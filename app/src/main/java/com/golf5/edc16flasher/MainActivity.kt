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
import com.golf5.edc16flasher.flashing.FlashEligibility
import com.golf5.edc16flasher.flashing.FlashPreflight
import com.golf5.edc16flasher.flashing.FlashRefusalReason
import com.golf5.edc16flasher.flashing.evaluateEligibility
import com.golf5.edc16flasher.protocol.EcuFlasher
import com.golf5.edc16flasher.protocol.Kwp2000Protocol
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
    private lateinit var flasher: EcuFlasher

    private var customBinBytes: ByteArray? = null
    private var selectedFileName: String = "03G906021QJ_stage1_refined_CS_OK.bin (Вбудована)"
    private var voltageJob: Job? = null

    /** Updated after a successful readEcuId() call — consumed by updateModeBanner(). */
    private var currentEcuId: String = ""

    /** Set to true after a successful startReading() backup in the current session. */
    private var sessionBackupCompleted: Boolean = false

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
                        appendLog("[USB] Адаптер відключено.")
                        voltageJob?.cancel()
                        usbSerialManager.close()
                        updateUiDisconnected()
                    }
                    "com.golf5.edc16flasher.DIAGNOSTIC" -> {
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
        flasher = EcuFlasher(protocol)

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
        binding.btnWrite.setOnClickListener { confirmAndFlash() }
        binding.btnRecovery.setOnClickListener { confirmAndRecovery() }
        binding.btnClearDtc.setOnClickListener { clearDtc() }
        binding.btnSelectFile.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }
    }

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
            } else {
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
        binding.tvUsbStatus.text = "● USB кабель не підключено"
        binding.tvUsbStatus.setTextColor(Color.parseColor("#FF5252"))
        binding.btnToggleEmulator.text = "Тест Емуляція"
        binding.btnToggleEmulator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#37474F"))
        updateModeBanner(null)
    }

    /**
     * Updates the mode banner and write/recovery button enabled state based on
     * the current transport and preflight eligibility evaluation.
     */
    private fun updateModeBanner(voltage: Float?) {
        if (!usbSerialManager.isConnected) {
            binding.tvModeBanner.text = getString(R.string.mode_emulator)
            binding.tvModeBanner.setBackgroundColor(Color.parseColor("#37474F"))
            binding.btnWrite.isEnabled = false
            binding.btnRecovery.isEnabled = false
            return
        }

        val physical = usbSerialManager.isPhysical
        val mpps = usbSerialManager.isMpps

        if (!physical) {
            // Emulator mode — always allow write via emulator
            binding.tvModeBanner.text = getString(R.string.mode_emulator)
            binding.tvModeBanner.setBackgroundColor(Color.parseColor("#37474F"))
            binding.btnWrite.isEnabled = true
            binding.btnRecovery.isEnabled = true
            return
        }

        // Physical — evaluate eligibility
        val imageBytes = customBinBytes
        val imageSize = imageBytes?.size ?: 0
        val preflight = FlashPreflight(
            connected = true,
            physical = true,
            mpps = mpps,
            mppsAuthVerified = usbSerialManager.mppsAuthVerified,
            ecuIdentification = currentEcuId,
            voltage = voltage,
            imageSize = imageSize,
            checksumValid = imageSize == 0x200000, // true only when file loaded and correct size
            securityVerified = false,              // LegacyBlsSecurityAlgorithm.verified = false
            backupCompleted = sessionBackupCompleted,
            recoveryMode = false,
        )
        val normalEligibility = evaluateEligibility(preflight)
        val recoveryEligibility = evaluateEligibility(preflight.copy(recoveryMode = true))

        val writeEligible = normalEligibility is FlashEligibility.Eligible
        val recoveryEligible = recoveryEligibility is FlashEligibility.Eligible

        when {
            isTransactionActive -> {
                binding.tvModeBanner.text = getString(R.string.mode_flashing)
                binding.tvModeBanner.setBackgroundColor(Color.parseColor("#B71C1C"))
                binding.btnWrite.isEnabled = false
                binding.btnRecovery.isEnabled = false
            }
            writeEligible -> {
                binding.tvModeBanner.text = getString(R.string.mode_physical_write_eligible)
                binding.tvModeBanner.setBackgroundColor(Color.parseColor("#1565C0"))
                binding.btnWrite.isEnabled = true
                binding.btnRecovery.isEnabled = recoveryEligible
            }
            else -> {
                binding.tvModeBanner.text = getString(R.string.mode_physical_read_only)
                binding.tvModeBanner.setBackgroundColor(Color.parseColor("#263238"))
                binding.btnWrite.isEnabled = false
                binding.btnRecovery.isEnabled = false
            }
        }
    }

    private fun connectUsb(device: UsbDevice? = null) {
        if (usbSerialManager.isConnected || isConnecting) return
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

    @Volatile
    private var isTransactionActive = false

    private fun startVoltageMonitoring() {
        voltageJob?.cancel()
        voltageJob = lifecycleScope.launch {
            while (isActive && usbSerialManager.isConnected) {
                delay(2000)
                if (!isTransactionActive) {
                    val v = withContext(Dispatchers.IO) {
                        try { usbSerialManager.getBatteryVoltage() } catch (e: Throwable) { null }
                    }
                    if (v != null && v > 1.0f) {
                        runOnUiThread { updateUiConnected(v) }
                    }
                }
            }
        }
    }

    private fun updateUiConnected(voltage: Float?) {
        if (!usbSerialManager.isConnected) return
        val vStr = if (voltage != null) String.format(Locale.US, " | 🔋 %.1fV", voltage) else ""
        binding.tvUsbStatus.text = "● ${usbSerialManager.transportName}$vStr"
        val color = if (voltage == null || voltage >= 12.0f) "#00E676" else "#FFA726"
        binding.tvUsbStatus.setTextColor(Color.parseColor(color))
        updateModeBanner(voltage)
    }

    private fun ensureUsbConnected(): Boolean {
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

        lifecycleScope.launch {
            isTransactionActive = true
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
                updateModeBanner(usbSerialManager.getBatteryVoltage())
            } catch (e: Exception) {
                appendLog("[MPPS] Помилка зчитування ідентифікатора: ${e.message}")
            } finally {
                isTransactionActive = false
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
        setControlsEnabled(false)
        isTransactionActive = true
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
                    FileOutputStream(backupFile).use { it.write(fullImage) }
                    sessionBackupCompleted = true
                    appendLog("[MPPS] Бекап успішно збережено: ${backupFile.absolutePath}")
                    updateModeBanner(usbSerialManager.getBatteryVoltage())

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Зчитування завершено!")
                        .setMessage("Резервну копію збережено: " + backupFile.name + " (2 097 152 байти)")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                isTransactionActive = false
                setControlsEnabled(true)
            }
        }
    }

    private fun confirmAndFlash() {
        if (!ensureUsbConnected()) return

        val v = usbSerialManager.getBatteryVoltage()
        val voltWarning = if (v != null && v < 12.0f) {
            "\n⚠️ УВАГА: Напруга АКБ (${String.format(Locale.US, "%.1fV", v)}) нижче 12.0V! Підключіть зарядний пристрій!\n"
        } else ""

        val msg = "УВАГА (Запис MPPS):\n" +
            voltWarning +
            "1. Акумулятор заряджений (12.4В+).\n" +
            "2. Увімкніть 'Режим польоту' на телефоні.\n" +
            "3. Вимкніть споживачі в авто.\n" +
            "4. Контрольна сума буде автоматично перерахована.\n\n" +
            "Записати $selectedFileName?"

        AlertDialog.Builder(this)
            .setTitle("Запис прошивки (Write Flash)")
            .setMessage(msg)
            .setPositiveButton("Записати") { _, _ -> startFlashing(isRecovery = false) }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun confirmAndRecovery() {
        if (!ensureUsbConnected()) return

        val msg = "⚠️ УВАГА: РЕЖИМ АВАРІЙНОГО ВІДНОВЛЕННЯ\n\n" +
            "Використовуйте тільки якщо:\n" +
            "• Запис було перервано\n" +
            "• ЕБУ не реагує на стандартний запит\n\n" +
            if (!sessionBackupCompleted) {
                "🔴 У ВАС НЕМАЄ BACKUP!\n" +
                "Recovery може зламати ЕБУ без можливості відновлення!\n\n"
            } else {
                ""
            } +
            "Recovery пропустить перевірку ID та примусово увійде в бутлоадер.\n\n" +
            "Продовжити?"

        AlertDialog.Builder(this)
            .setTitle("Аварійне відновлення (Recovery)")
            .setMessage(msg)
            .setPositiveButton("ДАЛІ") { _, _ -> confirmRecoveryFinal() }
            .setNegativeButton("Скасувати", null)
            .show()
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
        isTransactionActive = true  // Set FIRST (race condition fix M3)
        setControlsEnabled(false)

        lifecycleScope.launch {
            try {
                val binBytes = customBinBytes ?: withContext(Dispatchers.IO) {
                    assets.open("03G906021QJ_stage1_refined_CS_OK.bin").readBytes()
                }

                // Use legacy EcuFlasher with read-back verification (C1 fix applied)
                // TODO: Full FlashTransaction migration in next phase
                val success = if (isRecovery) {
                    flasher.recoveryFlash(binBytes) { progress, msg ->
                        runOnUiThread {
                            binding.progressBar.progress = progress
                            binding.tvProgress.text = "$progress% - $msg"
                            appendLog(msg)
                        }
                    }
                } else {
                    flasher.flashFirmware(binBytes) { progress, msg ->
                        runOnUiThread {
                            binding.progressBar.progress = progress
                            binding.tvProgress.text = "$progress% - $msg"
                            appendLog(msg)
                        }
                    }
                }

                if (success) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Успіх!")
                        .setMessage("Прошивку успішно записано та верифіковано!\nВимкніть запалювання на 10с, потім запустіть двигун.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                isTransactionActive = false
                setControlsEnabled(true)
            }
        }
    }

    private fun clearDtc() {
        if (!usbSerialManager.isConnected) {
            appendLog("[DTC] Помилка: USB адаптер не підключено!")
            return
        }

        lifecycleScope.launch {
            isTransactionActive = true
            try {
                appendLog("[DTC] Очищення кодів помилок (Clear DTC Service 0x14)...")
                val ok = withContext(Dispatchers.IO) { protocol.clearDiagnosticTroubleCodes() }
                if (ok) {
                    appendLog("[DTC] Всі коди помилок успішно очищені!")
                } else {
                    appendLog("[DTC] Помилка або немає відповіді від ЕБУ.")
                }
            } finally {
                isTransactionActive = false
            }
        }
    }

    private fun loadBinaryFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null && bytes.size == 2097152) {
                    customBinBytes = bytes
                    selectedFileName = uri.lastPathSegment ?: "custom.bin"
                    binding.tvFileStatus.text = "File: $selectedFileName (2 097 152b OK)"
                    appendLog("[MPPS] Завантажено зовнішній бінарник: $selectedFileName")
                } else {
                    appendLog("[MPPS] Помилка: файл повинен бути рівно 2 097 152 байти!")
                }
            } catch (e: Exception) {
                appendLog("[MPPS] Помилка відкриття файлу: ${e.message}")
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnEcuId.isEnabled = enabled
        binding.btnRead.isEnabled = enabled
        binding.btnWrite.isEnabled = enabled
        binding.btnRecovery.isEnabled = enabled
        binding.btnClearDtc.isEnabled = enabled
        binding.btnSelectFile.isEnabled = enabled
    }

    private fun appendLog(msg: String) {
        binding.tvLog.append("$msg\n")
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
