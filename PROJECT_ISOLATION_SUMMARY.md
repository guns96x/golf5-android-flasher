# Android ECU Flasher Project (Standalone Context Brief)

> **Призначення документу:** Повна ізоляція проєкту апаратного прошивальника (`golf5-android-flasher`) від проєкту калібрування прошивки (`golf5`). Використовуйте цей файл як стартовий контекст для окремого чату з розробки та підтримки флешера.

---

## 1. Репозиторій та розташування
* **Локальний шлях проекту:** `C:\Users\pavlo\golf5-android-flasher`
* **Цільова платформа:** Android 8.0+ (ARM64 / x86_64), протестовано на **Samsung Galaxy S24 FE**.
* **Цільові ЕБУ:**
  1. **Phase 1 (Реалізовано):** Bosch EDC16U34 (VW Golf 5 1.9 TDI BLS) по K-Line (ISO 14230 / KWP2000).
  2. **Phase 2 (Заплановано):** Bosch EDC17C46 (VW Passat B7 2.0 TDI) по CAN UDS (ISO 14229 / ISO 15765-4).

---

## 2. Поточний статус реалізації (100% готовність Phase 1)
1. **Android Застосунок (`com.golf5.edc16flasher`)**:
   * Зібрано налагоджувальний APK: `app\build\outputs\apk\debug\app-debug.apk` (7 МБ).
   * Встановлено та верифіковано на Samsung Galaxy S24 FE через ADB (`100.105.189.114:5555`).
   * Вбудований покроковий UI: виявлення кабелю, зчитування ID ЕБУ, зчитування дампу (Read ECU), запис прошивки (Flash ECU) та відновлення (Recovery).
   * Захист: заборона запису при напрузі акумулятора < 12.0V, перевірка контрольних сум файлу.
2. **KWP2000 MPPS Протокол**:
   * Повністю відтворено вендорний протокол MPPS v18 (драйверні чанки `mpps_drv_chunk1.bin`, `mpps_drv_chunk2.bin`).
   * Реалізовано Seed/Key авторизацію (Security Access `0x27 0x01` / `0x27 0x02`) під Bosch EDC16.
   * Верифіковано на автономному офлайн-емуляторі ЕБУ (`scratch/edc16_emulator.py`, `scratch/test_flasher_offline.py`): зчитування калібрувального сектора 512 КБ пройшло байт-в-байт.
3. **Termux CLI альтернатива**:
   * Скрипт `termux/edc16_flasher.py` + `termux/setup_termux.sh` для прямої прошивки з консолі Android через OTG без графічного інтерфейсу.
4. **Готовий бінарник у бандлі**:
   * В асетах додатку лежить перевірений файл з валідними контрольними сумами: `03G906021QJ_stage1_refined_CS_OK.bin`.

---

## 3. Майбутній беклог для окремого чату по флешеру
* Підтримка апаратного адаптера **Tactrix OpenPort 2.0 (J2534)** через Android USB Host (`android.hardware.usb.UsbManager`).
* Реалізація CAN UDS завантажувача для EDC17C46 (Passat B7).
* Додавання бездротового транспорту (Wi-Fi WebSocket / ESP32-S3).

---

## 4. Як запустити окремий чат
1. Відкрийте нове вікно/діалог Antigravity або додайте робочу директорію `C:\Users\pavlo\golf5-android-flasher`.
2. Надішліть перше повідомлення:  
   *«Працюємо виключно над Android флешером з папки C:\Users\pavlo\golf5-android-flasher. Ознайомся з PROJECT_ISOLATION_SUMMARY.md»*.
