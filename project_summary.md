# Project Summary: GOLF5-ANDROID-FLASHER (Android ECU Flasher)
**Last Updated**: 2026-09-14 | **Status**: Phase 1 (EDC16U34 K-Line) 100% Implemented & Verified

## 1. Hardware & Configuration
- **Application**: Android ECU Flasher (`com.golf5.edc16flasher`).
- **Target OS**: Android 8.0+ (ARM64 / x86_64), verified on Samsung Galaxy S24 FE via ADB (`100.105.189.114:5555`).
- **Target ECU (Phase 1)**: Bosch EDC16U34 (VW Golf 5 1.9 TDI BLS) over K-Line (ISO 14230 / KWP2000).
- **Target ECU (Phase 2)**: Bosch EDC17C46 (VW Passat B7 2.0 TDI) over CAN UDS (ISO 14229 / ISO 15765-4).
- **Hardware Protocol**: MPPS v18 clone USB adapter protocol with reconstructed driver binary chunks (`mpps_drv_chunk1.bin`, `mpps_drv_chunk2.bin`).

## 2. Verified Invariants & Ground Truth (🟢)
- **Seed/Key Cryptography**: Security Access authentication (`0x27 0x01` / `0x27 0x02`) implemented and verified for Bosch EDC16.
- **Calibration Sector**: Calibration map sector size is 512 KB (`0x180000–0x1FFFFF`).
- **Bundled Calibration**: Verified binary `03G906021QJ_stage1_refined_CS_OK.bin` bundled in app assets with valid Bosch checksums and RSA signatures.
- **Battery Voltage Guard**: Flash writing is strictly aborted if measured battery voltage < 12.0 V to prevent bricking during programming.
- **Recovery Mode**: Dedicated recovery flash mode implemented to re-write calibration sector in case of interrupted sessions.
- **Termux CLI Alternative**: Headless console flasher `termux/edc16_flasher.py` + `termux/setup_termux.sh` verified for direct OTG execution.
- **Offline Emulator Verification**: 100% byte-for-byte readback verified against offline EDC16 emulator (`scratch/edc16_emulator.py`, `scratch/test_flasher_offline.py`).

## 3. Current Project Status
- Phase 1 complete: Debug APK assembled (`app\build\outputs\apk\debug\app-debug.apk` 7 MB), verified on S24 FE and offline emulator.

## 4. Key Decisions Made
- **Complete Decoupling**: Full isolation of hardware flasher from calibration (`golf5`) and diagnostic logger (`vcds-android`).
- **Mandatory Voltage Interlock**: Hard requirement for >= 12.0V battery voltage before allowing erase/write commands.

## 5. Active Working Hypotheses (🟡)
- Phase 2: CAN UDS loader implementation for Bosch EDC17C46 (VW Passat B7 2.0 TDI).
- Support for Tactrix OpenPort 2.0 (J2534) hardware adapter via Android USB Host.
- Wireless transport option via ESP32-S3 Wi-Fi WebSocket bridge.

## 6. Discarded Hypotheses (🔴 Do Not Repeat / Anti-Memory)
- **Discarded: Flashing without battery voltage interlocking.** Risk of permanent ECU brick if voltage drops during flash.
- **Discarded: Treating MPPS adapter response `0x55` as "unlocked" state in handshake.** Unsupported by reference protocol; full handshake required.
- **Discarded: Issuing `0x92` control request on MPPS adapter.** Wipes FTDI EEPROM and destroys adapter firmware.

## 7. Known Problems & Issues
- Live car flashing requires stable battery tender/charger to guarantee > 12.5V during programming.

## 8. Completed Work
- Reconstructed MPPS driver chunks and Seed/Key authorization.
- Built step-by-step UI: Cable Detect, Read ECU, Flash ECU, Recovery.
- Termux CLI alternative scripted and verified.

## 9. Next Steps
- Perform live vehicle identification and readback on Golf 5 1.9 TDI BLS.
- Prepare CAN UDS architecture for Phase 2 (EDC17C46).
