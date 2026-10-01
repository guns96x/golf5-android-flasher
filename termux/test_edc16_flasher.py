"""Offline tests for termux/edc16_flasher.py (no serial port, no ECU).

Run from the repository root:  python -m unittest termux/test_edc16_flasher.py
"""
import os
import re
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edc16_flasher as f  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOTLIN_PROFILE = os.path.join(
    REPO, "app/src/main/java/com/golf5/edc16flasher/firmware/EcuFirmwareProfile.kt"
)


def synthetic_image() -> bytearray:
    img = bytearray(b"\xFF" * 0x200000)
    for i in range(0x180000, 0x200000):
        img[i] = (i * 7 + 3) & 0xFF
    return img


class ProfileParityTest(unittest.TestCase):
    def test_known_literal_values(self):
        self.assertEqual(f.FULL_IMAGE_SIZE, 0x200000)
        self.assertEqual(f.CAL_START, 0x180000)
        self.assertEqual(f.CAL_SIZE, 0x080000)
        self.assertEqual(f.EXPECTED_RESIDUE, 0xD01FE500)
        self.assertEqual(f.CHECKSUM_BLOCKS, ((0x180000, 0x1C0000, 0x1BFFFC), (0x1C0000, 0x1FE000, 0x1FDFFC)))
        self.assertEqual(set(f.REQUIRED_ECU_IDS), {"03G906021QJ", "391847"})
        self.assertEqual(f.MIN_PROGRAMMING_VOLTAGE, 12.2)

    def test_constants_match_kotlin_profile_source(self):
        with open(KOTLIN_PROFILE, encoding="utf-8") as fh:
            src = fh.read()

        def kt(name):
            return int(re.search(name + r"\s*=\s*(0x[0-9A-Fa-f]+)", src).group(1), 16)

        self.assertEqual(kt("fullImageSize"), f.FULL_IMAGE_SIZE)
        self.assertEqual(kt("calibrationStart"), f.CAL_START)
        self.assertEqual(kt("calibrationSize"), f.CAL_SIZE)
        blocks = tuple(
            (int(a, 16), int(b, 16), int(c, 16), int(d, 16))
            for a, b, c, d in re.findall(
                r"ChecksumBlock\((0x[0-9A-F]+),\s*(0x[0-9A-F]+),\s*(0x[0-9A-F]+),\s*(0x[0-9A-F]+)L\)", src
            )
        )
        self.assertEqual(tuple(b[:3] for b in blocks), f.CHECKSUM_BLOCKS)
        self.assertTrue(all(b[3] == f.EXPECTED_RESIDUE for b in blocks))
        ids = set(re.search(r"requiredIdentifiers\s*=\s*setOf\(([^)]*)\)", src).group(1).replace('"', "").replace(" ", "").split(","))
        self.assertEqual(ids, set(f.REQUIRED_ECU_IDS))


class ChecksumTest(unittest.TestCase):
    def test_fix_produces_expected_residue_in_both_blocks(self):
        img = f.fix_edc16_checksum(synthetic_image())
        for start, end, _ in f.CHECKSUM_BLOCKS:
            body = bytes(img[start:end])
            residue = sum(struct.unpack(f">{len(body)//4}I", body)) & 0xFFFFFFFF
            self.assertEqual(residue, 0xD01FE500)
        self.assertTrue(f.verify_edc16_checksum(img))

    def test_fix_only_touches_patch_words(self):
        original = synthetic_image()
        fixed = f.fix_edc16_checksum(bytearray(original))
        diff = [i for i in range(len(original)) if original[i] != fixed[i]]
        patch_bytes = {p + k for _, _, p in f.CHECKSUM_BLOCKS for k in range(4)}
        self.assertTrue(set(diff) <= patch_bytes)

    def test_single_byte_change_breaks_verification(self):
        img = f.fix_edc16_checksum(synthetic_image())
        img[0x180010] ^= 0x01
        self.assertFalse(f.verify_edc16_checksum(img))

    def test_wrong_size_rejected(self):
        with self.assertRaises(ValueError):
            f.fix_edc16_checksum(bytearray(0x100000))
        self.assertFalse(f.verify_edc16_checksum(bytes(0x100000)))


class HexParserTest(unittest.TestCase):
    def test_accepts(self):
        for text in ("AABBCC", "aabbcc", "0xAABBCC", "AA BB CC"):
            self.assertEqual(f.parse_hex(text), b"\xAA\xBB\xCC")

    def test_rejects(self):
        for bad in ("ABC", "00GG", "", "AA0xBB", "0x0xAA", "A ABB", "-1"):
            with self.assertRaises(f.InvalidHexError, msg=bad):
                f.parse_hex(bad)


class WriteGateTest(unittest.TestCase):
    def test_ecu_id_requires_both_identifiers(self):
        self.assertTrue(f.ecu_id_matches("03G906021QJ 391847 EDC16U34"))
        self.assertFalse(f.ecu_id_matches("03G906021QJ only"))
        self.assertFalse(f.ecu_id_matches("391847 only"))

    def test_flash_is_fail_closed_while_security_unverified(self):
        self.assertFalse(f.SECURITY_ALGORITHM_VERIFIED)
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as tmp:
            tmp.write(f.fix_edc16_checksum(synthetic_image()))
            path = tmp.name
        try:
            with self.assertRaisesRegex(RuntimeError, "SECURITY_ALGORITHM_UNVERIFIED"):
                f.check_flash_preconditions(path)
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
