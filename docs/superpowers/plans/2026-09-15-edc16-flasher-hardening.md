# EDC16 Android Flasher Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the EDC16 Android flasher deterministic, fully testable in the offline emulator, and fail-closed for unverified physical write behavior.

**Architecture:** Split raw KWP framing/parsing, ECU profile/checksum logic, security access, MPPS authentication, emulator state, flash eligibility and transaction orchestration into explicit units. All destructive logic is exercised against a stateful in-memory ECU before the physical path can advertise write eligibility.

**Tech Stack:** Kotlin/JVM 17, Android SDK 34, JUnit 4, kotlinx-coroutines 1.7.3, Android USB Host, Python 3 for Termux parity tests, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-15-edc16-flasher-hardening-design.md`

## Global Constraints

- Primary ECU profile is exactly `03G906021QJ` / SW `391847`.
- Full image size is exactly `0x200000` bytes.
- Calibration region is exactly `[0x180000, 0x200000)` / `0x080000` bytes.
- Checksum block 1 is `[0x180000, 0x1C0000)` with patch word `0x1BFFFC`.
- Checksum block 2 is `[0x1C0000, 0x1FE000)` with patch word `0x1FDFFC`.
- Expected additive 32-bit big-endian residue is exactly `0xD01FE500` for each declared checksum block.
- Do not infer unknown checksum regions, seed/key algorithms, MPPS authentication formulas, CAN commands or TP2.0 behavior.
- Unknown security/authentication state means physical write capability is false.
- Recovery mode may bypass ECU ID only; it may not bypass voltage, image-size, checksum, security-verification or transport-integrity gates.
- No physical flash success message before full 512 KiB read-back SHA-256 verification succeeds.
- Keep Termux bridge bound to `127.0.0.1` only.
- Do not add EDC17 support in this plan.
- Do not rewrite Git history or change repository visibility in this plan.

---

## File map to preserve during implementation

### New Kotlin files

- `app/src/main/java/com/golf5/edc16flasher/protocol/KwpFrame.kt` — parsed KWP response model and typed protocol exceptions.
- `app/src/main/java/com/golf5/edc16flasher/protocol/KwpFrameCodec.kt` — pure encode/parse/checksum logic.
- `app/src/main/java/com/golf5/edc16flasher/protocol/KwpTransport.kt` — minimal transport contract consumed by `Kwp2000Protocol`.
- `app/src/main/java/com/golf5/edc16flasher/firmware/EcuFirmwareProfile.kt` — immutable ECU/image/checksum profile.
- `app/src/main/java/com/golf5/edc16flasher/firmware/Edc16ChecksumEngine.kt` — pure checksum fix/verify.
- `app/src/main/java/com/golf5/edc16flasher/security/SecurityAccessAlgorithm.kt` — security abstraction and verification flag.
- `app/src/main/java/com/golf5/edc16flasher/security/LegacyBlsSecurityAlgorithm.kt` — current formula, explicitly unverified.
- `app/src/main/java/com/golf5/edc16flasher/security/MockSecurityAlgorithm.kt` — deterministic verified emulator-only implementation.
- `app/src/main/java/com/golf5/edc16flasher/usb/MppsAuthenticator.kt` — MPPS challenge/response model with known-vector handling and fail-closed unknowns.
- `app/src/main/java/com/golf5/edc16flasher/flashing/FlashEligibility.kt` — preflight facts and refusal reasons.
- `app/src/main/java/com/golf5/edc16flasher/flashing/FlashTransaction.kt` — typed stages/results and orchestration.

### New JVM tests

- `app/src/test/java/com/golf5/edc16flasher/protocol/KwpFrameCodecTest.kt`
- `app/src/test/java/com/golf5/edc16flasher/protocol/Kwp2000ProtocolTest.kt`
- `app/src/test/java/com/golf5/edc16flasher/firmware/Edc16ChecksumEngineTest.kt`
- `app/src/test/java/com/golf5/edc16flasher/security/SecurityAccessTest.kt`
- `app/src/test/java/com/golf5/edc16flasher/usb/MppsAuthenticatorTest.kt`
- `app/src/test/java/com/golf5/edc16flasher/usb/MockEdc16TransportTest.kt`
- `app/src/test/java/com/golf5/edc16flasher/flashing/FlashEligibilityTest.kt`
- `app/src/test/java/com/golf5/edc16flasher/flashing/FlashTransactionTest.kt`

### Existing files to modify

- `app/build.gradle.kts`
- `app/src/main/java/com/golf5/edc16flasher/protocol/Kwp2000Protocol.kt`
- `app/src/main/java/com/golf5/edc16flasher/protocol/EcuFlasher.kt`
- `app/src/main/java/com/golf5/edc16flasher/protocol/Edc16Security.kt`
- `app/src/main/java/com/golf5/edc16flasher/usb/IUsbTransport.kt`
- `app/src/main/java/com/golf5/edc16flasher/usb/UsbSerialManager.kt`
- `app/src/main/java/com/golf5/edc16flasher/usb/MockEdc16Transport.kt`
- `app/src/main/java/com/golf5/edc16flasher/usb/MppsHardwareTransport.kt`
- `app/src/main/java/com/golf5/edc16flasher/usb/TermuxBridgeServer.kt`
- `app/src/main/java/com/golf5/edc16flasher/MainActivity.kt`
- `termux/edc16_flasher.py`
- `README.md`

---

### Task 1: Add JVM test harness and pure transport boundary

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/golf5/edc16flasher/protocol/KwpTransport.kt`
- Modify: `app/src/main/java/com/golf5/edc16flasher/usb/IUsbTransport.kt`
- Modify: `app/src/main/java/com/golf5/edc16flasher/usb/UsbSerialManager.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/protocol/KwpTransportSmokeTest.kt`

**Interfaces:**
- Produces: `KwpTransport` consumed by Tasks 2, 5 and 6.

- [x] **Step 1: Add local test dependencies**

Add to `dependencies` in `app/build.gradle.kts`:

```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

- [x] **Step 2: Create the transport contract**

Create `KwpTransport.kt` exactly around protocol I/O, not lifecycle/discovery:

```kotlin
package com.golf5.edc16flasher.protocol

interface KwpTransport {
    val transportName: String
    val isPhysical: Boolean
    val isMpps: Boolean

    fun write(data: ByteArray, timeoutMs: Int = 2000)
    fun read(buffer: ByteArray, timeoutMs: Int = 2000): Int
    fun getBatteryVoltage(): Float?
    fun sendFastInit(pulseMs: Int = 25, initialPayload: ByteArray = ByteArray(0))
}
```

- [x] **Step 3: Make `IUsbTransport` extend `KwpTransport`**

Keep `open`, `close`, `isConnected`, `runDiagnostic` on `IUsbTransport`. Add default `isPhysical = true`. `MockEdc16Transport` must override `isPhysical = false` in Task 5.

- [x] **Step 4: Make `UsbSerialManager` implement `KwpTransport` by delegation**

Do not expose `activeTransport` publicly. Implement each protocol method by delegating to `activeTransport` or throwing `IOException("USB Transport is not open")`.

- [x] **Step 5: Add a smoke test with a fake transport**

```kotlin
class KwpTransportSmokeTest {
    @Test fun fakeTransportCanRoundTripBytes() {
        val fake = object : KwpTransport {
            override val transportName = "fake"
            override val isPhysical = false
            override val isMpps = false
            private val queue = ArrayDeque<Byte>()
            override fun write(data: ByteArray, timeoutMs: Int) { data.forEach(queue::addLast) }
            override fun read(buffer: ByteArray, timeoutMs: Int): Int {
                var n = 0
                while (n < buffer.size && queue.isNotEmpty()) buffer[n++] = queue.removeFirst()
                return n
            }
            override fun getBatteryVoltage(): Float? = 13.8f
            override fun sendFastInit(pulseMs: Int, initialPayload: ByteArray) = Unit
        }
        fake.write(byteArrayOf(1, 2, 3))
        val out = ByteArray(3)
        assertEquals(3, fake.read(out))
        assertArrayEquals(byteArrayOf(1, 2, 3), out)
    }
}
```

- [x] **Step 6: Run tests**

Run: `./gradlew testDebugUnitTest`

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/golf5/edc16flasher/protocol/KwpTransport.kt app/src/main/java/com/golf5/edc16flasher/usb/IUsbTransport.kt app/src/main/java/com/golf5/edc16flasher/usb/UsbSerialManager.kt app/src/test/java/com/golf5/edc16flasher/protocol/KwpTransportSmokeTest.kt
git commit -m "test: add pure KWP transport boundary"
```

---

### Task 2: Replace raw SID scanning with strict KWP frame codec

**Files:**
- Create: `app/src/main/java/com/golf5/edc16flasher/protocol/KwpFrame.kt`
- Create: `app/src/main/java/com/golf5/edc16flasher/protocol/KwpFrameCodec.kt`
- Modify: `app/src/main/java/com/golf5/edc16flasher/protocol/Kwp2000Protocol.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/protocol/KwpFrameCodecTest.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/protocol/Kwp2000ProtocolTest.kt`

**Interfaces:**
- Produces: `KwpFrame`, `KwpFrameCodec.encodeRequest()`, `KwpFrameCodec.parseResponse()`, typed protocol exceptions.

- [x] **Step 1: Write parser tests before implementation**

Cover exactly these cases:

```kotlin
@Test fun parsesShortPositiveFrame()
@Test fun parsesLongPositiveFrame()
@Test fun rejectsBadChecksum()
@Test fun rejectsDeclaredLengthMismatch()
@Test fun preservesPayloadBytesContaining0x7f()
@Test fun removesOnlyExactLeadingEcho()
```

For the short-frame test, build a response with target `0xF1`, source `0x01`, SID `0x5A`, payload `0x9B 0x31 0x32`, then append additive 8-bit checksum. Assert service ID and payload, not merely that parsing returned non-null.

- [x] **Step 2: Implement immutable frame types**

Use:

```kotlin
data class KwpFrame(
    val target: Int,
    val source: Int,
    val serviceId: Int,
    val payload: ByteArray,
    val raw: ByteArray,
)

class KwpFrameException(message: String) : IOException(message)
class KwpNegativeResponseException(val failedSid: Int, val nrc: Int) : IOException(
    "ECU negative response: SID 0x%02X NRC 0x%02X".format(failedSid, nrc)
)
```

- [x] **Step 3: Implement `KwpFrameCodec` as pure Kotlin**

Required behavior:

- encode short header for `dataLen <= 63`;
- encode long header otherwise, up to `255` service+payload bytes;
- checksum is sum of all frame bytes before checksum modulo 256;
- parser accepts only response target `0xF1` and source `0x01` for this profile;
- parser validates exact total length and checksum;
- parser does not search arbitrary raw bytes for SID.

- [x] **Step 4: Write request-engine tests with a scripted transport**

Create a test fake where each `write()` can enqueue exact read chunks. Add:

```kotlin
@Test fun nrc78ThenPositiveResponseReturnsPositiveFrame()
@Test fun nonPendingNrcThrowsTypedException()
@Test fun timeoutIncludesLastParserFailure()
@Test fun partialReadsAreAccumulatedUntilOneFrameIsComplete()
```

The NRC pending sequence must be `0x7F <requested SID> 0x78` inside a valid KWP frame. The protocol must not retransmit the original request after NRC `0x78`.

- [x] **Step 5: Refactor `Kwp2000Protocol`**

Constructor becomes:

```kotlin
class Kwp2000Protocol(
    private val transport: KwpTransport,
    private val log: (String) -> Unit = {},
)
```

`sendRequest()` returns `KwpFrame`, not raw `ByteArray`. Update public helpers to read `frame.serviceId` and `frame.payload`.

`readFrame` must accumulate bytes because one transport read is not guaranteed to equal one KWP frame. Keep an internal receive buffer and parse only when a complete frame is available.

- [x] **Step 6: Update call sites until compilation is green**

`MainActivity` should still construct `Kwp2000Protocol(usbSerialManager)`, because Task 1 made `UsbSerialManager` a `KwpTransport`.

- [x] **Step 7: Run**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Expected: both PASS.

- [x] **Step 8: Commit**

```bash
git add app/src/main/java/com/golf5/edc16flasher/protocol app/src/test/java/com/golf5/edc16flasher/protocol
git commit -m "fix: strictly parse KWP2000 frames"
```

---

### Task 3: Extract exact ECU firmware profile and checksum engine

**Files:**
- Create: `app/src/main/java/com/golf5/edc16flasher/firmware/EcuFirmwareProfile.kt`
- Create: `app/src/main/java/com/golf5/edc16flasher/firmware/Edc16ChecksumEngine.kt`
- Modify: `app/src/main/java/com/golf5/edc16flasher/protocol/EcuFlasher.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/firmware/Edc16ChecksumEngineTest.kt`

**Interfaces:**
- Produces: `EcuFirmwareProfile.EDC16U34_03G906021QJ_391847`, `ChecksumVerification`, `Edc16ChecksumEngine.fix`, `verify`.

- [x] **Step 1: Write failing checksum tests**

Generate a synthetic 2 MiB image entirely in memory. Set deterministic 32-bit words in the two covered checksum blocks. Assert:

```kotlin
@Test fun rejectsWrongImageSize()
@Test fun fixDoesNotMutateInputArray()
@Test fun fixMakesBothDeclaredResiduesEqualD01FE500()
@Test fun verifyFailsAfterOneCoveredByteIsChanged()
@Test fun bytesOutsideDeclaredChecksumBlocksAreUnchanged()
```

- [x] **Step 2: Create exact profile types**

```kotlin
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
```

- [x] **Step 3: Implement pure engine**

`fix()` copies input first, excludes the patch word from the body sum, writes the required big-endian 32-bit patch word, then calls `verify()` and throws if verification is not fully valid.

`verify()` returns each block's observed residue and boolean validity.

- [x] **Step 4: Remove checksum math from `EcuFlasher`**

Delete the old `fixEdc16Checksum` implementation after all callers use `Edc16ChecksumEngine`.

- [x] **Step 5: Run and commit**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
git add app/src/main/java/com/golf5/edc16flasher/firmware app/src/main/java/com/golf5/edc16flasher/protocol/EcuFlasher.kt app/src/test/java/com/golf5/edc16flasher/firmware
git commit -m "refactor: isolate EDC16 firmware profile and checksum engine"
```

---

### Task 4: Make security access explicit and fail closed

**Files:**
- Create: `app/src/main/java/com/golf5/edc16flasher/security/SecurityAccessAlgorithm.kt`
- Create: `app/src/main/java/com/golf5/edc16flasher/security/LegacyBlsSecurityAlgorithm.kt`
- Create: `app/src/main/java/com/golf5/edc16flasher/security/MockSecurityAlgorithm.kt`
- Modify: `app/src/main/java/com/golf5/edc16flasher/protocol/Edc16Security.kt`
- Modify: `app/src/main/java/com/golf5/edc16flasher/protocol/EcuFlasher.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/security/SecurityAccessTest.kt`

**Interfaces:**
- Produces: `SecurityAccessAlgorithm` with verification state.

- [ ] **Step 1: Create the interface exactly**

```kotlin
interface SecurityAccessAlgorithm {
    val id: String
    val verified: Boolean
    fun calculateKey(seed: ByteArray): ByteArray
}
```

- [ ] **Step 2: Move the current formula without claiming validation**

`LegacyBlsSecurityAlgorithm.id = "legacy-bls-formula-v1"` and `verified = false`.

Preserve its current deterministic transform exactly so behavior does not silently change.

- [ ] **Step 3: Add a mock-only verified algorithm**

Use a deliberately simple deterministic emulator transform:

```kotlin
object MockSecurityAlgorithm : SecurityAccessAlgorithm {
    override val id = "mock-xor-a5"
    override val verified = true
    override fun calculateKey(seed: ByteArray): ByteArray {
        require(seed.size == 4)
        return seed.map { ((it.toInt() and 0xFF) xor 0xA5).toByte() }.toByteArray()
    }
}
```

- [ ] **Step 4: Add tests**

Assert the mock vector `12 34 56 78 -> B7 91 F3 DD` and assert `LegacyBlsSecurityAlgorithm.verified == false`.

Do not add fake real-ECU vectors just to turn the flag true.

- [ ] **Step 5: Make `EcuFlasher` receive a security algorithm**

Constructor target:

```kotlin
class EcuFlasher(
    private val protocol: Kwp2000Protocol,
    private val profile: EcuFirmwareProfile,
    private val checksumEngine: Edc16ChecksumEngine,
    private val security: SecurityAccessAlgorithm,
)
```

Physical eligibility is implemented in Task 7; until then do not add any new path that silently ignores `security.verified`.

- [ ] **Step 6: Run and commit**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
git add app/src/main/java/com/golf5/edc16flasher/security app/src/main/java/com/golf5/edc16flasher/protocol/Edc16Security.kt app/src/main/java/com/golf5/edc16flasher/protocol/EcuFlasher.kt app/src/test/java/com/golf5/edc16flasher/security
git commit -m "fix: make ECU security verification explicit"
```

---

### Task 5: Complete the offline EDC16 emulator write state machine

**Files:**
- Modify: `app/src/main/java/com/golf5/edc16flasher/usb/MockEdc16Transport.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/usb/MockEdc16TransportTest.kt`

**Interfaces:**
- Consumes: `KwpTransport`, `MockSecurityAlgorithm`.
- Produces: deterministic in-memory read/write ECU behavior.

- [ ] **Step 1: Write failing tests for stateful write behavior**

Required test names:

```kotlin
@Test fun requestDownloadDoesNotChangeMemoryUntilTransferData()
@Test fun transferDataWritesBytesAtRequestedAddress()
@Test fun wrongSequenceReturnsNegativeResponse()
@Test fun transferExitRejectsIncompleteDownload()
@Test fun completedDownloadCanBeReadBackByteForByte()
@Test fun injectedNrc78EventuallySucceeds()
@Test fun injectedDisconnectAtBlockStopsTransfer()
@Test fun emulatorReportsIsPhysicalFalse()
```

Use a small requested range for focused transport tests; keep one integration test for the full 512 KiB region.

- [ ] **Step 2: Add explicit session fields**

Do not overload upload fields for download. Add separate `UploadState?` and `DownloadState?` data classes with `start`, `size`, `cursor`, `blockSize`, `expectedSequence`.

- [ ] **Step 3: Parse `0x34` request payload**

For this repository's request format, require payload bytes:

`00 <addr24 BE> <size24 BE>`.

Reject ranges outside `[0x180000, 0x200000)` with a valid negative KWP response rather than throwing from the emulator thread.

- [ ] **Step 4: Implement write `0x36`**

When download is active, payload byte 0 is sequence and remaining bytes are data. Require exact expected sequence, require cursor+data not to exceed requested size, copy data into `flashMemory`, then increment sequence modulo 256.

- [ ] **Step 5: Implement strict `0x37` completion**

If transferred bytes do not equal requested size, return a negative response and preserve memory state for inspection. If complete, clear download state and return positive `0x77`.

- [ ] **Step 6: Add test-only helpers**

Expose copies, not mutable internal arrays:

```kotlin
internal fun snapshot(start: Int, size: Int): ByteArray
internal fun setVoltageForTest(voltage: Float)
internal fun configureFaults(faults: MockFaults)
```

- [ ] **Step 7: Run and commit**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
git add app/src/main/java/com/golf5/edc16flasher/usb/MockEdc16Transport.kt app/src/test/java/com/golf5/edc16flasher/usb/MockEdc16TransportTest.kt
git commit -m "feat: complete stateful EDC16 emulator write path"
```

---

### Task 6: Isolate MPPS authentication and reject unknown challenges

**Files:**
- Create: `app/src/main/java/com/golf5/edc16flasher/usb/MppsAuthenticator.kt`
- Modify: `app/src/main/java/com/golf5/edc16flasher/usb/MppsHardwareTransport.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/usb/MppsAuthenticatorTest.kt`

**Interfaces:**
- Produces: `MppsAuthResult.KnownResponse` or `MppsAuthResult.UnknownChallenge`.

- [ ] **Step 1: Write known-vector tests**

```kotlin
@Test fun vector1MatchesCapture() {
    assertArrayEquals(
        hex("65E3DBEE"),
        (auth.responseFor(hex("1EB987D7"), ByteArray(8)) as KnownResponse).bytes,
    )
}

@Test fun vector2MatchesCapture() {
    assertArrayEquals(
        hex("51D6EC90"),
        (auth.responseFor(hex("DB0B83ED"), ByteArray(8)) as KnownResponse).bytes,
    )
}

@Test fun unknownChallengeFailsClosed() {
    assertTrue(auth.responseFor(hex("01020304"), ByteArray(8)) is UnknownChallenge)
}
```

- [ ] **Step 2: Implement only proven behavior**

Do not port the generic `0x78D3035C / 0x3F30029D` fallback into the verified path. Preserve it only in git history for later research.

- [ ] **Step 3: Update handshake**

When authenticator returns `UnknownChallenge`, throw `MppsAuthenticationUnverifiedException` containing the challenge hex and stop initialization of write capability.

Do not substitute a zero key and do not retry with guessed arithmetic.

- [ ] **Step 4: Expose authentication confidence**

`MppsHardwareTransport` must expose a boolean or enum indicating whether the current session's authentication was validated by known behavior. Task 7 consumes this fact.

- [ ] **Step 5: Run and commit**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
git add app/src/main/java/com/golf5/edc16flasher/usb/MppsAuthenticator.kt app/src/main/java/com/golf5/edc16flasher/usb/MppsHardwareTransport.kt app/src/test/java/com/golf5/edc16flasher/usb/MppsAuthenticatorTest.kt
git commit -m "fix: fail closed on unknown MPPS authentication challenge"
```

---

### Task 7: Add typed flash eligibility and full verified transaction

**Files:**
- Create: `app/src/main/java/com/golf5/edc16flasher/flashing/FlashEligibility.kt`
- Create: `app/src/main/java/com/golf5/edc16flasher/flashing/FlashTransaction.kt`
- Modify: `app/src/main/java/com/golf5/edc16flasher/protocol/EcuFlasher.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/flashing/FlashEligibilityTest.kt`
- Test: `app/src/test/java/com/golf5/edc16flasher/flashing/FlashTransactionTest.kt`

**Interfaces:**
- Produces: `FlashEligibility`, `FlashRefusalReason`, `FlashStage`, `FlashResult`.

- [ ] **Step 1: Define refusal reasons as an enum**

Use at least:

```kotlin
enum class FlashRefusalReason {
    NOT_CONNECTED,
    ECU_ID_MISMATCH,
    VOLTAGE_UNAVAILABLE,
    VOLTAGE_TOO_LOW,
    IMAGE_SIZE_INVALID,
    CHECKSUM_INVALID,
    SECURITY_ALGORITHM_UNVERIFIED,
    MPPS_AUTH_UNVERIFIED,
    BACKUP_REQUIRED,
}
```

- [ ] **Step 2: Define immutable preflight input**

```kotlin
data class FlashPreflight(
    val connected: Boolean,
    val physical: Boolean,
    val mpps: Boolean,
    val mppsAuthVerified: Boolean,
    val ecuIdentification: String,
    val voltage: Float?,
    val imageSize: Int,
    val checksumValid: Boolean,
    val securityVerified: Boolean,
    val backupCompleted: Boolean,
    val recoveryMode: Boolean,
)
```

- [ ] **Step 3: Write exhaustive eligibility tests**

One test per refusal reason. Also assert:

- emulator may write with mock verified security without physical MPPS auth;
- physical normal write requires matching ECU ID;
- recovery may ignore ECU ID mismatch but still fails for low voltage/security/checksum/auth/backup.

Use `12.2f` as the minimum physical programming voltage threshold.

- [ ] **Step 4: Define transaction stages and result**

```kotlin
enum class FlashStage {
    PREFLIGHT, BACKUP, PROGRAM_SESSION, SECURITY_ACCESS,
    REQUEST_DOWNLOAD, TRANSFER, TRANSFER_EXIT,
    READ_BACK_VERIFY, RESET, COMPLETE
}

sealed interface FlashResult {
    data class Success(val backupSha256: String, val writtenSha256: String) : FlashResult
    data class Refused(val reasons: Set<FlashRefusalReason>) : FlashResult
    data class Failed(val stage: FlashStage, val message: String) : FlashResult
}
```

- [ ] **Step 5: Write the full emulator integration test before implementation**

Build deterministic 2 MiB input, fix checksum, run flash transaction against `MockEdc16Transport`, then assert:

- backup completed before download starts;
- exactly 512 KiB is written;
- full read-back completes;
- SHA-256 of intended calibration equals SHA-256 of read-back calibration;
- result is `Success` only after read-back;
- emulator snapshot equals intended calibration bytes.

Add a second test that injects one-byte read-back corruption and expects `Failed(READ_BACK_VERIFY, ...)`.

- [ ] **Step 6: Implement backup-first transaction**

The transaction must obtain and hash backup data before first destructive request. Store backup bytes through a callback supplied by `MainActivity`; do not hard-code Android file APIs into the transaction core.

- [ ] **Step 7: Implement post-write verification**

After `RequestTransferExit`, read the whole calibration region again. Compare SHA-256 digests using `MessageDigest.getInstance("SHA-256")`.

Only then perform ECU reset and return `Success`.

Do not clear DTCs before read-back verification. DTC clearing is a separate user action or a best-effort post-success action.

- [ ] **Step 8: Retire boolean flash success**

Replace `EcuFlasher.flashFirmware(): Boolean` and `recoveryFlash(): Boolean` with typed `FlashResult` orchestration or make them thin adapters around `FlashTransaction`. No UI should infer success from lack of exception.

- [ ] **Step 9: Run and commit**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
git add app/src/main/java/com/golf5/edc16flasher/flashing app/src/main/java/com/golf5/edc16flasher/protocol/EcuFlasher.kt app/src/test/java/com/golf5/edc16flasher/flashing
git commit -m "feat: add verified flash transaction state machine"
```

---

### Task 8: Wire capability state into Android UI

**Files:**
- Modify: `app/src/main/java/com/golf5/edc16flasher/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `FlashEligibility`, `FlashResult`.

- [ ] **Step 1: Add a persistent mode banner**

Text states must be exactly recognizable as:

- `EMULATOR`;
- `PHYSICAL / READ ONLY`;
- `PHYSICAL / WRITE ELIGIBLE`;
- `FLASHING - DO NOT DISCONNECT`.

Do not encode eligibility only by color.

- [ ] **Step 2: Derive button state from eligibility**

`btnWrite.isEnabled` and `btnRecovery.isEnabled` must come from calculated eligibility, not only USB connected state.

When disabled, tapping or inspecting the UI must allow the user to see the exact refusal reasons.

- [ ] **Step 3: Physical write confirmation contents**

Before executing a physical write, show:

- parsed ECU ID;
- adapter/transport name;
- current voltage;
- selected file name;
- checksum status;
- backup status;
- mode `NORMAL` or `RECOVERY`.

- [ ] **Step 4: Transaction lock**

While `PHYSICAL_WRITE_ACTIVE`:

- cancel/pause background voltage polling;
- disable all buttons except passive log scrolling;
- do not allow adapter reconnect logic to start another transport transaction.

- [ ] **Step 5: Build and manual emulator smoke test**

Run:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Then install debug APK and verify emulator mode can ID, backup, write and read-back without USB hardware.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/golf5/edc16flasher/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml
git commit -m "feat: gate flashing UI by verified capabilities"
```

---

### Task 9: Harden Termux bridge and keep Python behavior in parity

**Files:**
- Modify: `app/src/main/java/com/golf5/edc16flasher/usb/TermuxBridgeServer.kt`
- Modify: `termux/edc16_flasher.py`
- Create: `termux/test_edc16_flasher.py`

**Interfaces:**
- Bridge remains TCP loopback-only on port `8888`.

- [ ] **Step 1: Add strict hex parser tests**

Python and Kotlin must reject:

- odd length: `ABC`;
- non-hex: `00GG`;
- embedded unsupported prefix combinations.

Accepted inputs include `AABBCC` and normalized space-separated bytes if explicitly supported.

- [ ] **Step 2: Default bridge to read-only mutations disabled**

`WRITE_EE` remains disabled. Gate `WRITE_RAW`, `WRITE_MASKED` and OUT `CONTROL` behind a process-local `developerMode` flag defaulting to false.

Read commands `PING`, `VOLTAGE`, `READ_EE`, `READ_RAW`, diagnostics and ECU ID remain available.

- [ ] **Step 3: Remove duplicated magic constants where practical**

At minimum, Python constants must exactly match Kotlin profile values for image size, calibration range, checksum ranges and residue. Add a Python test asserting the known literal values.

- [ ] **Step 4: Add Python checksum tests**

Create synthetic 2 MiB image, call `fix_edc16_checksum`, independently recompute both block residues and assert `0xD01FE500`.

- [ ] **Step 5: Run and commit**

```bash
python -m unittest termux/test_edc16_flasher.py
./gradlew testDebugUnitTest
./gradlew assembleDebug
git add app/src/main/java/com/golf5/edc16flasher/usb/TermuxBridgeServer.kt termux/edc16_flasher.py termux/test_edc16_flasher.py
git commit -m "fix: harden Termux bridge and checksum parity"
```

---

### Task 10: Add CI and correct repository claims

**Files:**
- Create: `.github/workflows/android-ci.yml`
- Modify: `README.md`

**Interfaces:**
- Produces: repository-wide build/test gate.

- [ ] **Step 1: Add GitHub Actions workflow**

Use Ubuntu, Java 17 and Gradle wrapper. Workflow commands:

```yaml
- name: Kotlin unit tests
  run: ./gradlew testDebugUnitTest
- name: Assemble debug APK
  run: ./gradlew assembleDebug
- name: Python tests
  run: python -m unittest termux/test_edc16_flasher.py
```

Run on both `push` and `pull_request`.

- [ ] **Step 2: Rewrite README status language**

Remove or qualify claims such as `Full MPPS functionality`, `100%` emulator coverage, or hardware-write completion unless covered by tests/evidence.

Add a status table:

| Capability | Status |
|---|---|
| Offline ECU ID | Tested |
| Offline 512 KiB read | Tested |
| Offline 512 KiB write + read-back verify | Tested when Task 7 CI is green |
| KWP frame parser | Tested |
| Checksum profile | Tested for declared profile only |
| Real BLS seed/key | Unverified until real capture vectors exist |
| MPPS unknown challenge | Fail-closed |
| Physical write | Not release-ready until all physical prerequisites are verified |

- [ ] **Step 3: Link the design and plan near top of README**

Add links to:

- `docs/superpowers/specs/2026-09-15-edc16-flasher-hardening-design.md`
- `docs/superpowers/plans/2026-09-15-edc16-flasher-hardening.md`
- `GEMINI.md`

- [ ] **Step 4: Run final local gate**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
python -m unittest termux/test_edc16_flasher.py
git status --short
```

Expected: tests/build PASS and only intentionally modified files staged/untracked before commit.

- [ ] **Step 5: Commit and push**

```bash
git add .github/workflows/android-ci.yml README.md
git commit -m "ci: verify Android flasher and document readiness"
git push origin master
```

---

## Mandatory execution order

Gemini must execute Tasks 1 -> 10 in order. Do not parallelize Tasks 1-7 because later interfaces depend on earlier ones. Task 9 may be done after Task 7, but keeping numeric order reduces drift.

After each task:

1. Run the task-specific tests.
2. Run `./gradlew assembleDebug` whenever Kotlin production code changed.
3. Do not continue on a red test/build.
4. Commit exactly that task's coherent change.
5. Record the commit SHA in the work log.

## Stop conditions requiring evidence instead of guessing

Stop implementation of the affected physical-write capability and leave it fail-closed when any of these occurs:

- a real seed/key vector is unavailable;
- an MPPS challenge is not one of the repository-validated vectors;
- the actual MPPS capture contradicts the documented packet format;
- ECU identification does not contain both required identifiers in parsed payloads;
- checksum residue differs from the declared profile after independent verification;
- read-back SHA-256 differs from intended calibration SHA-256;
- voltage cannot be read or falls below `12.2 V` before destructive programming starts;
- transport disconnects or times out during write.

A stop condition is not permission to invent a fallback. Preserve logs/raw evidence and report the exact missing fact.

## Final acceptance gate

Gemini may mark the hardening plan complete only when all of the following are true:

- `./gradlew testDebugUnitTest` passes.
- `./gradlew assembleDebug` passes.
- `python -m unittest termux/test_edc16_flasher.py` passes.
- Emulator integration test proves backup -> write -> read-back SHA-256 equality.
- Negative emulator test proves read-back mismatch returns failure.
- Physical write eligibility is false with unverified BLS security.
- Physical write eligibility is false for unknown MPPS challenge.
- UI cannot enable physical write merely because USB is connected.
- README accurately labels unverified hardware functions.

Do not weaken a gate merely to make CI green.
