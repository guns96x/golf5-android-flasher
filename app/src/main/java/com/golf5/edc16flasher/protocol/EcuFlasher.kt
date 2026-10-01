package com.golf5.edc16flasher.protocol

import com.golf5.edc16flasher.firmware.EcuFirmwareProfile
import com.golf5.edc16flasher.security.SecurityAccessAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Read-only ECU operations.
 *
 * Writing is handled exclusively by [com.golf5.edc16flasher.flashing.FlashTransaction]
 * (typed result, backup first, SHA-256 read-back before success). The former boolean
 * `flashFirmware()` / `recoveryFlash()` paths were removed: they bypassed security
 * verification and recovery had no read-back.
 *
 * @param security provider of the SID 0x27 algorithm for the currently active transport
 */
class EcuFlasher(
    private val protocol: Kwp2000Protocol,
    private val security: () -> SecurityAccessAlgorithm,
    private val profile: EcuFirmwareProfile = EcuFirmwareProfile.EDC16U34_03G906021QJ_391847,
) {

    /**
     * Reads the 512 KiB calibration region and returns it inside a 2 MiB container
     * (0xFF outside the calibration region). Returns null on any failure.
     */
    suspend fun readCalibration(
        onProgress: (Int, String) -> Unit
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val calStart = profile.calibrationStart
            val calSize = profile.calibrationSize

            onProgress(0, "[MPPS] Ініціалізація зчитування калібрувань...")
            protocol.startDiagnosticSession(0x85.toByte())

            onProgress(10, "[MPPS] Авторизація доступу Security Access...")
            val seed = protocol.requestSecuritySeed()
            val key = security().calculateKey(seed)
            if (!protocol.sendSecurityKey(key)) throw IOException("Security Access відхилено")

            onProgress(15, "[MPPS] Запит вивантаження (RequestUpload 0x%06X..0x%06X)...".format(calStart, calStart + calSize))
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
            if (calData.size != calSize) {
                throw IOException("Помилка зчитування: отримано ${calData.size} байт з очікуваних $calSize байт")
            }

            protocol.requestTransferExit()
            protocol.ecuReset()

            val fullImage = ByteArray(profile.fullImageSize) { 0xFF.toByte() }
            System.arraycopy(calData, 0, fullImage, calStart, calSize)

            onProgress(100, "[MPPS] Калібровку успішно зчитано (${profile.fullImageSize} байт контейнер)!")
            return@withContext fullImage
        } catch (e: Exception) {
            onProgress(0, "[MPPS] Помилка зчитування: ${e.message}")
            return@withContext null
        }
    }
}
