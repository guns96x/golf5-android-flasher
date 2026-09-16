package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.firmware.EcuFirmwareProfile
import com.golf5.edc16flasher.firmware.Edc16ChecksumEngine
import com.golf5.edc16flasher.security.LegacyBlsSecurityAlgorithm
import com.golf5.edc16flasher.security.SecurityAccessAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

class EcuFlasher(
    private val protocol: Kwp2000Protocol,
    private val profile: EcuFirmwareProfile = EcuFirmwareProfile.EDC16U34_03G906021QJ_391847,
    private val checksumEngine: Edc16ChecksumEngine = Edc16ChecksumEngine,
    private val security: SecurityAccessAlgorithm = LegacyBlsSecurityAlgorithm,
) {

    /**
     * Standard MPPS Write with Automatic Checksum Correction & Safety Gates
     */
    suspend fun flashFirmware(
        rawBytes: ByteArray,
        onProgress: (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(0, "[MPPS] Перевірка розміру бінарного файлу...")
            if (rawBytes.size != 2097152) {
                onProgress(0, "Помилка: розмір файлу повинен бути рівно 2 097 152 байти!")
                return@withContext false
            }

            onProgress(2, "[MPPS] Автоматичний перерахунок КС (інваріант 0xD01FE500)...")
            val firmwareBytes = Edc16ChecksumEngine.fix(rawBytes)
            onProgress(4, "[MPPS] Контрольна сума валідна (Checksum OK)!")

            onProgress(6, "[MPPS] Перевірка ідентифікатора ЕБУ...")
            val ecuId = protocol.readEcuIdentification()
            onProgress(8, "Ідентифіковано: $ecuId")

            if (!ecuId.contains("03G906021QJ") && !ecuId.contains("391847")) {
                onProgress(0, "УВАГА: Захисне блокування! Номер ЕБУ не співпадає з 03G906021QJ (SW 391847)")
                return@withContext false
            }

            onProgress(10, "[MPPS] Вхід у сесію програмування (0x10 0x85)...")
            protocol.startDiagnosticSession(0x85.toByte())

            onProgress(12, "[MPPS] Авторизація доступу Security Access (0x27)...")
            val seed = protocol.requestSecuritySeed()
            val key = security.calculateKey(seed)
            protocol.sendSecurityKey(key)

            onProgress(15, "[MPPS] Запит запису калібровок (0x180000 - 0x200000)...")
            val calStart = 0x180000
            val calSize = 0x080000 // 512 KB
            protocol.requestDownload(calStart, calSize)

            val blockSize = 128
            val totalBlocks = calSize / blockSize
            var blockSeq: Byte = 1

            for (i in 0 until totalBlocks) {
                val offset = calStart + (i * blockSize)
                val chunk = firmwareBytes.copyOfRange(offset, offset + blockSize)
                protocol.transferData(blockSeq, chunk)
                blockSeq = ((blockSeq + 1) and 0xFF).toByte()

                val progress = 15 + ((i.toFloat() / totalBlocks) * 75).toInt()
                if (i % 64 == 0 || i == totalBlocks - 1) {
                    onProgress(progress, "[MPPS] Запис блоку ${i + 1}/$totalBlocks ($progress%)")
                }
            }

            onProgress(92, "[MPPS] Завершення передачі даних (TransferExit)...")
            protocol.requestTransferExit()

            // ── READ-BACK VERIFICATION (CRITICAL SAFETY) ──
            onProgress(93, "[MPPS] Верифікація запису — зчитування калібрувань...")
            val writtenCalibration = firmwareBytes.copyOfRange(calStart, calStart + calSize)
            val readBackCalibration = readCalibrationForVerification(calStart, calSize, onProgress)

            onProgress(94, "[MPPS] Порівняння SHA-256...")
            val writtenSha = sha256Hex(writtenCalibration)
            val readBackSha = sha256Hex(readBackCalibration)

            if (writtenSha != readBackSha) {
                onProgress(0, "[MPPS] ПОМИЛКА ВЕРИФІКАЦІЇ! SHA-256 не співпадають!")
                onProgress(0, "Записано: $writtenSha")
                onProgress(0, "Прочитано: $readBackSha")
                onProgress(0, "ЕБУ може бути пошкоджений! НЕ вимикайте запалювання!")
                return@withContext false
            }

            onProgress(95, "[MPPS] ✓ Верифікація пройшла успішно! SHA-256: $writtenSha")

            onProgress(96, "[MPPS] Очищення кодів несправностей (Clear DTC)...")
            protocol.clearDiagnosticTroubleCodes()

            onProgress(98, "[MPPS] Перезавантаження ЕБУ (ECU Reset)...")
            val resetOk = protocol.ecuReset()
            if (!resetOk) {
                onProgress(99, "[MPPS] УВАГА: Reset не спрацював — вимкніть/увімкніть запалювання вручну!")
            }

            onProgress(100, "[MPPS] Прошивку успішно записано і верифіковано! Зачекайте 10с перед запуском.")
            return@withContext true
        } catch (e: Exception) {
            onProgress(0, "[MPPS] Помилка під час запису: ${e.message}")
            return@withContext false
        }
    }

    /**
     * MPPS Emergency Recovery Mode:
     * Bypasses standard identification checks, forces wake-up and direct programming session.
     */
    suspend fun recoveryFlash(
        rawBytes: ByteArray,
        onProgress: (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(0, "[RECOVERY] Аварійне відновлення: ініціалізація шини...")
            if (rawBytes.size != 2097152) {
                onProgress(0, "Помилка: розмір файлу повинен бути 2 097 152 байти!")
                return@withContext false
            }

            val firmwareBytes = Edc16ChecksumEngine.fix(rawBytes)
            onProgress(5, "[RECOVERY] Примусова активація сесії програмування...")
            protocol.forceRecoverySession()

            onProgress(10, "[RECOVERY] Зняття захисту Seed/Key...")
            val seed = protocol.requestSecuritySeed()
            val key = security.calculateKey(seed)
            protocol.sendSecurityKey(key)

            onProgress(15, "[RECOVERY] Запис сектора калібрувань...")
            val calStart = 0x180000
            val calSize = 0x080000
            protocol.requestDownload(calStart, calSize)

            val blockSize = 128
            val totalBlocks = calSize / blockSize
            var blockSeq: Byte = 1

            for (i in 0 until totalBlocks) {
                val offset = calStart + (i * blockSize)
                val chunk = firmwareBytes.copyOfRange(offset, offset + blockSize)
                protocol.transferData(blockSeq, chunk)
                blockSeq = ((blockSeq + 1) and 0xFF).toByte()

                val progress = 15 + ((i.toFloat() / totalBlocks) * 75).toInt()
                if (i % 64 == 0 || i == totalBlocks - 1) {
                    onProgress(progress, "[RECOVERY] Запис: ${i + 1}/$totalBlocks ($progress%)")
                }
            }

            onProgress(92, "[RECOVERY] Завершення сесії передачі...")
            protocol.requestTransferExit()

            onProgress(95, "[RECOVERY] Очищення помилок DTC...")
            protocol.clearDiagnosticTroubleCodes()

            onProgress(98, "[RECOVERY] Перезавантаження ЕБУ...")
            protocol.ecuReset()

            onProgress(100, "[RECOVERY] Блок успішно відновлено!")
            return@withContext true
        } catch (e: Exception) {
            onProgress(0, "[RECOVERY] Помилка відновлення: ${e.message}")
            return@withContext false
        }
    }

    /**
     * MPPS Read Calibration Area (512 KB) to phone storage
     */
    suspend fun readCalibration(
        onProgress: (Int, String) -> Unit
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            onProgress(0, "[MPPS] Ініціалізація зчитування калібрувань...")
            protocol.startDiagnosticSession(0x85.toByte())

            onProgress(10, "[MPPS] Авторизація доступу Security Access...")
            val seed = protocol.requestSecuritySeed()
            val key = Edc16Security.calculateKey(seed)
            protocol.sendSecurityKey(key)

            onProgress(15, "[MPPS] Запит вивантаження (RequestUpload 0x180000..0x200000)...")
            val calStart = 0x180000
            val calSize = 0x080000
            val blockSize = protocol.requestUpload(calStart, calSize)
            val totalBlocks = (calSize + blockSize - 1) / blockSize

            onProgress(18, "[MPPS] Погоджено розмір блоку: $blockSize байт. Всього блоків: $totalBlocks")
            val outStream = ByteArrayOutputStream()
            var blockSeq: Byte = 1

            for (i in 0 until totalBlocks) {
                val chunk = protocol.readMemoryChunk(blockSeq, blockSize)
                outStream.write(chunk)
                blockSeq = ((blockSeq + 1) and 0xFF).toByte()

                val progress = 18 + ((i.toFloat() / totalBlocks) * 75).toInt()
                if (i % 64 == 0 || i == totalBlocks - 1) {
                    onProgress(progress, "[MPPS] Зчитування: ${i + 1}/$totalBlocks ($progress%)")
                }
            }

            val calData = outStream.toByteArray()
            if (calData.size < calSize) {
                throw IOException("Помилка зчитування: отримано ${calData.size} байт з очікуваних $calSize байт")
            }

            protocol.requestTransferExit()
            protocol.ecuReset()

            // Build full 2 MiB container with 0xFF padding before 0x180000
            val fullImage = ByteArray(2097152) { 0xFF.toByte() }
            System.arraycopy(calData, 0, fullImage, 0x180000, minOf(calData.size, 0x080000))

            onProgress(100, "[MPPS] Калібровку успішно зчитано (2 097 152 байти)!")
            return@withContext fullImage
        } catch (e: Exception) {
            onProgress(0, "[MPPS] Помилка зчитування: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Read calibration region for verification after write.
     * Used by flashFirmware() for read-back verification (C1 fix).
     */
    private suspend fun readCalibrationForVerification(
        startAddress: Int,
        size: Int,
        onProgress: (Int, String) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val blockSize = protocol.requestUpload(startAddress, size)
        val result = ByteArray(size)
        var offset = 0
        var seq = 1

        while (offset < size) {
            val chunk = protocol.readMemoryChunk(seq.toByte(), blockSize)
            val len = minOf(chunk.size, size - offset)
            System.arraycopy(chunk, 0, result, offset, len)
            offset += len
            seq = (seq % 255) + 1
        }

        protocol.requestTransferExit()
        return@withContext result
    }

    /**
     * Calculate SHA-256 hex string for verification.
     */
    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
