# Golf 5 1.9 TDI BLS — автономний Android USB-OTG флешер
**ЕБУ**: Bosch EDC16U34 (`03G906021QJ`, SW `391847` / `1037391847P447HAXN`)  
**Автомобіль**: Volkswagen Golf 5 (1K1), 1.9 TDI 105 к.с. (BLS)

Автономний Android-застосунок для роботи з EDC16U34 через USB-OTG та адаптер MPPS / KKL 409.1.
Не потребує Termux, ПК, інтернету чи сторонніх застосунків (дозволу INTERNET немає).

> ⚠️ **Фізичний запис у реальний ЕБУ зараз ЗАБЛОКОВАНО (fail-closed).** Алгоритм Seed/Key для BLS
> не підтверджено реальними векторами, тому застосунок показує `PHYSICAL / READ ONLY`.
> Повний цикл запису (бекап → запис → повне зчитування → SHA-256) перевірено **тільки на вбудованому емуляторі**.

Документи, що визначають поведінку:
- Дизайн: [`docs/superpowers/specs/2026-09-15-edc16-flasher-hardening-design.md`](docs/superpowers/specs/2026-09-15-edc16-flasher-hardening-design.md)
- План: [`docs/superpowers/plans/2026-09-15-edc16-flasher-hardening.md`](docs/superpowers/plans/2026-09-15-edc16-flasher-hardening.md)
- Журнал виконання: [`docs/superpowers/plans/2026-09-15-edc16-flasher-hardening-worklog.md`](docs/superpowers/plans/2026-09-15-edc16-flasher-hardening-worklog.md)
- Контракт для агентів: [`GEMINI.md`](GEMINI.md)

## ✅ Статус можливостей

| Можливість | Статус |
|---|---|
| Offline ECU ID (емулятор) | Tested |
| Offline 512 KiB read (емулятор) | Tested |
| Offline 512 KiB write + read-back verify (емулятор, `EmulatorFlashIntegrationTest`) | Tested (CI) |
| KWP2000 frame parser (ISO 14230, NRC 0x78) | Tested |
| Checksum profile `0xD01FE500` | Tested for declared profile only (`EDC16U34_03G906021QJ_391847`) |
| Real BLS seed/key | **Unverified** until real capture vectors exist → physical write refused |
| MPPS known challenge vectors (2 шт.) | Tested; unknown challenge → **fail-closed** |
| Physical ECU ID / read (backup) | Implemented, not hardware-verified in this repo |
| Physical write | **Not release-ready**: blocked by `SECURITY_ALGORITHM_UNVERIFIED` |

### Ворота запису (усі мають бути виконані)
Транспорт підключено; ID містить і `03G906021QJ`, і `391847` (Recovery пропускає лише цю перевірку);
напруга читається і ≥ 12.2 V безпосередньо перед `RequestDownload`; образ рівно 2 MiB; КС профілю валідна;
алгоритм security `verified = true`; MPPS-автентифікація верифікована; бекап у цій сесії; немає іншої операції на транспорті.
Успіх повідомляється **тільки** після повного зчитування 512 KiB і збігу SHA-256.

Причини блокування показуються текстом під банером режиму (`EMULATOR`, `PHYSICAL / READ ONLY`,
`PHYSICAL / WRITE ELIGIBLE`, `FLASHING - DO NOT DISCONNECT`); натисніть на банер для деталей.

---

## 📁 Структура Проекту

```
golf5-android-flasher/
├── app/src/main/java/com/golf5/edc16flasher/
│   ├── MainActivity.kt              # UI: банер режиму, гейтинг кнопок за FlashEligibility
│   ├── flashing/                    # FlashEligibility (ворота) + FlashTransaction (типізований запис)
│   ├── firmware/                    # Профіль EDC16U34 і рушій КС (fix/verify)
│   ├── protocol/                    # KWP2000 кодек кадрів, протокол, адаптер, read-only EcuFlasher
│   ├── security/                    # Seed/Key: LegacyBls (unverified), Mock (тільки емулятор)
│   └── usb/                         # MPPS v18, KKL serial, емулятор
├── app/src/test/                    # JVM тести (емулятор, транзакція, кодек, КС)
├── binaries/, app/src/main/assets/  # Тюнінгові образи Stage 1 (НЕ заводські)
└── .github/workflows/android-ci.yml # testDebugUnitTest, assembleDebug
```

---

## ⚡ Опис калібрування `stage1_refined` (заявлено автором; тестами цього репозиторію не перевіряється)

1. **Hot Start Fix (Миттєвий запуск на гарячу)**:
   - В оновленій карті `StSys_trqStrtBas_MAP` (0x1F0762) піднято пусковий момент на 250 об/хв при температурі від 40°C до 100°C з 0.0 Нм до **108–125 Нм**. Стартер схоплює за пів оберту без тривалого крутіння.
2. **Плавність та підрив на 4–5 передачах (Mid-Range Punch)**:
   - Усунено провал у карті обмеження димності (`SMK-2500` на 0x1D6602, 2500 об/хв): подачу плавно піднято з 56.50 мг до **58.50 мг** (+15 Нм на трасі).
   - Збережено безпеку для двомасового маховика (DMF) на низьких обертах (<2200 об/хв).
3. **Eco Cruise SOI (+0.7° на трасі)**:
   - На 5–6 передачах при швидкостях 90–120 км/год (1750–2250 об/хв, 15–25 мг) кут випередження впорскування оптимізовано під заглушений EGR, що покращує спалювання палива та знижує витрату в круїзному режимі.
4. **100% збереження захистів турбіни та двигуна**:
   - Максимальний наддув: 2214 мбар (Garrett GT1646V / KKK BV39 у зеленій зоні надійності).
   - Активний захист за температурою вихлопу (EGT) та датчик CTSCD.
   - Повністю відключена регенерація DPF (0.0 мг пост-впорскування).

---

## 📱 Android застосунок

1. Встановіть APK (`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`).
2. Без авто: натисніть **«Тест Емуляція»** → **OPEN FILE…** → вбудований Stage 1 → **ECU ID** → **WRITE FLASH**.
   Емулятор пройде бекап, запис, повне зчитування та звірку SHA-256.
3. З авто (зараз тільки читання): режим «У літаку», запалювання увімкнено, двигун не запускати, споживачі вимкнено;
   підключіть адаптер через OTG і дозвольте доступ до USB → **ECU ID** → **READ ECU** (бекап зберігається
   у `Android/data/com.golf5.edc16flasher/files/`). Довге натискання на рядок статусу USB — діагностика адаптера. Кнопки WRITE/RECOVERY лишатимуться вимкненими з поясненням причин.

---

## 🔒 Контрольні суми бінарних файлів (перевірено `sha256sum`/`md5sum` по файлах у репозиторії; копії в `binaries/` та `app/src/main/assets/` ідентичні)

| Файл | Розмір | SHA-256 | КС профілю |
|---|---|---|---|
| `03G906021QJ_stage1_refined_CS_OK.bin` | 2 097 152 | `a517affa3f89bf2ba188a84c6b6810b20f61fa297de4917e99cba5f1f18e2b44` | валідна |
| `03G906021QJ_stage1_refined_dpf_egr_off.bin` | 2 097 152 | `48c8f368b5ce26cd920e3e2dcc7be20762d0ab69684504843f2e23ebb0c56fe8` (MD5 `25b082857fc0f2ba563ab8ce5b4a5144`) | **невалідна** |

`*_dpf_egr_off.bin` відрізняється від `*_CS_OK.bin` лише двома словами КС (`0x1BFFFC`, `0x1FDFFC`);
після виправлення КС він побайтово дорівнює `*_CS_OK.bin`. Застосунок пропонує тільки `*_CS_OK.bin`.
Обидва файли — **тюнінгові**, не заводські.

Зовнішні посилання (перевіряйте SHA-256 перед використанням, посилання можуть бути недійсні):
- Catbox: `https://files.catbox.moe/btghry.bin`
