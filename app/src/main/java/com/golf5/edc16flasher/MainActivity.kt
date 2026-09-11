package com.golf5.edc16flasher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.golf5.edc16flasher.databinding.ActivityMainBinding
import com.golf5.edc16flasher.protocol.EcuFlasher
import com.golf5.edc16flasher.protocol.Kwp2000Protocol
import com.golf5.edc16flasher.usb.UsbSerialManager
import kotlinx.coroutines.Dispatchers
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

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadBinaryFromUri(it) }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbSerialManager.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        appendLog("[USB] Дозвіл USB надано користувачем.")
                        connectUsb()
                    } else {
                        appendLog("[USB] Помилка: Доступ до USB відхилено.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("[USB] Виявлено підключення діагностичного адаптера.")
                    checkUsbDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("[USB] Адаптер відключено.")
                    usbSerialManager.close()
                    updateUiDisconnected()
                }
            }
        }
    }

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
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setupButtons()
        checkUsbDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        usbSerialManager.close()
    }

    private fun setupButtons() {
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
        val drivers = usbSerialManager.findSupportedDevices()
        if (drivers.isNotEmpty()) {
            val driver = drivers[0]
            val dev = driver.device
            binding.tvUsbStatus.text = "● Адаптер підключено: ${dev.productName ?: "MPPS / FTDI K-Line"}"
            binding.tvUsbStatus.setTextColor(Color.parseColor("#00E676"))
            appendLog("[USB] Знайдено адаптер: ${dev.productName ?: "USB Serial"} (VID: 0x${Integer.toHexString(dev.vendorId).uppercase()})")
            usbSerialManager.requestPermission(driver) {
                appendLog("[USB] Запит системного дозволу USB...")
            }
        } else {
            updateUiDisconnected()
        }
    }

    private fun updateUiDisconnected() {
        binding.tvUsbStatus.text = "● USB кабель не підключено"
        binding.tvUsbStatus.setTextColor(Color.parseColor("#FF5252"))
    }

    private fun connectUsb() {
        val drivers = usbSerialManager.findSupportedDevices()
        if (drivers.isNotEmpty()) {
            if (usbSerialManager.open(drivers[0], 10400)) {
                appendLog("[USB] Порт K-Line успішно відкрито (10400 бод).")
            } else {
                appendLog("[USB] Не вдалося відкрити порт.")
            }
        }
    }

    private fun readEcuId() {
        if (!usbSerialManager.isConnected) {
            appendLog("[MPPS] Помилка: USB адаптер не підключено!")
            return
        }

        lifecycleScope.launch {
            appendLog("[MPPS] Зчитування ідентифікатора ЕБУ...")
            try {
                val ecuInfo = withContext(Dispatchers.IO) { protocol.readEcuIdentification() }
                binding.tvEcuId.text = "ECU ID: $ecuInfo"
                appendLog("[MPPS] Успішно:\n$ecuInfo")
            } catch (e: Exception) {
                appendLog("[MPPS] Помилка: ${e.message}")
            }
        }
    }

    private fun confirmAndRead() {
        if (!usbSerialManager.isConnected) {
            appendLog("[MPPS] Помилка: USB адаптер не підключено!")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Зчитування калібрувань (Read ECU)")
            .setMessage("Зчитати калібрувальний сектор (512 КБ) у пам'ять телефону для бекапу?")
            .setPositiveButton("Зчитати") { _, _ -> startReading() }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun startReading() {
        setControlsEnabled(false)
        lifecycleScope.launch {
            val fullImage = flasher.readCalibration { progress, msg ->
                runOnUiThread {
                    binding.progressBar.progress = progress
                    binding.tvProgress.text = "$progress% - $msg"
                    appendLog(msg)
                }
            }
            setControlsEnabled(true)

            if (fullImage != null) {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val backupFile = File(getExternalFilesDir(null), "03G906021QJ_backup_$timeStamp.bin")
                FileOutputStream(backupFile).use { it.write(fullImage) }
                appendLog("[MPPS] Бекап успішно збережено: ${backupFile.absolutePath}")

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Зчитування завершено!")
                    .setMessage("Резервну копію збережено:
${backupFile.name}
Розмір: 2 097 152 байти.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun confirmAndFlash() {
        if (!usbSerialManager.isConnected) {
            appendLog("[MPPS] Помилка: USB адаптер не підключено!")
            return
        }

        val msg = "УВАГА (Запис MPPS):\n\n" +
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
        if (!usbSerialManager.isConnected) {
            appendLog("[RECOVERY] Помилка: USB адаптер не підключено!")
            return
        }

        val msg = "УВАГА: РЕЖИМ АВАРІЙНОГО ВІДНОВЛЕННЯ\n\n" +
            "Використовуйте тільки якщо запис було перервано і блок не реагує на стандартний запит.\n" +
            "Програма пропустить перевірку ID і примусово увійде в режим бутлоадера.\n\n" +
            "Почати аварійне відновлення?"

        AlertDialog.Builder(this)
            .setTitle("Аварійне відновлення (Recovery)")
            .setMessage(msg)
            .setPositiveButton("Відновити") { _, _ -> startFlashing(isRecovery = true) }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun startFlashing(isRecovery: Boolean) {
        setControlsEnabled(false)
        lifecycleScope.launch {
            val binBytes = customBinBytes ?: withContext(Dispatchers.IO) {
                assets.open("03G906021QJ_stage1_refined_CS_OK.bin").readBytes()
            }

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
            setControlsEnabled(true)

            if (success) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Успіх!")
                    .setMessage("Прошивку успішно записано (КС валідна)!
Вимкніть запалювання на 10с, потім запустіть двигун.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun clearDtc() {
        if (!usbSerialManager.isConnected) {
            appendLog("[DTC] Помилка: USB адаптер не підключено!")
            return
        }

        lifecycleScope.launch {
            appendLog("[DTC] Очищення кодів помилок (Clear DTC Service 0x14)...")
            val ok = withContext(Dispatchers.IO) { protocol.clearDiagnosticTroubleCodes() }
            if (ok) {
                appendLog("[DTC] Всі коди помилок успішно очищені!")
            } else {
                appendLog("[DTC] Помилка або немає відповіді від ЕБУ.")
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
