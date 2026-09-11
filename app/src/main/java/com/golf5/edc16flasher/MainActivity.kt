package com.golf5.edc16flasher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbSerialManager: UsbSerialManager
    private lateinit var protocol: Kwp2000Protocol
    private lateinit var flasher: EcuFlasher

    private var customBinBytes: ByteArray? = null
    private var selectedFileName: String = "03G906021QJ_stage1_refined_dpf_egr_off.bin (Вбудована)"

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
                        appendLog("Дозвіл USB надано користувачем.")
                        connectUsb()
                    } else {
                        appendLog("Помилка: Доступ до USB відхилено.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("Виявлено підключення USB пристрою.")
                    checkUsbDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("USB пристрій відключено.")
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
        binding.btnReadId.setOnClickListener {
            readEcuId()
        }

        binding.btnFlash.setOnClickListener {
            confirmAndFlash()
        }
    }

    private fun checkUsbDevices() {
        val drivers = usbSerialManager.findSupportedDevices()
        if (drivers.isNotEmpty()) {
            val driver = drivers[0]
            val dev = driver.device
            binding.tvUsbStatus.text = "● Виявлено адаптер: ${dev.manufacturerName ?: "FTDI"} (${dev.productName ?: "K-Line"})"
            binding.tvUsbStatus.setTextColor(getColor(R.color.accent))
            binding.tvDeviceInfo.text = "VendorID: 0x${Integer.toHexString(dev.vendorId).uppercase()}, ProductID: 0x${Integer.toHexString(dev.productId).uppercase()}"

            appendLog("Знайдено адаптер: ${dev.productName ?: "USB Serial"}")
            usbSerialManager.requestPermission(driver) {
                appendLog("Запит дозволу USB...")
            }
        } else {
            updateUiDisconnected()
        }
    }

    private fun updateUiDisconnected() {
        binding.tvUsbStatus.text = "● USB кабель не підключено"
        binding.tvUsbStatus.setTextColor(getColor(R.color.text_secondary))
        binding.tvDeviceInfo.text = "Підключіть OTG перехідник та кабель MPPS / K-Line"
    }

    private fun connectUsb() {
        val drivers = usbSerialManager.findSupportedDevices()
        if (drivers.isNotEmpty()) {
            if (usbSerialManager.open(drivers[0], 10400)) {
                appendLog("Порт успішно відкрито (10400 бод). Готово до роботи!")
            } else {
                appendLog("Не вдалося відкрити порт USB.")
            }
        }
    }

    private fun readEcuId() {
        if (!usbSerialManager.isConnected) {
            appendLog("Помилка: USB адаптер не підключено!")
            return
        }

        lifecycleScope.launch {
            appendLog("Зчитування даних ЕБУ (KWP2000)...")
            try {
                val ecuInfo = withContext(Dispatchers.IO) {
                    protocol.readEcuIdentification()
                }
                binding.tvEcuId.text = ecuInfo
                appendLog("Успішно:\n$ecuInfo")
            } catch (e: Exception) {
                appendLog("Помилка зчитування ID: ${e.message}")
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
                    binding.tvFileStatus.text = "Файл: $selectedFileName (2 097 152 байт OK)"
                    appendLog("Завантажено зовнішній файл: $selectedFileName")
                } else {
                    appendLog("Помилка: розмір файлу повинен бути рівно 2 097 152 байти!")
                }
            } catch (e: Exception) {
                appendLog("Помилка завантаження файлу: ${e.message}")
            }
        }
    }

    private fun confirmAndFlash() {
        if (!usbSerialManager.isConnected) {
            appendLog("Помилка: USB адаптер не підключено!")
            return
        }

        val message = "УВАГА:\n\n" +
            "1. Переконайтеся, що акумулятор автомобіля заряджений (не нижче 12.4 В).\n" +
            "2. Увімкніть режим 'У літаку' на телефоні, щоб уникнути дзвінків.\n" +
            "3. НЕ відключайте OTG кабель під час запису!\n" +
            "4. Вимкніть споживачі (клімат, фари, музику).\n\n" +
            "Почати запис прошивки ($selectedFileName)?"

        AlertDialog.Builder(this)
            .setTitle("Підтвердження запису прошивки")
            .setMessage(message)
            .setPositiveButton("Записати") { _, _ ->
                startFlashing()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun startFlashing() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.btnFlash.isEnabled = false
        binding.btnReadId.isEnabled = false

        lifecycleScope.launch {
            val binBytes = customBinBytes ?: withContext(Dispatchers.IO) {
                assets.open("03G906021QJ_stage1_refined_dpf_egr_off.bin").readBytes()
            }

            appendLog("Завантажено прошивку: ${binBytes.size} байт")
            val success = flasher.flashFirmware(binBytes) { progress, message ->
                runOnUiThread {
                    binding.progressBar.progress = progress
                    binding.tvProgress.text = "$progress% - $message"
                    appendLog(message)
                }
            }

            binding.btnFlash.isEnabled = true
            binding.btnReadId.isEnabled = true

            if (success) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Успіх!")
                    .setMessage("Прошивку успішно записано в блок EDC16U34!\nВимкніть запалювання на 10 секунд, після чого запустіть двигун.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun appendLog(msg: String) {
        binding.tvLog.append("$msg\n")
    }
}
