#!/usr/bin/env python3
"""
EDC16U34 KWP2000 OTG Flasher for Android (Termux / Linux / Windows)
Compatible with FTDI KKL 409.1, MPPS v18 / v16 / v13 K-Line cables via USB-OTG
Target: VW Golf 5 1.9 TDI BLS (03G906021QJ / SW 391847)
"""

import sys
import os
import time
import argparse

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    serial = None

TARGET_ECU = 0x01
SOURCE_DIAG = 0xF1

def calculate_key(seed: bytes) -> bytes:
    if len(seed) < 4:
        return b"\x00\x00\x00\x00"
    s0, s1, s2, s3 = seed[0], seed[1], seed[2], seed[3]
    seed_val = (s0 << 24) | (s1 << 16) | (s2 << 8) | s3
    poly = 0x4F73A1B2
    key_val = (seed_val ^ poly) & 0xFFFFFFFF
    # 32-bit rotate left 5
    key_val = (((key_val << 5) & 0xFFFFFFFF) | (key_val >> 27)) ^ 0x35A9C2E1
    key_val &= 0xFFFFFFFF
    return bytes([
        (key_val >> 24) & 0xFF,
        (key_val >> 16) & 0xFF,
        (key_val >> 8) & 0xFF,
        key_val & 0xFF
    ])

class Edc16Kwp2000:
    def __init__(self, port_name, baudrate=10400, timeout=3.0):
        if serial is None:
            raise ImportError("Модуль 'pyserial' не встановлено! Виконайте: pip install pyserial")
        print(f"[*] Відкриття порту {port_name} на швидкості {baudrate} бод...")
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

    def send_request(self, service_id: int, payload: bytes = b"") -> bytes:
        data_len = 1 + len(payload)
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
        
        # Checksum (sum of all bytes modulo 256)
        csum = sum(packet) & 0xFF
        packet.append(csum)

        # Send
        self.ser.write(packet)
        time.sleep(0.02)

        # Read response
        resp = self.ser.read(256)
        if len(resp) < 4:
            raise RuntimeError(f"Немає відповіді від ЕБУ або відповідь занадто коротка ({len(resp)} байт)")

        # Verify NRC (Negative Response Code)
        for i in range(len(resp) - 2):
            if resp[i] == 0x7F and resp[i+1] == service_id:
                nrc = resp[i+2]
                raise RuntimeError(f"Помилка ЕБУ: Service 0x{service_id:02X} NRC 0x{nrc:02X}")

        return resp

    def read_ecu_id(self) -> str:
        print("[*] Запит паспортних даних ЕБУ (0x1A 0x9B)...")
        try:
            resp = self.send_request(0x1A, b"\x9B")
        except Exception:
            print("[*] Повторний запит (0x21 0x80)...")
            resp = self.send_request(0x21, b"\x80")
        text = "".join(chr(b) for b in resp if 32 <= b <= 126)
        return text

    def enter_programming_session(self):
        print("[*] Перехід у режим програмування (Service 0x10 0x85)...")
        self.send_request(0x10, b"\x85")
        time.sleep(0.05)

    def unlock_security(self):
        print("[*] Запит Seed (Service 0x27 0x01)...")
        resp = self.send_request(0x27, b"\x01")
        # Extract seed (last 4 payload bytes before checksum)
        seed = resp[-5:-1]
        print(f"[+] Отримано Seed: {seed.hex().upper()}")
        key = calculate_key(seed)
        print(f"[+] Розраховано Key: {key.hex().upper()}")
        print("[*] Відправка Key (Service 0x27 0x02)...")
        self.send_request(0x27, b"\x02" + key)
        print("[+] Захисний доступ надано успішно!")

    def flash_binary(self, bin_path: str):
        if not os.path.isfile(bin_path):
            raise FileNotFoundError(f"Файл {bin_path} не знайдено!")
        
        file_size = os.path.getsize(bin_path)
        if file_size != 2097152:
            raise ValueError(f"Розмір файлу {file_size} байт! Очікується рівно 2097152 байти (2 MiB)")

        with open(bin_path, "rb") as f:
            firmware = f.read()

        print(f"[+] Прошивка завантажена: {len(firmware)} байт")
        
        # 1. Start Session
        self.enter_programming_session()
        
        # 2. Security Access
        self.unlock_security()

        # 3. Request Download (EDC16 Calibration sector 0x180000 - 0x200000 = 512 KB)
        cal_start = 0x180000
        cal_size = 0x080000 # 512 KB
        print(f"[*] Запит запису сектора калібрувань (0x{cal_start:06X} - 0x{cal_start+cal_size:06X})...")
        dl_param = bytes([
            0x00,
            (cal_start >> 16) & 0xFF,
            (cal_start >> 8) & 0xFF,
            cal_start & 0xFF,
            (cal_size >> 16) & 0xFF,
            (cal_size >> 8) & 0xFF,
            cal_size & 0xFF
        ])
        self.send_request(0x34, dl_param)
        print("[+] Запит на запис підтверджено ЕБУ.")

        # 4. Transfer Data
        block_size = 256
        total_blocks = cal_size // block_size
        block_seq = 1

        print(f"[*] Початок запису {total_blocks} блоків...")
        start_time = time.time()
        for i in range(total_blocks):
            offset = cal_start + (i * block_size)
            chunk = firmware[offset : offset + block_size]
            payload = bytes([block_seq]) + chunk
            self.send_request(0x36, payload)
            block_seq = (block_seq + 1) & 0xFF

            if (i + 1) % 32 == 0 or i == total_blocks - 1:
                progress = ((i + 1) / total_blocks) * 100
                elapsed = time.time() - start_time
                print(f"  -> Прогрес: {i+1}/{total_blocks} ({progress:.1f}%) | Час: {elapsed:.1f}с")

        # 5. Request Transfer Exit
        print("[*] Завершення сесії передачі (Service 0x37)...")
        self.send_request(0x37, b"")

        # 6. ECU Hard Reset
        print("[*] Перезавантаження ЕБУ (Service 0x11 0x01)...")
        try:
            self.send_request(0x11, b"\x01")
        except Exception:
            pass

        print("\n" + "="*60)
        print("[✓] ПРОШИВКА УСПІШНО ЗАПИСАНА В БЛОК EDC16U34!")
        print("Вимкніть запалювання на 10 секунд, потім запустіть двигун.")
        print("="*60 + "\n")

def list_available_ports():
    if serial is None:
        print("\n[!] Модуль 'pyserial' не знайдено в системі.")
        print("    Для встановлення виконайте: pip install pyserial\n")
        return
    ports = serial.tools.list_ports.comports()
    print("\n[?] Доступні послідовні / USB порти:")
    for p in ports:
        print(f"  - {p.device}: {p.description} (VID:PID={p.hwid})")
    print()

def main():
    parser = argparse.ArgumentParser(description="EDC16U34 USB-OTG Flasher for Android / Linux / Windows")
    parser.add_argument("-p", "--port", help="Послідовний порт (наприклад: /dev/ttyUSB0 або COM3)")
    parser.add_argument("-b", "--baud", type=int, default=10400, help="Швидкість передачі (за замовчуванням 10400)")
    parser.add_argument("-i", "--identify", action="store_true", help="Прочитати ідентифікатор ЕБУ")
    parser.add_argument("-f", "--flash", help="Шлях до бінарного файлу прошивки (.bin, 2097152 байт)")
    parser.add_argument("-l", "--list", action="store_true", help="Показати список доступних портів")

    args = parser.parse_args()

    if args.list or not args.port:
        list_available_ports()
        if not args.port:
            print("Вкажіть порт за допомогою -p <порт> (наприклад -p /dev/ttyUSB0)")
            return

    flasher = Edc16Kwp2000(args.port, args.baud)
    try:
        if args.identify:
            ecu_id = flasher.read_ecu_id()
            print(f"[+] Паспортні дані ЕБУ:\n{ecu_id}")
        elif args.flash:
            flasher.flash_binary(args.flash)
        else:
            ecu_id = flasher.read_ecu_id()
            print(f"[+] Паспортні дані ЕБУ:\n{ecu_id}")
    finally:
        flasher.close()

if __name__ == "__main__":
    main()
