#!/usr/bin/env python3
"""
Mobile MPPS v18 Engine for Android (Termux / Linux / Windows)
Target: VW Golf 5 1.9 TDI BLS (Bosch EDC16U34, SW 391847)

Capabilities (see README status table; nothing here is a hardware-verified claim):
- ECU Identification (0x1A 0x9B / 0x21 0x80)
- Read Calibration Area to file (0x35 RequestUpload)
- Bosch EDC16 checksum fix/verify for the declared profile only (0xD01FE500 residue)
- Clear DTCs (0x14 Clear Diagnostic Info)
- Write: FAIL-CLOSED. The seed/key formula is unverified, so --flash refuses before any
  ECU traffic. When verified vectors exist, the write path still requires both ECU
  identifiers, a pre-write backup and a full SHA-256 read-back before reporting success.
"""

import sys
import os
import time
import argparse
import hashlib
import struct

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    serial = None

TARGET_ECU = 0x01
SOURCE_DIAG = 0xF1

# ── Firmware profile: must equal EcuFirmwareProfile.EDC16U34_03G906021QJ_391847 (Kotlin) ──
PROFILE_ID = "EDC16U34_03G906021QJ_391847"
FULL_IMAGE_SIZE = 0x200000
CAL_START = 0x180000
CAL_SIZE = 0x080000
EXPECTED_RESIDUE = 0xD01FE500
# (start, end_exclusive, patch_word_offset)
CHECKSUM_BLOCKS = (
    (0x180000, 0x1C0000, 0x1BFFFC),
    (0x1C0000, 0x1FE000, 0x1FDFFC),
)
REQUIRED_ECU_IDS = ("03G906021QJ", "391847")
MIN_PROGRAMMING_VOLTAGE = 12.2

# The legacy BLS seed/key formula below has no committed real-ECU vectors.
# While False, flash_binary() refuses before opening a programming session.
SECURITY_ALGORITHM_VERIFIED = False
# Plain KKL serial has no battery-voltage readout; the voltage gate cannot be satisfied.
CLI_VOLTAGE_SOURCE_AVAILABLE = False


class InvalidHexError(ValueError):
    pass


def parse_hex(text: str) -> bytes:
    """Strict hex parser (parity with BridgeCommandPolicy.parseHexStrict in Kotlin)."""
    s = text.strip()
    if s[:2] in ("0x", "0X"):
        s = s[2:]
    tokens = [t for t in s.split(" ") if t]
    if len(tokens) > 1 and any(len(t) != 2 for t in tokens):
        raise InvalidHexError(f"space-separated hex must use 2-digit bytes: {text!r}")
    clean = "".join(tokens)
    if not clean:
        raise InvalidHexError("empty hex")
    if len(clean) % 2:
        raise InvalidHexError(f"odd-length hex: {text!r}")
    if any(c not in "0123456789abcdefABCDEF" for c in clean):
        raise InvalidHexError(f"non-hex character in {text!r}")
    return bytes.fromhex(clean)


def block_residue(image: bytes, start: int, end: int) -> int:
    body = image[start:end]
    return sum(struct.unpack(f">{len(body)//4}I", body)) & 0xFFFFFFFF


def verify_edc16_checksum(image: bytes) -> bool:
    if len(image) != FULL_IMAGE_SIZE:
        return False
    return all(block_residue(image, start, end) == EXPECTED_RESIDUE for start, end, _ in CHECKSUM_BLOCKS)


def ecu_id_matches(ecu_id: str) -> bool:
    return all(required in ecu_id for required in REQUIRED_ECU_IDS)

def calculate_key(seed: bytes) -> bytes:
    if len(seed) < 4:
        raise ValueError("Invalid seed length (< 4 bytes)")
    s0, s1, s2, s3 = seed[0], seed[1], seed[2], seed[3]
    seed_val = (s0 << 24) | (s1 << 16) | (s2 << 8) | s3
    poly = 0x4F73A1B2
    key_val = (seed_val ^ poly) & 0xFFFFFFFF
    key_val = (((key_val << 5) & 0xFFFFFFFF) | (key_val >> 27)) ^ 0x35A9C2E1
    key_val &= 0xFFFFFFFF
    return bytes([
        (key_val >> 24) & 0xFF,
        (key_val >> 16) & 0xFF,
        (key_val >> 8) & 0xFF,
        key_val & 0xFF
    ])

def fix_edc16_checksum(firmware_bytes: bytearray) -> bytearray:
    """
    Bosch EDC16U34 checksum fix for the declared profile only (in place, also returned):
    - Block 1 [0x180000, 0x1C0000): 32-bit BE sum == 0xD01FE500 (patch at 0x1BFFFC)
    - Block 2 [0x1C0000, 0x1FE000): 32-bit BE sum == 0xD01FE500 (patch at 0x1FDFFC)
    """
    if len(firmware_bytes) != FULL_IMAGE_SIZE:
        raise ValueError(f"Image size {len(firmware_bytes)} != {FULL_IMAGE_SIZE}")

    for start, end, patch in CHECKSUM_BLOCKS:
        body_sum = (block_residue(firmware_bytes, start, patch) + block_residue(firmware_bytes, patch + 4, end)) & 0xFFFFFFFF
        needed = (EXPECTED_RESIDUE - body_sum) & 0xFFFFFFFF
        firmware_bytes[patch : patch + 4] = struct.pack(">I", needed)
        print(f"[MPPS] CS 0x{patch:06X}: 0x{needed:08X}")

    if not verify_edc16_checksum(firmware_bytes):
        raise RuntimeError("Checksum fix failed independent verification")
    return firmware_bytes

class MobileMppsEngine:
    def __init__(self, port_name, baudrate=10400, timeout=2.0):
        if serial is None:
            raise ImportError("Модуль 'pyserial' не знайдено! Виконайте: pip install pyserial")
        print(f"[*] MPPS: Відкриття порту {port_name} ({baudrate} бод)...")
        self.ser = serial.Serial(
            port=port_name,
            baudrate=baudrate,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=timeout
        )
        self.ser.flushInput()
        self.ser.flushOutput()

    def close(self):
        self.ser.close()

    def send_request(self, service_id: int, payload: bytes = b"", timeout=6.0) -> bytes:
        data_len = 1 + len(payload)
        if data_len > 255:
            raise ValueError(f"Payload {data_len} exceeds KWP single-byte length limit")

        packet = bytearray()
        if data_len <= 63:
            packet.append(0x80 | data_len)
            packet.append(TARGET_ECU)
            packet.append(SOURCE_DIAG)
        else:
            packet.append(0x80)
            packet.append(TARGET_ECU)
            packet.append(SOURCE_DIAG)
            packet.append(data_len)

        packet.append(service_id)
        packet.extend(payload)
        csum = sum(packet) & 0xFF
        packet.append(csum)

        self.ser.flushInput()
        self.ser.write(packet)
        time.sleep(0.01)

        start_time = time.time()
        expected_pos_sid = service_id + 0x40

        while (time.time() - start_time) < timeout:
            resp = self.ser.read(256)
            if len(resp) >= len(packet) and resp[:len(packet)] == packet:
                # Discard local K-Line echo
                resp = resp[len(packet):]

            if not resp:
                time.sleep(0.02)
                continue

            for i in range(len(resp) - 2):
                if resp[i] == 0x7F and resp[i+1] == service_id:
                    nrc = resp[i+2]
                    if nrc == 0x78:
                        time.sleep(0.05)
                        continue
                    else:
                        raise RuntimeError(f"Помилка ЕБУ: Service 0x{service_id:02X} NRC 0x{nrc:02X}")

            if expected_pos_sid in resp:
                return resp

            time.sleep(0.02)

        raise TimeoutError(f"Таймаут очікування відповіді на Service 0x{service_id:02X}")

    def read_ecu_id(self) -> str:
        print("[*] MPPS: Запит паспортних даних ЕБУ (0x1A 0x9B)...")
        try:
            resp = self.send_request(0x1A, b"\x9B")
        except Exception:
            resp = self.send_request(0x21, b"\x80")
        text = "".join(chr(b) for b in resp if 32 <= b <= 126)
        return text

    def enter_programming_session(self):
        print("[*] MPPS: Вхід у сесію програмування (0x10 0x85)...")
        self.send_request(0x10, b"\x85")
        time.sleep(0.05)

    def unlock_security(self):
        print("[*] MPPS: Запит Security Seed (0x27 0x01)...")
        resp = self.send_request(0x27, b"\x01")
        idx = resp.find(b"\x67\x01")
        if idx != -1 and len(resp) >= idx + 6:
            seed = resp[idx+2 : idx+6]
        else:
            seed = resp[-5:-1]
        print(f"[+] MPPS: Отримано Seed: {seed.hex().upper()}")
        key = calculate_key(seed)
        print(f"[+] MPPS: Розраховано Key: {key.hex().upper()}")
        self.send_request(0x27, b"\x02" + key)
        print("[+] MPPS: Захисний доступ успішно розблоковано!")

    def clear_dtcs(self):
        print("[*] MPPS: Очищення кодів несправностей (Clear DTC Service 0x14)...")
        try:
            self.send_request(0x14, b"\xFF\x00")
            print("[+] MPPS: Коди помилок успішно очищені!")
        except Exception as e:
            print(f"[-] MPPS: Не вдалося очистити DTC: {e}")

    def read_ecu(self, out_path: str):
        print(f"[*] MPPS: Початок зчитування калібрувальної області (512 КБ)...")
        self.enter_programming_session()
        self.unlock_security()

        cal_data = self.read_calibration()
        try:
            self.send_request(0x11, b"\x01")
        except Exception:
            pass

        full_image = bytearray([0xFF] * FULL_IMAGE_SIZE)
        full_image[CAL_START : CAL_START + CAL_SIZE] = cal_data
        with open(out_path, "wb") as f:
            f.write(full_image)
            f.flush()
            os.fsync(f.fileno())

        print(f"\n[✓] MPPS: Калібрування зчитано і збережено в {out_path} ({len(full_image)} байт)")
        print(f"    SHA-256 (512 КБ): {hashlib.sha256(cal_data).hexdigest()}")

    def read_calibration(self) -> bytes:
        """RequestUpload + TransferData + TransferExit for the full calibration region."""
        cal_start = CAL_START
        cal_size = CAL_SIZE
        dl_param = bytes([
            0x00,
            (cal_start >> 16) & 0xFF,
            (cal_start >> 8) & 0xFF,
            cal_start & 0xFF,
            (cal_size >> 16) & 0xFF,
            (cal_size >> 8) & 0xFF,
            cal_size & 0xFF
        ])
        resp_35 = self.send_request(0x35, dl_param)
        idx_75 = resp_35.find(b"\x75")
        if idx_75 == -1:
            raise RuntimeError("Помилка: ЕБУ відхилив запит вивантаження (RequestUpload 0x35)")

        # Parse negotiated upload block length from 0x75 response
        block_size = 128
        tail = resp_35[idx_75 + 1 : -1] # exclude 0x75 and trailing CS
        if len(tail) >= 2:
            advertised = (tail[0] << 8) | tail[1]
            if 32 <= advertised <= 512:
                block_size = advertised
        elif len(tail) == 1:
            if 32 <= tail[0] <= 255:
                block_size = tail[0]

        total_blocks = (cal_size + block_size - 1) // block_size
        cal_data = bytearray()

        print(f"[*] MPPS: Погоджено розмір блоку: {block_size} байт. Зчитування {total_blocks} блоків...")
        start_time = time.time()
        for i in range(total_blocks):
            payload = bytes([(i + 1) & 0xFF])
            resp = self.send_request(0x36, payload)
            # Find 0x76 (positive response to 0x36)
            idx = resp.find(b"\x76")
            if idx == -1 or len(resp) < idx + 3:
                raise RuntimeError(f"Помилка зчитування блоку {i+1}: відсутня валідна відповідь 0x76 від ЕБУ")
            chunk = resp[idx+2 : len(resp)-1]
            if len(chunk) == 0:
                raise RuntimeError(f"Помилка зчитування блоку {i+1}: порожні дані від ЕБУ")
            cal_data.extend(chunk)

            if (i + 1) % 64 == 0 or i == total_blocks - 1:
                progress = ((i + 1) / total_blocks) * 100
                print(f"  -> Прогрес: {i+1}/{total_blocks} ({progress:.1f}%)")

        if len(cal_data) != cal_size:
            raise RuntimeError(f"Помилка зчитування: отримано {len(cal_data)} байт з очікуваних {cal_size} байт!")

        self.send_request(0x37, b"")
        return bytes(cal_data)

    def flash_binary(self, bin_path: str, is_recovery: bool = False):
        firmware = check_flash_preconditions(bin_path)

        if not is_recovery:
            ecu_id = self.read_ecu_id()
            print(f"[+] MPPS Ідентифікація ЕБУ: {ecu_id}")
            if not ecu_id_matches(ecu_id):
                raise RuntimeError(f"ЗАХИСНЕ БЛОКУВАННЯ: ID ЕБУ має містити {REQUIRED_ECU_IDS}, отримано: {ecu_id!r}")
        else:
            print("[!] RECOVERY: перевірку ID пропущено (усі інші перевірки діють).")

        self.enter_programming_session()
        self.unlock_security()

        # Backup before the first destructive request.
        backup = self.read_calibration()
        backup_path = bin_path + f".prewrite_backup_{time.strftime('%Y%m%d_%H%M%S')}.bin"
        full_backup = bytearray([0xFF] * FULL_IMAGE_SIZE)
        full_backup[CAL_START : CAL_START + CAL_SIZE] = backup
        with open(backup_path, "wb") as f:
            f.write(full_backup)
            f.flush()
            os.fsync(f.fileno())
        print(f"[+] Бекап: {backup_path} SHA-256 {hashlib.sha256(backup).hexdigest()}")

        print(f"[*] MPPS: RequestDownload 0x{CAL_START:06X}..0x{CAL_START + CAL_SIZE:06X}")
        dl_param = bytes([
            0x00,
            (CAL_START >> 16) & 0xFF, (CAL_START >> 8) & 0xFF, CAL_START & 0xFF,
            (CAL_SIZE >> 16) & 0xFF, (CAL_SIZE >> 8) & 0xFF, CAL_SIZE & 0xFF,
        ])
        self.send_request(0x34, dl_param)

        block_size = 128
        total_blocks = CAL_SIZE // block_size
        start_time = time.time()
        for i in range(total_blocks):
            offset = CAL_START + i * block_size
            payload = bytes([(i + 1) & 0xFF]) + bytes(firmware[offset : offset + block_size])
            self.send_request(0x36, payload)
            if (i + 1) % 64 == 0 or i == total_blocks - 1:
                print(f"  -> {i+1}/{total_blocks} | {time.time() - start_time:.1f}с")

        self.send_request(0x37, b"")

        intended = bytes(firmware[CAL_START : CAL_START + CAL_SIZE])
        read_back = self.read_calibration()
        if hashlib.sha256(read_back).digest() != hashlib.sha256(intended).digest():
            raise RuntimeError("READ-BACK SHA-256 НЕ ЗБІГАЄТЬСЯ: запис не верифіковано. НЕ вимикайте запалювання.")

        try:
            self.send_request(0x11, b"\x01")
        except Exception:
            print("[!] ЕБУ не підтвердив Reset — вимкніть запалювання на 10 с вручну.")

        print(f"[✓] Запис верифіковано read-back SHA-256: {hashlib.sha256(intended).hexdigest()}")


def check_flash_preconditions(bin_path: str) -> bytearray:
    """Offline gates checked before any ECU traffic. Returns the checksum-fixed image."""
    if not SECURITY_ALGORITHM_VERIFIED:
        raise RuntimeError(
            "BLOCKED: SECURITY_ALGORITHM_UNVERIFIED — seed/key формула не підтверджена "
            "реальними векторами. Запис вимкнено (fail-closed)."
        )
    if not CLI_VOLTAGE_SOURCE_AVAILABLE:
        raise RuntimeError(
            f"BLOCKED: VOLTAGE_UNAVAILABLE — CLI не вміє читати напругу бортмережі, "
            f"а запис вимагає >= {MIN_PROGRAMMING_VOLTAGE} V безпосередньо перед програмуванням."
        )
    if not os.path.isfile(bin_path):
        raise FileNotFoundError(f"Файл {bin_path} не знайдено!")
    size = os.path.getsize(bin_path)
    if size != FULL_IMAGE_SIZE:
        raise ValueError(f"Розмір файлу {size} байт, потрібно рівно {FULL_IMAGE_SIZE}")
    with open(bin_path, "rb") as f:
        firmware = fix_edc16_checksum(bytearray(f.read()))
    if not verify_edc16_checksum(firmware):
        raise RuntimeError("CHECKSUM_INVALID")
    return firmware


def list_available_ports():
    if serial is None:
        print("\n[!] Модуль 'pyserial' не знайдено. Встановіть: pip install pyserial\n")
        return
    ports = serial.tools.list_ports.comports()
    print("\n[?] Доступні послідовні / USB порти:")
    for p in ports:
        print(f"  - {p.device}: {p.description} (VID:PID={p.hwid})")
    print()

def main():
    parser = argparse.ArgumentParser(description="Mobile MPPS v18 Engine for Android / Linux / Windows")
    parser.add_argument("-p", "--port", help="Послідовний порт (наприклад: /dev/ttyUSB0 або COM3)")
    parser.add_argument("-b", "--baud", type=int, default=10400, help="Швидкість (за замовчуванням 10400)")
    parser.add_argument("-i", "--identify", action="store_true", help="Прочитати дані ЕБУ (ECU ID)")
    parser.add_argument("-r", "--read", help="Зчитати калібровки у вказаний файл (.bin)")
    parser.add_argument("-f", "--flash", help="Записати вказаний файл прошивки (.bin)")
    parser.add_argument("--recovery", action="store_true", help="Режим аварійного відновлення (Recovery)")
    parser.add_argument("-c", "--clear-dtc", action="store_true", help="Очистити коди помилок (Clear DTC)")
    parser.add_argument("-l", "--list", action="store_true", help="Показати доступні порти")

    args = parser.parse_args()

    if args.list or not args.port:
        list_available_ports()
        if not args.port:
            print("Вкажіть порт: -p <порт> (наприклад -p /dev/ttyUSB0)")
            return

    if args.flash:
        # Offline gates first: refuse before the serial port is even opened.
        try:
            check_flash_preconditions(args.flash)
        except Exception as e:
            print(f"[-] {e}")
            sys.exit(2)

    engine = MobileMppsEngine(args.port, args.baud)
    try:
        if args.identify:
            print(f"[+] Паспортні дані:\n{engine.read_ecu_id()}")
        elif args.read:
            engine.read_ecu(args.read)
        elif args.clear_dtc:
            engine.clear_dtcs()
        elif args.flash:
            engine.flash_binary(args.flash, is_recovery=args.recovery)
        else:
            print(f"[+] Паспортні дані:\n{engine.read_ecu_id()}")
    finally:
        engine.close()

if __name__ == "__main__":
    main()
