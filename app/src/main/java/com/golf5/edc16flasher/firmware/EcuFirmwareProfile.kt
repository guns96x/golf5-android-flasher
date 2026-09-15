package com.golf5.edc16flasher.firmware

data class ChecksumBlock(
    val start: Int,
    val endExclusive: Int,
    val patchWordOffset: Int,
    val expectedResidue: Long,
)

data class EcuFirmwareProfile(
    val id: String,
    val fullImageSize: Int,
    val calibrationStart: Int,
    val calibrationSize: Int,
    val requiredIdentifiers: Set<String>,
    val checksumBlocks: List<ChecksumBlock>,
) {
    companion object {
        val EDC16U34_03G906021QJ_391847 = EcuFirmwareProfile(
            id = "EDC16U34_03G906021QJ_391847",
            fullImageSize = 0x200000,
            calibrationStart = 0x180000,
            calibrationSize = 0x080000,
            requiredIdentifiers = setOf("03G906021QJ", "391847"),
            checksumBlocks = listOf(
                ChecksumBlock(0x180000, 0x1C0000, 0x1BFFFC, 0xD01FE500L),
                ChecksumBlock(0x1C0000, 0x1FE000, 0x1FDFFC, 0xD01FE500L),
            ),
        )
    }
}
