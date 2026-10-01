package com.golf5.edc16flasher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.golf5.edc16flasher.databinding.ActivityMainBinding
import com.golf5.edc16flasher.firmware.EcuFirmwareProfile
import com.golf5.edc16flasher.firmware.Edc16ChecksumEngine
import com.golf5.edc16flasher.flashing.FlashEligibility
import com.golf5.edc16flasher.flashing.FlashPreflight
import com.golf5.edc16flasher.flashing.FlashRefusalReason
import com.golf5.edc16flasher.flashing.evaluateEligibility
import com.golf5.edc16flasher.protocol.EcuFlasher
import com.golf5.edc16flasher.protocol.Kwp2000Protocol
import com.golf5.edc16flasher.security.LegacyBlsSecurityAlgorithm
import com.golf5.edc16flasher.security.MockSecurityAlgorithm
import com.golf5.edc16flasher.security.Sa2BlsSecurityAlgorithm
import com.golf5.edc16flasher.security.SecurityAccessAlgorithm
import com.golf5.edc16flasher.usb.UsbSerialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbSerialManager: UsbSerialManager
    private lateinit var protocol: Kwp2000Protocol

    private val profile = EcuFirmwareProfile.EDC16U34_03G906021QJ_391847

    /** Checksum-verified (or auto-corrected) image ready to flash; null until a valid file is prepared. */
    private var firmwareImage: ByteArray? = null
    private var selectedFileName: String = "03G906021QJ_stage1_refined_CS_OK.bin (Вбудована)"
    private var checksumStatus: String = "не перевірено"
    private var voltageJob: Job? = null

    /** Last voltage reading; null means unavailable. Consumed by eligibility evaluation. */
    private var lastVoltage: Float? = null

    /** Updated after a successful readEcuId() call — consumed by refreshUi(). */
    private var currentEcuId: String = ""

    /** Set to true after a successful startReading() backup in the current session. */
    private var sessionBackupCompleted: Boolean = false

    /**
     * True while any bus transaction runs. Blocks voltage polling, adapter reconnects
     * and every control except passive log scrolling.
     */
    @Volatile
    private var isTransactionActive = false

    /** The emulator only accepts its mock key; physical ECUs get the factory SA2 algorithm. */
    private val securityAlgorithm: SecurityAccessAlgorithm
        get() = if (usbSerialManager.isPhysical) Sa2BlsSecurityAlgorithm else MockSecurityAlgorithm

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadBinaryFromUri(it) }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    UsbSerialManager.ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            appendLog("[USB] Дозвіл USB надано користувачем.")
                            connectUsb()
                        } else {
                            appendLog("[USB] Доступ до USB відхилено.")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        appendLog("[USB] Виявлено підключення діагностичного адаптера.")
                        checkUsbDevices()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (isTransactionActive) {
                            appendLog("[USB] !!! АДАПТЕР ВІДКЛЮЧЕНО ПІД ЧАС ОПЕРАЦІЇ! Не вимикайте запалювання, перевірте журнал.")
                        } else {
                            appendLog("[USB] Адаптер відключено.")
                        }
                        voltageJob?.cancel()
                        usbSerialManager.close()
                        updateUiDisconnected()
                    }
                    "com.golf5.edc16flasher.DIAGNOSTIC" -> {
                        if (isTransactionActive) {
                            appendLog("[DIAG] Відхилено: триває операція з ЕБУ.")
                            return
                        }
                        appendLog("[DIAG] Запуск повної апаратної діагностики MPPS...")
                        lifecycleScope.launch(Dispatchers.IO) {
                            val report = usbSerialManager.runDiagnostic()
                            withContext(Dispatchers.Main) {
                                appendLog(report)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                appendLog("[USB] Помилка обробки події USB: ${t.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        appendLog("[USB] Оновлено сесію USB (onNewIntent).")
        checkUsbDevices()
    }

    private var isConnecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbSerialManager = UsbSerialManager(this)
        protocol = Kwp2000Protocol(usbSerialManager)

        val filter = IntentFilter().apply {
            addAction(UsbSerialManager.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction("com.golf5.edc16flasher.DIAGNOSTIC")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setupButtons()
        refreshUi()
        loadBuiltInFirmware()
        checkUsbDevices()
    }

    override fun onResume() {
        super.onResume()
        if (!usbSerialManager.isConnected && !isConnecting) {
            checkUsbDevices()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voltageJob?.cancel()
        unregisterReceiver(usbReceiver)
        usbSerialManager.close()
    }

    private fun setupButtons() {
        binding.tvUsbStatus.setOnClickListener {
            appendLog("[USB] Ручний повторний пошук адаптера...")
            checkUsbDevices()
        }
        binding.tvModeBanner.setOnClickListener { showEligibilityDetails() }
        binding.tvEligibility.setOnClickListener { showEligibilityDetails() }
        binding.btnToggleEmulator.setOnClickListener {
            if (!usbSerialManager.isConnected) {
                appendLog("[EMULATOR] Активація локального емулятора Bosch EDC16U34...")
                if (usbSerialManager.openMockEmulator()) {
                    val v = usbSerialManager.getBatteryVoltage()
                    appendLog("[EMULATOR] Емулятор активовано (🔋 13.8V, SW 391847)! Готово до вичитки та тестів.")
                    updateUiConnected(v)
                    startVoltageMonitoring()
                    binding.btnToggleEmulator.text = "Вимкнути Тест"
                    binding.btnToggleEmulator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B71C1C"))
                }
            } else if (usbSerialManager.transportName.contains("Емуляція")) {
                appendLog("[EMULATOR] Вимкнення емулятора...")
                voltageJob?.cancel()
                usbSerialManager.close()
                updateUiDisconnected()
            } else {
                appendLog("[USB] Фізичний адаптер підключено! Відключіть кабель для тестування в емуляторі.")
            }
        }
        binding.btnEcuId.setOnClickListener { readEcuId() }
        binding.btnRead.setOnClickListener { confirmAndRead() }
        binding.btnWrite.setOnClickListener { confirmAndFlash(isRecovery = false) }
        binding.btnRecovery.setOnClickListener { confirmAndFlash(isRecovery = true) }
        binding.btnClearDtc.setOnClickListener { clearDtc() }
        binding.btnSelectFile.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }
    }

    private fun checkUsbDevices() {
        if (isTransactionActive) {
            appendLog("[USB] Пошук адаптера відкладено: триває операція з ЕБУ.")
            return
        }
        try {
            val supported = usbSerialManager.findSupportedDevices()
            val rawDevices = usbSerialManager.getRawDevices()

            if (supported.isNotEmpty()) {
                val dev = supported[0]
                val vidHex = Integer.toHexString(dev.vendorId).uppercase()
                val pidHex = Integer.toHexString(dev.productId).uppercase()
                val isMpps = usbSerialManager.isMppsDevice(dev)
                val name = if (isMpps) "MPPS v18 (AMT Flash)" else (try { dev.productName ?: "K-Line" } catch (e: Throwable) { "K-Line" })

                if (usbSerialManager.hasPermission(dev)) {
                    appendLog("[USB] Знайдено $name (VID:0x$vidHex PID:0x$pidHex), дозвіл є.")
                    connectUsb(dev)
                } else {
                    binding.tvUsbStatus.text = "● Запит дозволу USB ($name)"
                    binding.tvUsbStatus.setTextColor(Color.parseColor("#FFA726"))
                    appendLog("[USB] Знайдено $name (VID:0x$vidHex PID:0x$pidHex). Запит системного дозволу...")
                    usbSerialManager.requestPermission(dev) {
                        appendLog("[USB] Очікування підтвердження на екрані...")
                    }
                }
            } else if (rawDevices.isNotEmpty()) {
                val dev = rawDevices.first()
                val vidHex = Integer.toHexString(dev.vendorId).uppercase()
                val pidHex = Integer.toHexString(dev.productId).uppercase()
                binding.tvUsbStatus.text = "● USB підключено (0x$vidHex:0x$pidHex)"
                binding.tvUsbStatus.setTextColor(Color.parseColor("#FFA726"))
                appendLog("[USB] Виявлено непідтримуваний USB пристрій: VID:0x$vidHex PID:0x$pidHex")
            } else if (!usbSerialManager.isConnected) {
                updateUiDisconnected()
            }
        } catch (t: Throwable) {
            appendLog("[USB] Помилка сканування USB: ${t.message}")
        }
    }

    private fun updateUiDisconnected() {
        voltageJob?.cancel()
        sessionBackupCompleted = false
        currentEcuId = ""
        lastVoltage = null
        binding.tvEcuId.text = "ECU ID: —"
        binding.tvUsbStatus.text = "● USB кабель не підключено"
        binding.tvUsbStatus.setTextColor(Color.parseColor("#FF5252"))
        binding.btnToggleEmulator.text = "Тест Емуляція"
        binding.btnToggleEmulator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#37474F"))
        refreshUi()
    }

    // ── Capability state ────────────────────────────────────────────────────

    private fun buildPreflight(voltage: Float?, recoveryMode: Boolean) = FlashPreflight(
        connected = usbSerialManager.isConnected,
        physical = usbSerialManager.isPhysical,
        mpps = usbSerialManager.isMpps,
        mppsAuthVerified = usbSerialManager.mppsAuthVerified,
        ecuIdentification = currentEcuId,
        voltage = voltage,
        imageSize = firmwareImage?.size ?: 0,
        checksumValid = firmwareImage != null,
        securityVerified = securityAlgorithm.verified,
        backupCompleted = sessionBackupCompleted,
        recoveryMode = recoveryMode,
    )

    /**
     * Single source of truth for the mode banner and every control's enabled state.
     * Write/Recovery are enabled only by calculated eligibility, never by USB state alone.
     */
    private fun refreshUi() {
        if (isTransactionActive) {
            setAllControlsEnabled(false)
            return
        }

        val normal = evaluateEligibility(buildPreflight(lastVoltage, recoveryMode = false))
        val recovery = evaluateEligibility(buildPreflight(lastVoltage, recoveryMode = true))
        val writeEligible = normal is FlashEligibility.Eligible
        val recoveryEligible = recovery is FlashEligibility.Eligible

        setAllControlsEnabled(true)
        binding.btnWrite.isEnabled = writeEligible
        binding.btnRecovery.isEnabled = recoveryEligible

        val (bannerRes, bannerColor) = when {
            !usbSerialManager.isConnected -> R.string.mode_disconnected to "#424242"
            !usbSerialManager.isPhysical -> R.string.mode_emulator to "#37474F"
            writeEligible || recoveryEligible -> R.string.mode_physical_write_eligible to "#1565C0"
            else -> R.string.mode_physical_read_only to "#263238"
        }
        binding.tvModeBanner.text = getString(bannerRes)
        binding.tvModeBanner.setBackgroundColor(Color.parseColor(bannerColor))
        binding.tvEligibility.text = "Запис: ${describe(normal)}\nRecovery: ${describe(recovery)}"
    }

    private fun describe(eligibility: FlashEligibility): String = when (eligibility) {
        FlashEligibility.Eligible -> "дозволено"
        is FlashEligibility.Refused -> "заблоковано — " + eligibility.reasons.joinToString("; ") { reasonLabel(it) }
    }

    private fun reasonLabel(reason: FlashRefusalReason): String = getString(
        when (reason) {
            FlashRefusalReason.NOT_CONNECTED -> R.string.refusal_not_connected
            FlashRefusalReason.ECU_ID_MISMATCH -> R.string.refusal_ecu_id_mismatch
            FlashRefusalReason.VOLTAGE_UNAVAILABLE -> R.string.refusal_voltage_unavailable
            FlashRefusalReason.VOLTAGE_TOO_LOW -> R.string.refusal_voltage_too_low
            FlashRefusalReason.IMAGE_SIZE_INVALID -> R.string.refusal_image_size
            FlashRefusalReason.CHECKSUM_INVALID -> R.string.refusal_checksum
            FlashRefusalReason.SECURITY_ALGORITHM_UNVERIFIED -> R.string.refusal_security_unverified
            FlashRefusalReason.MPPS_AUTH_UNVERIFIED -> R.string.refusal_mpps_auth_unverified
            FlashRefusalReason.BACKUP_REQUIRED -> R.string.refusal_backup_required
        }
    )

    private fun showEligibilityDetails() {
        val msg = listOf(false, true).joinToString("\n\n") { recovery ->
            val title = if (recovery) "RECOVERY" else "NORMAL"
            when (val e = evaluateEligibility(buildPreflight(lastVoltage, recovery))) {
                FlashEligibility.Eligible -> "$title: дозволено"
                is FlashEligibility.Refused -> "$title: заблоковано\n" + e.reasons.joinToString("\n") { "• " + reasonLabel(it) }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(binding.tvModeBanner.text)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Transaction lock ────────────────────────────────────────────────────

    private fun beginTransaction(flashing: Boolean) {
        isTransactionActive = true
        voltageJob?.cancel()
        setAllControlsEnabled(false)
        if (flashing) {
            binding.tvModeBanner.text = getString(R.string.mode_flashing)
            binding.tvModeBanner.setBackgroundColor(Color.parseColor("#B71C1C"))
        }
    }

    private fun endTransaction() {
        isTransactionActive = false
        if (usbSerialManager.isConnected) startVoltageMonitoring()
        refreshUi()
    }

    private fun setAllControlsEnabled(enabled: Boolean) {
        binding.btnEcuId.isEnabled = enabled
        binding.btnRead.isEnabled = enabled
        binding.btnWrite.isEnabled = enabled
        binding.btnRecovery.isEnabled = enabled
        binding.btnClearDtc.isEnabled = enabled
        binding.btnSelectFile.isEnabled = enabled
        binding.btnToggleEmulator.isEnabled = enabled
        binding.tvUsbStatus.isEnabled = enabled
        binding.tvModeBanner.isEnabled = enabled
        binding.tvEligibility.isEnabled = enabled
    }

    private fun connectUsb(device: UsbDevice? = null) {
        if (usbSerialManager.isConnected || isConnecting || isTransactionActive) return
        isConnecting = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val target = device ?: usbSerialManager.findSupportedDevices().firstOrNull() ?: return@launch
                withContext(Dispatchers.Main) {
                    appendLog("[USB] Ініціалізація ${target.deviceName}...")
                }
                if (usbSerialManager.open(target, 10400)) {
                    val v = usbSerialManager.getBatteryVoltage()
                    val vStr = if (v != null) String.format(Locale.US, " (🔋 %.1fV)", v) else ""
                    withContext(Dispatchers.Main) {
                        appendLog("[USB] ${usbSerialManager.transportName} успішно підключено$vStr! Готово.")
                        updateUiConnected(v)
                    }
                    startVoltageMonitoring()
                } else {
                    val reason = usbSerialManager.lastConnectError
                    withContext(Dispatchers.Main) {
                        if (reason != null) {
                            appendLog("[USB] Не вдалося ініціалізувати протокол адаптера: $reason")
                        } else {
                            appendLog("[USB] Не вдалося ініціалізувати протокол адаптера.")
                        }
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendLog("[USB] Помилка відкриття порту: ${t.message}")
                }
            } finally {
                isConnecting = false
            }
        }
    }

    private fun startVoltageMonitoring() {
        voltageJob?.cancel()
        voltageJob = lifecycleScope.launch {
            while (isActive && usbSerialManager.isConnected) {
                delay(2000)
                if (!isTransactionActive) {
                    val v = withContext(Dispatchers.IO) {
                        try { usbSerialManager.getBatteryVoltage() } catch (e: Throwable) { null }
                    }
                    if (!isTransactionActive) updateUiConnected(v?.takeIf { it > 1.0f })
                }
            }
        }
    }

    private fun updateUiConnected(voltage: Float?) {
        if (!usbSerialManager.isConnected) return
        lastVoltage = voltage
        val vStr = if (voltage != null) String.format(Locale.US, " | 🔋 %.1fV", voltage) else " | 🔋 —"
        binding.tvUsbStatus.text = "● ${usbSerialManager.transportName}$vStr"
        val color = if (voltage != null && voltage >= 12.2f) "#00E676" else "#FFA726"
        binding.tvUsbStatus.setTextColor(Color.parseColor(color))
        refreshUi()
    }

    private fun ensureUsbConnected(): Boolean {
        if (isTransactionActive) return false
        if (!usbSerialManager.isConnected) {
            checkUsbDevices()
        }
        if (!usbSerialManager.isConnected) {
            val raw = usbSerialManager.getRawDevices()
            if (raw.isEmpty()) {
                appendLog("[MPPS] Помилка: Телефон не бачить жодного USB пристрою.")
                appendLog("-> ПЕРЕВІРТЕ: чи підключено кабель до OBD2 роз'єму авто та увімкнено запалювання? (Кабелі KKL/MPPS живляться від 12В машини!)")
                appendLog("-> ПЕРЕВІРТЕ: чи підтримує ваш OTG перехідник передачу даних.")
            } else {
                appendLog("[MPPS] USB виявлено, але адаптер не підключено або очікує дозволу.")
            }
            return false
        }
        return true
    }

    private fun readEcuId() {
        if (!ensureUsbConnected()) return

        beginTransaction(flashing = false)
        lifecycleScope.launch {
            appendLog("[MPPS] Зчитування ідентифікатора ЕБУ (EDC16)...")
            try {
                val ecuInfo = withContext(Dispatchers.IO) {
                    if (usbSerialManager.isMpps) {
                        usbSerialManager.queryEcuIdentificationMpps()
                    } else {
                        protocol.readEcuIdentification()
                    }
                }
                binding.tvEcuId.text = "ECU ID: $ecuInfo"
                currentEcuId = ecuInfo
                appendLog("[MPPS] Успішно ідентифіковано: $ecuInfo")
            } catch (e: Exception) {
                appendLog("[MPPS] Помилка зчитування ідентифікатора: ${e.message}")
            } finally {
                endTransaction()
            }
        }
    }

    private fun confirmAndRead() {
        if (!ensureUsbConnected()) return

        AlertDialog.Builder(this)
            .setTitle("Зчитування калібрувань (Read ECU)")
            .setMessage("Зчитати калібрувальний сектор (512 КБ) у пам'ять телефону для бекапу?")
            .setPositiveButton("Зчитати") { _, _ -> startReading() }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun startReading() {
        if (!ensureUsbConnected()) return
        beginTransaction(flashing = false)
        val flasher = EcuFlasher(protocol, profile, security = securityAlgorithm)
        lifecycleScope.launch {
            try {
                val fullImage = flasher.readCalibration { progress, msg ->
                    runOnUiThread {
                        binding.progressBar.progress = progress
                        binding.tvProgress.text = "$progress% - $msg"
                        appendLog(msg)
                    }
                }

                if (fullImage != null) {
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val backupFile = File(getExternalFilesDir(null), "03G906021QJ_backup_$timeStamp.bin")
                    withContext(Dispatchers.IO) { FileOutputStream(backupFile).use { it.write(fullImage) } }
                    sessionBackupCompleted = true
                    appendLog("[MPPS] Бекап успішно збережено: ${backupFile.absolutePath}")

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Зчитування завершено!")
                        .setMessage("Резервну копію збережено: " + backupFile.name + " (2 097 152 байти)")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                appendLog("[MPPS] Помилка збереження бекапу: ${e.message}")
            } finally {
                endTransaction()
            }
        }
    }

    /**
     * Re-evaluates eligibility with a fresh voltage reading, then shows every fact the
     * write depends on. Refuses with the exact reasons instead of offering a confirm button.
     */
    private fun confirmAndFlash(isRecovery: Boolean) {
        if (!ensureUsbConnected()) return

        lifecycleScope.launch {
            val v = withContext(Dispatchers.IO) {
                try { usbSerialManager.getBatteryVoltage() } catch (e: Throwable) { null }
            }
            if (isTransactionActive) return@launch
            updateUiConnected(v?.takeIf { it > 1.0f })

            val eligibility = evaluateEligibility(buildPreflight(lastVoltage, isRecovery))
            if (eligibility is FlashEligibility.Refused) {
                appendLog("[MPPS] Запис відхилено: " + eligibility.reasons.joinToString { it.name })
                showEligibilityDetails()
                return@launch
            }

            val vStr = lastVoltage?.let { String.format(Locale.US, "%.1f V", it) } ?: "недоступна"
            val summary = "ECU ID: ${currentEcuId.ifBlank { "не зчитано" }}\n" +
                "Адаптер: ${usbSerialManager.transportName}\n" +
                "Напруга: $vStr\n" +
                "Файл: $selectedFileName\n" +
                "Контрольна сума: $checksumStatus\n" +
                "Бекап: ${if (sessionBackupCompleted) "збережено в цій сесії" else "НЕМАЄ"}\n" +
                "Режим: ${if (isRecovery) "RECOVERY" else "NORMAL"}\n\n"

            val msg = if (isRecovery) {
                summary +
                    "⚠️ Використовуйте тільки якщо запис було перервано або ЕБУ не реагує на стандартний запит.\n" +
                    "Recovery пропустить перевірку ID та примусово увійде в бутлоадер."
            } else {
                summary +
                    "1. Акумулятор заряджений (12.4В+), підключено зарядний пристрій.\n" +
                    "2. Увімкніть 'Режим польоту' на телефоні.\n" +
                    "3. Вимкніть споживачі в авто."
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (isRecovery) "Аварійне відновлення (Recovery)" else "Запис прошивки (Write Flash)")
                .setMessage(msg)
                .setPositiveButton(if (isRecovery) "ДАЛІ" else "Записати") { _, _ ->
                    if (isRecovery) confirmRecoveryFinal() else startFlashing(isRecovery = false)
                }
                .setNegativeButton("Скасувати", null)
                .show()
        }
    }

    /**
     * CRITICAL SAFETY (C2): Second confirmation dialog for Recovery Mode.
     * User must manually type ECU ID to proceed.
     */
    private fun confirmRecoveryFinal() {
        val input = android.widget.EditText(this).apply {
            hint = "Введіть: 03G906021QJ"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("⚠️ ОСТАТОЧНЕ ПОПЕРЕДЖЕННЯ")
            .setMessage(
                "Recovery mode ВИМКНУВ перевірку ECU ID!\n\n" +
                "Ви на 100% впевнені, що це:\n" +
                "03G906021QJ (SW 391847)?\n\n" +
                "Неправильний файл НАЗАВЖДИ зламає блок!\n\n" +
                "Введіть номер ЕБУ для підтвердження:"
            )
            .setView(input)
            .setPositiveButton("ЗАПИСАТИ") { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.equals("03G906021QJ", ignoreCase = true)) {
                    startFlashing(isRecovery = true)
                } else {
                    appendLog("[RECOVERY] Підтвердження не пройдено: введено '$typed'")
                    Toast.makeText(this, "Підтвердження не пройдено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("СКАСУВАТИ", null)
            .show()
    }

    private fun startFlashing(isRecovery: Boolean) {
        if (isTransactionActive) return
        // State may have changed while a dialog was open (disconnect, voltage drop): check again.
        val eligibility = evaluateEligibility(buildPreflight(lastVoltage, isRecovery))
        val image = firmwareImage
        if (eligibility is FlashEligibility.Refused || image == null) {
            appendLog("[MPPS] Запис скасовано: умови змінилися після підтвердження.")
            refreshUi()
            showEligibilityDetails()
            return
        }

        beginTransaction(flashing = true)
        val flasher = EcuFlasher(protocol, profile, security = securityAlgorithm)

        lifecycleScope.launch {
            try {
                val onProgress: (Int, String) -> Unit = { progress, msg ->
                    runOnUiThread {
                        binding.progressBar.progress = progress
                        binding.tvProgress.text = "$progress% - $msg"
                        appendLog(msg)
                    }
                }
                val success = if (isRecovery) {
                    flasher.recoveryFlash(image, onProgress)
                } else {
                    flasher.flashFirmware(image, onProgress)
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (success) "Успіх!" else "ЗАПИС НЕ ЗАВЕРШЕНО")
                    .setMessage(
                        if (success) "Прошивку успішно записано та верифіковано!\nВимкніть запалювання на 10с, потім запустіть двигун."
                        else "Запис або верифікація не пройшли. НЕ вимикайте запалювання, збережіть журнал і перевірте причину."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            } finally {
                endTransaction()
            }
        }
    }

    private fun clearDtc() {
        if (!usbSerialManager.isConnected) {
            appendLog("[DTC] Помилка: USB адаптер не підключено!")
            return
        }
        if (isTransactionActive) return

        beginTransaction(flashing = false)
        lifecycleScope.launch {
            try {
                appendLog("[DTC] Очищення кодів помилок (Clear DTC Service 0x14)...")
                val ok = withContext(Dispatchers.IO) { protocol.clearDiagnosticTroubleCodes() }
                if (ok) {
                    appendLog("[DTC] Всі коди помилок успішно очищені!")
                } else {
                    appendLog("[DTC] Помилка або немає відповіді від ЕБУ.")
                }
            } catch (e: Exception) {
                appendLog("[DTC] Помилка: ${e.message}")
            } finally {
                endTransaction()
            }
        }
    }

    // ── Firmware image ──────────────────────────────────────────────────────

    /** Returns the image to flash (verified or auto-corrected) and a human-readable checksum status. */
    private fun prepareFirmware(raw: ByteArray): Pair<ByteArray?, String> {
        if (raw.size != profile.fullImageSize) {
            return null to "НЕВАЛІДНА (розмір ${raw.size} байт)"
        }
        return try {
            if (Edc16ChecksumEngine.verify(raw, profile).isValid) {
                raw to "OK (0xD01FE500)"
            } else {
                Edc16ChecksumEngine.fix(raw, profile) to "ВИПРАВЛЕНО автоматично (0xD01FE500)"
            }
        } catch (e: Exception) {
            null to "НЕВАЛІДНА (${e.message})"
        }
    }

    private fun applyFirmware(name: String, raw: ByteArray) {
        val (image, status) = prepareFirmware(raw)
        firmwareImage = image
        selectedFileName = name
        checksumStatus = status
        binding.tvFileStatus.text = "File: $name | CS: $status"
        appendLog("[MPPS] Файл: $name — контрольна сума: $status")
        refreshUi()
    }

    private fun loadBuiltInFirmware() {
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    assets.open("03G906021QJ_stage1_refined_CS_OK.bin").readBytes()
                }
                if (firmwareImage == null) applyFirmware(selectedFileName, raw)
            } catch (e: Exception) {
                appendLog("[MPPS] Вбудований файл недоступний: ${e.message}")
            }
        }
    }

    private fun loadBinaryFromUri(uri: Uri) {
        if (isTransactionActive) return
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) {
                    appendLog("[MPPS] Помилка: не вдалося прочитати файл.")
                } else if (bytes.size != profile.fullImageSize) {
                    appendLog("[MPPS] Помилка: файл повинен бути рівно 2 097 152 байти (отримано ${bytes.size})!")
                } else {
                    applyFirmware(uri.lastPathSegment ?: "custom.bin", bytes)
                }
            } catch (e: Exception) {
                appendLog("[MPPS] Помилка відкриття файлу: ${e.message}")
            }
        }
    }

    private fun appendLog(msg: String) {
        binding.tvLog.append("$msg\n")
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
