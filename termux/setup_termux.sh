#!/bin/bash
echo "=== Налаштування середовища Termux для прошивки EDC16U34 ==="
pkg update -y
pkg install -y python python-pip termux-api git libusb
pip install pyserial pyftdi

echo "=== Готово! ==="
echo "Для перевірки портів виконайте: python edc16_flasher.py -l"
echo "Для прошивки виконайте: python edc16_flasher.py -p /dev/ttyUSB0 -f 03G906021QJ_stage1_refined_dpf_egr_off.bin"
