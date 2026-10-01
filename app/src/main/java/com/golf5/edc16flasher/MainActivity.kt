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
import android.view.WindowManager
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
import com.golf5.edc16flasher.flashing.FlashResult
import com.golf5.edc16flasher.flashing.FlashStage
import com.golf5.edc16flasher.flashing.FlashTransaction
import com.golf5.edc16flasher.flashing.evaluateEligibility
import com.golf5.edc16flasher.protocol.EcuFlasher
import com.golf5.edc16flasher.protocol.FlashProtocolAdapter
import com.golf5.edc16flasher.protocol.Kwp2000Protocol
import com.golf5.edc16flasher.security.SecurityAccessAlgorithm
import com.golf5.edc16flasher.security.SecurityAlgorithmSelector
import com.golf5.edc16flasher.usb.UsbSerialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    /** A user-selected firmware image with its checksum already fixed and re-verified. */
    private class SelectedImage(
        val name: String,
        val bytes: ByteArray,
        val checksumValid: Boolean,
        val originalChecksumValid: Boolean,
    )

    private val profile = EcuFirmwareProfile.EDC16U34_03G906021QJ_391847

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbSerialManager: UsbSerialManager
    private lateinit var protocol: Kwp2000Protocol
    private lateinit var flasher: EcuFlasher

    /** No implicit default: the user must pick an image explicitly. */
    private var selectedImage: SelectedImage? = null
    private var voltageJob: Job? = null
    private var lastVoltage: Float? = null

    /** Updated after a successful readEcuId() call in this transport session. */
    private var currentEcuId: String = ""

    /** Set after a successful calibration read was persisted in this transport session. */
    private var sessionBackupCompleted: Boolean = false

    /** Any ECU operation is running; serializes access to the transport. */
    @Volatile
    private var isBusy = false

    /** A destructive write transaction is running (PHYSICAL_WRITE_ACTIVE / emulator write). */
    @Volatile
    private var isWriteActive = false

    private var isConnecting = false

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
                        if (isBusy) return
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
                        if (!isBusy) checkUsbDevices()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        appendLog("[USB] Адаптер відключено.")
                        if (isWriteActive) {
                            appendLog("[USB] КРИТИЧНО: адаптер відключено під час запису! НЕ вимикайте запалювання, перепідключіть адаптер і виконайте Recovery з бекапу.")
                        }
                        voltageJob?.cancel()
                        usbSerialManager.close()
                        updateUiDisconnected()
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
        if (isBusy) return
        appendLog("[USB] Оновлено сесію USB (onNewIntent).")
        checkUsbDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbSerialManager = UsbSerialManager(this)
        protocol = Kwp2000Protocol(usbSerialManager)
        flasher = EcuFlasher(protocol, security = { currentSecurity() }, profile = profile)

        val filter = IntentFilter().apply {
            addAction(UsbSerialManager.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setupButtons()
        refreshCapabilityUi()
        checkUsbDevices()
    }

    override fun onResume() {
        super.onResume()
        if (!isBusy && !usbSerialManager.isConnected && !isConnecting) {
            checkUsbDevices()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voltageJob?.cancel()
        unregisterReceiver(usbReceiver)
        usbSerialManager.close()
    }

    /** SID 0x27 algorithm for the active transport: mock only for the emulator. */
    private fun currentSecurity(): SecurityAccessAlgorithm =
        SecurityAlgorithmSelector.forTransport(usbSerialManager.isPhysical)

    private fun setupButtons() {
        binding.tvUsbStatus.setOnClickListener {
            if (isBusy) return@setOnClickListener
            appendLog("[USB] Ручний повторний пошук адаптера...")
            checkUsbDevices()
        }
        binding.tvUsbStatus.setOnLongClickListener {
            runAdapterDiagnostic()
            true
        }
        binding.tvModeBanner.setOnClickListener { showEligibilityDetails() }
        binding.tvEligibility.setOnClickListener { showEligibilityDetails() }
        binding.btnToggleEmulator.setOnClickListener {
            if (isBusy) return@setOnClickListener
            if (!usbSerialManager.isConnected) {
                appendLog("[EMULATOR] Активація локального емулятора Bosch EDC16U34...")
                if (usbSerialManager.openMockEmulator()) {
                    resetTransportSession()
                    lastVoltage = usbSerialManager.getBatteryVoltage()
                    appendLog("[EMULATOR] Емулятор активовано (SW 391847). Фізичний ЕБУ не використовується.")
                    updateUiConnected(lastVoltage)
                    startVoltageMonitoring()
                    binding.btnToggleEmulator.text = "Вимкнути Тест"
                    binding.btnToggleEmulator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B71C1C"))
                }
            } else if (!usbSerialManager.isPhysical) {
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
        binding.btnWrite.setOnClickListener { confirmAndFlash() }
        binding.btnRecovery.setOnClickListener { confirmAndRecovery() }
        binding.btnClearDtc.setOnClickListener { clearDtc() }
        binding.btnSelectFile.setOnClickListener { chooseImageSource() }
    }

    // ── Capability model ────────────────────────────────────────────────────

    private fun buildPreflight(recoveryMode: Boolean, voltage: Float? = lastVoltage): FlashPreflight {
        val image = selectedImage
        return FlashPreflight(
            connected = usbSerialManager.isConnected,
            physical = usbSerialManager.isPhysical,
            mpps = usbSerialManager.isMpps,
            mppsAuthVerified = usbSerialManager.mppsAuthVerified,
            ecuIdentification = currentEcuId,
            voltage = voltage,
            imageSize = image?.bytes?.size ?: 0,
            checksumValid = image?.checksumValid ?: false,
            securityVerified = currentSecurity().verified,
            backupCompleted = sessionBackupCompleted,
            recoveryMode = recoveryMode,
        )
    }

    private fun refusalReasons(eligibility: FlashEligibility): Set<FlashRefusalReason> =
        (eligibility as? FlashEligibility.Refused)?.reasons ?: emptySet()

    private fun reasonLabel(reason: FlashRefusalReason): String = getString(
        when (reason) {
            FlashRefusalReason.NOT_CONNECTED -> R.string.refusal_not_connected
            FlashRefusalReason.ECU_ID_MISMATCH -> R.string.refusal_ecu_id_mismatch
            FlashRefusalReason.VOLTAGE_UNAVAILABLE -> R.string.refusal_voltage_unavailable
            FlashRefusalReason.VOLTAGE_TOO_LOW -> R.string.refusal_voltage_too_low
            FlashRefusalReason.IMAGE_SIZE_INVALID ->
                if (selectedImage == null) R.string.refusal_no_image else R.string.refusal_image_size
            FlashRefusalReason.CHECKSUM_INVALID -> R.string.refusal_checksum
            FlashRefusalReason.SECURITY_ALGORITHM_UNVERIFIED -> R.string.refusal_security_unverified
            FlashRefusalReason.MPPS_AUTH_UNVERIFIED -> R.string.refusal_mpps_auth_unverified
            FlashRefusalReason.BACKUP_REQUIRED -> R.string.refusal_backup_required
        }
    )

    private fun describeReasons(reasons: Set<FlashRefusalReason>): String =
        reasons.sortedBy { it.ordinal }.joinToString("\n") { "• ${reasonLabel(it)} [${it.name}]" }

    /**
     * Single source of truth for the mode banner and for every button's enabled state.
     * Write/recovery are enabled only from [evaluateEligibility], never from USB state alone.
     */
    private fun refreshCapabilityUi() {
        val connected = usbSerialManager.isConnected
        val physical = usbSerialManager.isPhysical
        val normal = evaluateEligibility(buildPreflight(recoveryMode = false))
        val recovery = evaluateEligibility(buildPreflight(recoveryMode = true))
        val writeEligible = !isBusy && normal is FlashEligibility.Eligible
        val recoveryEligible = !isBusy && recovery is FlashEligibility.Eligible

        when {
            isWriteActive -> {
                binding.tvModeBanner.text = getString(R.string.mode_flashing)
                binding.tvModeBanner.setBackgroundColor(Color.parseColor("#B71C1C"))
            }
            !connected -> {
                binding.tvModeBanner.text = getString(R.string.mode_disconnected)
                binding.tvModeBanner.setBackgroundColor(Color.parseColor("#37474F"))
            }
            !physical -> {
                binding.tvModeBanner.text = getString(R.string.mode_emulator)
                binding.tvModeBanner.setBackgroundColor(Color.parseColor("#4527A0"))
            }
            normal is FlashEligibility.Eligible -> {
                binding.tvModeBanner.text = getString(R.string.mode_physical_write_eligible)
                binding.tvModeBanner.setBackgroundColor(Color.parseColor("#1565C0"))
            }
            else -> {
                binding.tvModeBanner.text = getString(R.string.mode_physical_read_only)
                binding.tvModeBanner.setBackgroundColor(Color.parseColor("#263238"))
            }
        }

        val normalReasons = refusalReasons(normal)
        binding.tvEligibility.text = when {
            isWriteActive -> "Йде запис. Не відключайте адаптер і не вимикайте запалювання."
            normalReasons.isEmpty() -> getString(R.string.eligibility_ok)
            else -> "WRITE заблоковано:\n" + describeReasons(normalReasons)
        }

        binding.btnWrite.isEnabled = writeEligible
        binding.btnRecovery.isEnabled = recoveryEligible
        binding.btnEcuId.isEnabled = !isBusy && connected
        binding.btnRead.isEnabled = !isBusy && connected
        binding.btnClearDtc.isEnabled = !isBusy && connected
        binding.btnSelectFile.isEnabled = !isBusy
        binding.btnToggleEmulator.isEnabled = !isBusy
    }

    private fun showEligibilityDetails() {
        val normal = refusalReasons(evaluateEligibility(buildPreflight(recoveryMode = false)))
        val recovery = refusalReasons(evaluateEligibility(buildPreflight(recoveryMode = true)))
        val msg = buildString {
            append("Транспорт: ${usbSerialManager.transportName}\n")
            append("Профіль: ${profile.id}\n")
            append("Security: ${currentSecurity().id} (verified=${currentSecurity().verified})\n\n")
            append("WRITE: ")
            append(if (normal.isEmpty()) "дозволено" else "заблоковано\n" + describeReasons(normal))
            append("\n\nRECOVERY: ")
            append(if (recovery.isEmpty()) "дозволено" else "заблоковано\n" + describeReasons(recovery))
        }
        AlertDialog.Builder(this)
            .setTitle("Стан допуску до запису")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Transport ──────────────────────────────────────────────────────────

    private fun checkUsbDevices() {
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

    /** A new transport means a new session: previous ID/backup facts no longer apply. */
    private fun resetTransportSession() {
        sessionBackupCompleted = false
        currentEcuId = ""
        lastVoltage = null
        binding.tvEcuId.text = "ECU ID: Натисніть [ECU ID] для вичитки"
    }

    private fun updateUiDisconnected() {
        voltageJob?.cancel()
        resetTransportSession()
        binding.tvUsbStatus.text = "● USB кабель не підключено"
        binding.tvUsbStatus.setTextColor(Color.parseColor("#FF5252"))
        binding.btnToggleEmulator.text = "Тест Емуляція"
        binding.btnToggleEmulator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#37474F"))
        refreshCapabilityUi()
    }

    private fun connectUsb(device: UsbDevice? = null) {
        if (usbSerialManager.isConnected || isConnecting || isBusy) return
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
                        resetTransportSession()
                        lastVoltage = v
                        appendLog("[USB] ${usbSerialManager.transportName} успішно підключено$vStr! Готово.")
                        if (usbSerialManager.isMpps && !usbSerialManager.mppsAuthVerified) {
                            appendLog("[USB] MPPS автентифікацію не верифіковано — запис заблоковано (тільки читання).")
                        }
                        updateUiConnected(v)
                    }
                    startVoltageMonitoring()
                } else {
                    withContext(Dispatchers.Main) {
                        appendLog("[USB] Не вдалося ініціалізувати протокол адаптера.")
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
                if (isBusy) continue
                val v = withContext(Dispatchers.IO) {
                    try { usbSerialManager.getBatteryVoltage() } catch (e: Throwable) { null }
                }
                if (isBusy) continue
                lastVoltage = if (v != null && v > 1.0f) v else null
                updateUiConnected(lastVoltage)
            }
        }
    }

    private fun updateUiConnected(voltage: Float?) {
        if (!usbSerialManager.isConnected) return
        val vStr = if (voltage != null) String.format(Locale.US, " | 🔋 %.1fV", voltage) else " | 🔋 —"
        binding.tvUsbStatus.text = "● ${usbSerialManager.transportName}$vStr"
        val color = if (voltage != null && voltage >= 12.2f) "#00E676" else "#FFA726"
        binding.tvUsbStatus.setTextColor(Color.parseColor(color))
        refreshCapabilityUi()
    }

    private fun ensureUsbConnected(): Boolean {
        if (isBusy) {
            appendLog("[MPPS] Зачекайте: триває інша операція з ЕБУ.")
            return false
        }
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

    /**
     * Runs one ECU operation with the transport locked: no voltage polling, no reconnects,
     * and all buttons disabled until it finishes.
     */
    private fun runExclusive(name: String, write: Boolean = false, block: suspend () -> Unit) {
        if (isBusy) {
            appendLog("[$name] Відхилено: триває інша операція.")
            return
        }
        isBusy = true
        isWriteActive = write
        voltageJob?.cancel()
        if (write) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshCapabilityUi()
        lifecycleScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                appendLog("[$name] Помилка: ${t.message}")
            } finally {
                isBusy = false
                isWriteActive = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                refreshCapabilityUi()
                if (usbSerialManager.isConnected) startVoltageMonitoring()
            }
        }
    }

    // ── Read-only operations ────────────────────────────────────────────────

    /** Adapter self-diagnostic (long-press on the USB status line). */
    private fun runAdapterDiagnostic() {
        if (!ensureUsbConnected()) return
        runExclusive("DIAG") {
            appendLog("[DIAG] Апаратна діагностика адаптера...")
            val report = withContext(Dispatchers.IO) { usbSerialManager.runDiagnostic() }
            appendLog(report)
        }
    }

    private fun readEcuId() {
        if (!ensureUsbConnected()) return
        runExclusive("ECU ID") {
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
                val matches = profile.requiredIdentifiers.all { ecuInfo.contains(it) }
                if (!matches) {
                    appendLog("[MPPS] УВАГА: ID не містить обидва ідентифікатори ${profile.requiredIdentifiers} — звичайний запис заблоковано.")
                }
            } catch (e: Exception) {
                currentEcuId = ""
                appendLog("[MPPS] Помилка зчитування ідентифікатора: ${e.message}")
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
        runExclusive("READ") {
            val fullImage = flasher.readCalibration { progress, msg -> postProgress(progress, msg) }
            if (fullImage == null) {
                appendLog("[MPPS] Бекап НЕ створено.")
                return@runExclusive
            }
            val backupFile = withContext(Dispatchers.IO) { persistImage("backup", fullImage) }
            sessionBackupCompleted = true
            val sha = sha256Hex(fullImage.copyOfRange(profile.calibrationStart, profile.calibrationStart + profile.calibrationSize))
            appendLog("[MPPS] Бекап збережено: ${backupFile.absolutePath}")
            appendLog("[MPPS] SHA-256 калібрування: $sha")
            val cs = Edc16ChecksumEngine.verify(fullImage, profile)
            if (!cs.isValid) {
                appendLog("[MPPS] УВАГА: КС зчитаного калібрування не відповідає профілю ${profile.id}. Перевірте, що це саме 03G906021QJ/391847.")
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Зчитування завершено!")
                .setMessage("Резервну копію збережено:\n${backupFile.name}\n\nSHA-256 (512 КБ):\n$sha")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun clearDtc() {
        if (!ensureUsbConnected()) return
        runExclusive("DTC") {
            appendLog("[DTC] Очищення кодів помилок (Clear DTC Service 0x14)...")
            val ok = withContext(Dispatchers.IO) { protocol.clearDiagnosticTroubleCodes() }
            appendLog(if (ok) "[DTC] Всі коди помилок успішно очищені!" else "[DTC] Помилка або немає відповіді від ЕБУ.")
        }
    }

    // ── Write ──────────────────────────────────────────────────────────────

    private fun confirmAndFlash() {
        if (!ensureUsbConnected()) return
        val image = selectedImage ?: return
        val v = usbSerialManager.getBatteryVoltage()
        lastVoltage = v
        val eligibility = evaluateEligibility(buildPreflight(recoveryMode = false, voltage = v))
        if (eligibility is FlashEligibility.Refused) {
            refreshCapabilityUi()
            showRefusal(eligibility.reasons)
            return
        }

        AlertDialog.Builder(this)
            .setTitle(if (usbSerialManager.isPhysical) "Запис прошивки (ФІЗИЧНИЙ ЕБУ)" else "Запис прошивки (ЕМУЛЯТОР)")
            .setMessage(writeSummary(image, v, recovery = false) +
                "\n\nПеред записом буде автоматично зроблено бекап, після запису — повне зчитування і порівняння SHA-256.")
            .setPositiveButton("Записати") { _, _ -> startFlashing(isRecovery = false) }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun confirmAndRecovery() {
        if (!ensureUsbConnected()) return
        val image = selectedImage ?: return
        val v = usbSerialManager.getBatteryVoltage()
        lastVoltage = v
        val eligibility = evaluateEligibility(buildPreflight(recoveryMode = true, voltage = v))
        if (eligibility is FlashEligibility.Refused) {
            refreshCapabilityUi()
            showRefusal(eligibility.reasons)
            return
        }

        val msg = "⚠️ РЕЖИМ АВАРІЙНОГО ВІДНОВЛЕННЯ\n\n" +
            "Використовуйте тільки якщо запис було перервано або ЕБУ не відповідає на ID.\n" +
            "Recovery пропускає ЛИШЕ перевірку ID. Напруга, КС, бекап, security і read-back перевіряються.\n\n" +
            writeSummary(image, v, recovery = true)

        AlertDialog.Builder(this)
            .setTitle("Аварійне відновлення (Recovery)")
            .setMessage(msg)
            .setPositiveButton("ДАЛІ") { _, _ -> confirmRecoveryFinal() }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun writeSummary(image: SelectedImage, voltage: Float?, recovery: Boolean): String {
        val vStr = if (voltage != null) String.format(Locale.US, "%.2f V", voltage) else "недоступна"
        val cs = when {
            !image.checksumValid -> "НЕВАЛІДНА"
            image.originalChecksumValid -> "валідна (без змін)"
            else -> "виправлено автоматично і перевірено"
        }
        return "ECU ID: ${currentEcuId.ifBlank { "не зчитано" }}\n" +
            "Адаптер: ${usbSerialManager.transportName}\n" +
            "Напруга: $vStr\n" +
            "Файл: ${image.name}\n" +
            "КС (${profile.id}): $cs\n" +
            "Бекап у цій сесії: ${if (sessionBackupCompleted) "так" else "ні"}\n" +
            "Режим: ${if (recovery) "RECOVERY" else "NORMAL"}"
    }

    /** Second confirmation for recovery: user must type the ECU part number. */
    private fun confirmRecoveryFinal() {
        val input = android.widget.EditText(this).apply {
            hint = "Введіть: 03G906021QJ"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("⚠️ ОСТАТОЧНЕ ПОПЕРЕДЖЕННЯ")
            .setMessage(
                "Recovery mode НЕ перевіряє ECU ID!\n\n" +
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
        val image = selectedImage ?: return
        val tag = if (isRecovery) "RECOVERY" else "WRITE"
        runExclusive(tag, write = true) {
            var backupPath: String? = null
            val result = withContext(Dispatchers.IO) {
                val security = currentSecurity()
                val preflight = buildPreflight(recoveryMode = isRecovery, voltage = usbSerialManager.getBatteryVoltage())
                FlashTransaction(
                    preflight = preflight,
                    imageBytes = image.bytes,
                    protocol = FlashProtocolAdapter(protocol, security, recoveryMode = isRecovery),
                    onBackup = { calibration ->
                        val container = ByteArray(profile.fullImageSize) { 0xFF.toByte() }
                        System.arraycopy(calibration, 0, container, profile.calibrationStart, calibration.size)
                        backupPath = persistImage("prewrite_backup", container).absolutePath
                    },
                    voltageProbe = { usbSerialManager.getBatteryVoltage() },
                    onProgress = { stage, pct, msg -> postProgress(pct, "[$tag/${stage.name}] $msg") },
                    profile = profile,
                ).execute()
            }
            backupPath?.let {
                sessionBackupCompleted = true
                appendLog("[$tag] Бекап перед записом: $it")
            }
            handleFlashResult(tag, result, backupPath)
        }
    }

    private fun handleFlashResult(tag: String, result: FlashResult, backupPath: String?) {
        when (result) {
            is FlashResult.Success -> {
                appendLog("[$tag] УСПІХ: read-back SHA-256 збігається: ${result.writtenSha256}")
                val resetNote = if (result.resetAcknowledged) "" else
                    "\n\n⚠️ ЕБУ не підтвердив Reset — вимкніть запалювання на 10 с вручну."
                AlertDialog.Builder(this)
                    .setTitle("Запис верифіковано")
                    .setMessage("Записане калібрування повністю зчитано і збігається (SHA-256).\n\n" +
                        "Записано: ${result.writtenSha256}\nБекап: ${result.backupSha256}" + resetNote +
                        "\n\nВимкніть запалювання на 10 с, потім запустіть двигун.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            is FlashResult.Refused -> {
                appendLog("[$tag] Відмовлено до запису:\n" + describeReasons(result.reasons))
                showRefusal(result.reasons)
            }
            is FlashResult.Failed -> {
                val destructiveStarted = result.stage.ordinal >= FlashStage.REQUEST_DOWNLOAD.ordinal
                appendLog("[$tag] ПОМИЛКА на етапі ${result.stage}: ${result.message}")
                val advice = if (destructiveStarted) {
                    "\n\n🔴 Запис міг бути частковим. НЕ вимикайте запалювання. " +
                        "Підключіть зарядний пристрій і повторіть запис або Recovery." +
                        (backupPath?.let { "\nБекап: $it" } ?: "")
                } else {
                    "\n\nДеструктивних дій не виконувалось — ЕБУ не змінено."
                }
                AlertDialog.Builder(this)
                    .setTitle("Запис НЕ виконано (${result.stage})")
                    .setMessage(result.message + advice)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showRefusal(reasons: Set<FlashRefusalReason>) {
        AlertDialog.Builder(this)
            .setTitle("Запис заблоковано")
            .setMessage(describeReasons(reasons))
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Image selection ─────────────────────────────────────────────────────

    private fun chooseImageSource() {
        if (isBusy) return
        // The bundled *_dpf_egr_off.bin differs from *_CS_OK.bin only in the two checksum
        // patch words (stale checksum), so only the checksum-valid copy is offered.
        val options = arrayOf(
            "Вбудований: 03G906021QJ_stage1_refined_CS_OK.bin (ТЮНІНГ Stage 1 / DPF+EGR off, НЕ заводський)",
            "Файл з пам'яті телефону…",
        )
        AlertDialog.Builder(this)
            .setTitle("Джерело прошивки")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> loadBinaryFromAsset("03G906021QJ_stage1_refined_CS_OK.bin")
                    else -> openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                }
            }
            .show()
    }

    private fun loadBinaryFromAsset(assetName: String) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { assets.open(assetName).use { it.readBytes() } }
                acceptImage("$assetName (вбудований, тюнінг)", bytes)
            } catch (e: Exception) {
                appendLog("[FILE] Помилка читання вбудованого файлу: ${e.message}")
            }
        }
    }

    private fun loadBinaryFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) {
                    appendLog("[FILE] Не вдалося відкрити файл.")
                    return@launch
                }
                acceptImage(uri.lastPathSegment ?: "custom.bin", bytes)
            } catch (e: Exception) {
                appendLog("[FILE] Помилка відкриття файлу: ${e.message}")
            }
        }
    }

    /** Validates size, fixes and independently re-verifies the checksum, then selects the image. */
    private fun acceptImage(name: String, raw: ByteArray) {
        if (raw.size != profile.fullImageSize) {
            selectedImage = null
            binding.tvFileStatus.text = "File: $name — НЕВІРНИЙ розмір ${raw.size} (потрібно ${profile.fullImageSize})"
            binding.tvFileStatus.setTextColor(Color.parseColor("#FF5252"))
            appendLog("[FILE] Відхилено: розмір ${raw.size} байт, потрібно рівно ${profile.fullImageSize}.")
            refreshCapabilityUi()
            return
        }
        val originalValid = Edc16ChecksumEngine.verify(raw, profile).isValid
        val fixed = try { Edc16ChecksumEngine.fix(raw, profile) } catch (e: Exception) { null }
        val fixedValid = fixed != null && Edc16ChecksumEngine.verify(fixed, profile).isValid
        selectedImage = SelectedImage(name, fixed ?: raw, fixedValid, originalValid)

        val csText = when {
            !fixedValid -> "КС НЕВАЛІДНА"
            originalValid -> "КС OK"
            else -> "КС виправлено"
        }
        binding.tvFileStatus.text = "File: $name ($csText)"
        binding.tvFileStatus.setTextColor(Color.parseColor(if (fixedValid) "#69F0AE" else "#FF5252"))
        appendLog("[FILE] Вибрано: $name, $csText, SHA-256 калібрування: " +
            sha256Hex((fixed ?: raw).copyOfRange(profile.calibrationStart, profile.calibrationStart + profile.calibrationSize)))
        refreshCapabilityUi()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun persistImage(kind: String, image: ByteArray): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getExternalFilesDir(null), "03G906021QJ_${kind}_$timeStamp.bin")
        FileOutputStream(file).use { out ->
            out.write(image)
            out.flush()
            out.fd.sync()
        }
        if (file.length() != image.size.toLong()) {
            throw IllegalStateException("backup file size mismatch: ${file.length()} != ${image.size}")
        }
        return file
    }

    private fun postProgress(progress: Int, msg: String) {
        runOnUiThread {
            binding.progressBar.progress = progress
            binding.tvProgress.text = "$progress% - $msg"
            appendLog(msg)
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun appendLog(msg: String) {
        binding.tvLog.append("$msg\n")
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
