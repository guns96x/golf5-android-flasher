"""Offline evidence extraction only. No USB, serial, ADB, or ECU operations."""
from pathlib import Path
import hashlib
import importlib.util
import json
import random
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
DEFINITIONS = Path(r"D:\golf5-ecu-system\diagnostic-review\definitions\03G906021QJ_1984_391847_P447_HAXN_EDC16U34_3.42")
SGM = DEFINITIONS / "03G906021QJ_1984_391847_P447_HAXN_EDC16U34_3.42.sgm"
FACTORY = Path(r"D:\golf5-ecu-system\diagnostic-review\reference-from-hex.analysis-only.bin")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def calculate_documented_key(seed):
    """Exact twelve-byte tape decoded as five RSL/conditional XOR iterations."""
    if not 0 <= seed <= 0xFFFFFFFF:
        raise ValueError("seed must be an unsigned 32-bit integer")
    for _ in range(5):
        carry = seed >> 31
        seed = ((seed << 1) | carry) & 0xFFFFFFFF
        if carry:
            seed ^= 0x0A221289
    return seed


def main():
    root = ET.fromstring(SGM.read_bytes())
    text = root.findtext(".//KWP-2000-SA2")
    tape = [int(part.strip(), 16) for part in text.split(",")]
    expected = [0x68, 5, 0x81, 0x4A, 5, 0x87, 0x0A, 0x22, 0x12, 0x89, 0x49, 0x4C]
    if tape != expected:
        raise ValueError("SGM script differs from the explicitly reviewed tape")
    vm_path = HERE / "upstream_sa2_seed_key.py"
    spec = importlib.util.spec_from_file_location("upstream_sa2", vm_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    rng = random.Random(391847)
    edge_seeds = [0, 1, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0x12345678, 0x1A1B1C1D]
    seeds = edge_seeds + [rng.getrandbits(32) for _ in range(10000)]
    for seed in seeds:
        result = module.Sa2SeedKey(tape, seed).execute()
        if result != calculate_documented_key(seed):
            raise AssertionError(f"VM disagreement for seed {seed:08X}")

    factory = FACTORY.read_bytes()
    if len(factory) != 0x200000:
        raise ValueError("Expected complete 2 MiB factory reconstruction")
    tape_offsets = []
    search_start = 0
    while True:
        found = factory.find(bytes(tape), search_start)
        if found < 0:
            break
        tape_offsets.append(f"0x{found:06X}")
        search_start = found + 1
    if not tape_offsets:
        raise AssertionError("Exact SGM SA2 tape is absent from factory BIN")
    blocks = []
    for block in root.findall(".//DATENBLOCK"):
        erase = block.find("LOESCH-BEREICH")
        check = block.find("DATENBLOCK-CHECK")
        check_start = int(check.findtext("START-ADR"), 16)
        check_end = int(check.findtext("END-ADR"), 16)
        checksum = int(check.findtext("CHECKSUMME"), 16)
        observed = sum(factory[check_start:check_end + 1]) & 0xFFFF
        blocks.append({
            "name": block.findtext("DATENBLOCK-NAME"),
            "data_start": block.findtext("START-ADR"),
            "decompressed_size": block.findtext("GROESSE-DEKOMPRIMIERT"),
            "format": block.findtext("DATENBLOCK-FORMAT"),
            "erase_start": erase.findtext("START-ADR"),
            "erase_end_inclusive": erase.findtext("END-ADR"),
            "check_start": check.findtext("START-ADR"),
            "check_end_inclusive": check.findtext("END-ADR"),
            "sgm_checksum": f"0x{checksum:04X}",
            "factory_byte_sum16": f"0x{observed:04X}",
            "sum16_matches": checksum == observed,
        })
    report = {
        "operation": "OFFLINE_ONLY",
        "sgm_path": str(SGM), "sgm_sha256": sha256(SGM),
        "factory_path": str(FACTORY), "factory_sha256": sha256(FACTORY),
        "identity": {node.tag: node.text for node in root.find("IDENT")},
        "kwp_target": root.findtext(".//KWP-2000-TGT"),
        "sa2_tape_hex": bytes(tape).hex(" ").upper(),
        "sa2_status": "EXACT_CONTAINER_DOCUMENTED_AND_OFFLINE_VM_CROSSCHECKED",
        "hardware_security_access": "NOT_TESTED",
        "complete_sa2_tape_offsets_in_factory_bin": tape_offsets,
        "vm_sha256": sha256(vm_path),
        "vm_source": "https://github.com/bri3d/sa2_seed_key/blob/master/sa2_seed_key/sa2_seed_key.py",
        "crosscheck_cases": len(seeds),
        "vectors_origin": "SYNTHETIC_OFFLINE_COMPUTATION_NOT_ECU_CAPTURE",
        "vectors": [{"seed": f"{seed:08X}", "key": f"{calculate_documented_key(seed):08X}"} for seed in edge_seeds],
        "blocks": blocks,
        "timing_raw_container_values": {node.tag: node.text for node in root.find(".//KWP-2000-ACP")},
        "limitations": [
            "SGM logical block names are not TransferData sequence counters.",
            "Container geometry does not establish MPPS v18 USB behavior or physical packet size.",
            "No hardware security subfunction, session sequence, or write operation validated.",
            "Factory reconstruction is not the car's full recovery backup.",
        ],
    }
    (HERE / "exact-sgm-evidence.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"sa2_tape": report["sa2_tape_hex"], "tape_offsets": tape_offsets, "crosscheck_cases": len(seeds), "vectors": report["vectors"], "blocks": blocks}, indent=2))


if __name__ == "__main__":
    main()
