#!/bin/bash
echo "=== Налаштування середовища Termux для прошивки EDC16U34 ==="
pkg update -y
pkg install -y python python-pip termux-api git libusb
pip install pyserial pyftdi

echo "=== Готово! ==="
echo "Для перевірки портів виконайте: python edc16_flasher.py -l"
echo "Для бекапу калібрування: python edc16_flasher.py -p /dev/ttyUSB0 -r backup.bin"
echo "Запис (-f) у CLI заблоковано (fail-closed): seed/key не верифіковано, напруга недоступна."
