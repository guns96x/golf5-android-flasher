package com.golf5.edc16flasher.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EcuFlasher(private val protocol: Kwp2000Protocol) {

    suspend fun flashFirmware(
        firmwareBytes: ByteArray,
        onProgress: (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(0, "Перевірка цілісності бінарника...")
            if (firmwareBytes.size != 2097152) {
                onProgress(0, "Помилка: розмір файлу повинен бути рівно 2 097 152 байти!")
                return@withContext false
            }

            onProgress(5, "Вхід у діагностичну сесію...")
            protocol.startDiagnosticSession(0x85.toByte())

            onProgress(10, "Запит захисного доступу (Security Access)...")
            val seed = protocol.requestSecuritySeed()
            val key = Edc16Security.calculateKey(seed)
            protocol.sendSecurityKey(key)

            onProgress(15, "Ініціалізація завантаження Flash пам'яті...")
            // EDC16 calibration sector start at 0x180000
            val calStart = 0x180000
            val calSize = 0x080000 // 512 KB
            protocol.requestDownload(calStart, calSize)

            val blockSize = 256
            val totalBlocks = calSize / blockSize
            var blockSeq: Byte = 1

            for (i in 0 until totalBlocks) {
                val offset = calStart + (i * blockSize)
                val chunk = firmwareBytes.copyOfRange(offset, offset + blockSize)
                protocol.transferData(blockSeq, chunk)
                blockSeq = ((blockSeq + 1) and 0xFF).toByte()

                val progress = 15 + ((i.toFloat() / totalBlocks) * 80).toInt()
                if (i % 32 == 0 || i == totalBlocks - 1) {
                    onProgress(progress, "Запис блоків: ${i + 1} / $totalBlocks ($progress%)")
                }
            }

            onProgress(95, "Завершення сесії передачі (TransferExit)...")
            protocol.requestTransferExit()

            onProgress(98, "Перезавантаження ЕБУ (Hard Reset)...")
            protocol.ecuReset()

            onProgress(100, "Прошивку успішно записано!")
            return@withContext true
        } catch (e: Exception) {
            onProgress(0, "Помилка під час запису: ${e.message}")
            return@withContext false
        }
    }
}
