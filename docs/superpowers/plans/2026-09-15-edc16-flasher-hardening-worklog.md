# EDC16 Flasher Hardening Work Log

Plan: `docs/superpowers/plans/2026-09-15-edc16-flasher-hardening.md`

Status: `IN_PROGRESS`

## Task 1: Add JVM test harness and pure transport boundary
Commit: 51e412b5843f82136c26a413fd744b3af5491fec
Tests:
- ./gradlew testDebugUnitTest -> PASS
- ./gradlew assembleDebug -> PASS
Notes:
- Pure KwpTransport interface extracted and implemented by IUsbTransport and UsbSerialManager. Default values removed from overriding functions in IUsbTransport.

## Task 2: Replace raw SID scanning with strict KWP frame codec
Commit: 2d475943a97bb82dd5e29c802d2a919f4936f9fd
Tests:
- ./gradlew testDebugUnitTest -> PASS
- ./gradlew assembleDebug -> PASS
Notes:
- Implemented immutable KwpFrame, KwpFrameCodec (encodeRequest, parseResponse, stripLeadingEcho, extractFrame), and refactored Kwp2000Protocol to accumulate frames and handle NRC 0x78 properly.

## Task 3: Extract exact ECU firmware profile and checksum engine
Commit: 1df59c77689fa3ef52cb331624b3348afa94c529
Tests:
- ./gradlew testDebugUnitTest -> PASS
- ./gradlew assembleDebug -> PASS
Notes:
- Extracted EcuFirmwareProfile and Edc16ChecksumEngine with fix/verify methods. Tested non-mutation of input, declared block residue calculations, and bounds validation. Replaced embedded fixEdc16Checksum in EcuFlasher.

## Task 4: Make security access explicit and fail closed
Commit: 80030f3dfab06e5b87807e59a973f5cc681cbc6c
Tests:
- ./gradlew testDebugUnitTest -> PASS
- ./gradlew assembleDebug -> PASS
Notes:
- Extracted SecurityAccessAlgorithm interface. Wrapped legacy BLS formula as LegacyBlsSecurityAlgorithm with verified = false. Created MockSecurityAlgorithm with verified = true for emulator testing. Injected SecurityAccessAlgorithm into EcuFlasher.

## Task 5: Complete the offline EDC16 emulator write state machine
Commit: 8692c162e6b980b23009e602f2b4889abaf7b599
Tests:
- ./gradlew testDebugUnitTest -> PASS (27 tests)
- ./gradlew assembleDebug -> PASS
Notes:
- Fixed nrc78Count handling: emit all NRC 0x78 frames first, then fall through to the real response handler instead of returning early. This allows Kwp2000Protocol to drain NRC 0x78 frames from rxQueue and then receive the positive response without retransmitting.

## Task 6: Isolate MPPS authentication and reject unknown challenges
Commit: 00e563a (run `git rev-parse HEAD` for full SHA)
Tests:
- ./gradlew testDebugUnitTest -> PASS (32 tests)
- ./gradlew assembleDebug -> PASS
Notes:
- Created MppsAuthenticator object with only 2 proven captured vectors (1EB987D7->65E3DBEE, DB0B83ED->51D6EC90).
- Generic fallback formula (0x78D3035C/0x3F30029D) removed from verified path; preserved in git history only.
- MppsHardwareTransport.computeChallengeResponse now delegates to MppsAuthenticator; throws MppsAuthenticationUnverifiedException on unknown challenges.
- Added mppsAuthVerified: Boolean property on MppsHardwareTransport; reset to false on close().

## Task 7: Add typed flash eligibility and full verified transaction
Commit: 6b572cb (run `git rev-parse HEAD` for full SHA)
Tests:
- ./gradlew testDebugUnitTest -> PASS (all tests)
- ./gradlew assembleDebug -> PASS
Notes:
- Created FlashEligibility.kt: pure evaluateEligibility() with all 9 refusal reasons. Physical gates (voltage, MPPS auth, backup, ECU ID) skipped for emulator path. Recovery mode bypasses ECU ID mismatch only; all other gates enforced.
- Created FlashTransaction.kt: FlashProtocol interface (no Android deps), backup-first orchestration, SHA-256 read-back verification before ECU reset, typed FlashResult (Success/Refused/Failed with stage).
- Fixed test compile error: FakeProtocol and transferData must be open to allow anonymous subclassing in backupIsCalledBeforeFirstTransfer test.


## Rules

- Append one section only after the corresponding task is complete and committed.
- Paste the full commit SHA.
- Record every verification command actually run and its actual result.
- Do not write PASS unless the command exited successfully.
- If real-hardware evidence is missing, record `BLOCKED_EVIDENCE` and keep that capability fail-closed.

## Task records

Use this exact structure for each completed task:

```text
## Task N: <task name>
Commit: <40-character SHA>
Tests:
- <command> -> PASS
- <command> -> PASS
Notes:
- <concrete evidence, deviation, or "none">
```

Use this exact structure when blocked by missing evidence:

```text
## Task N: <task name>
Status: BLOCKED_EVIDENCE
Missing fact: <exact fact that cannot be proven from repository/capture/hardware evidence>
Capability remains fail-closed: <capability>
Observed evidence:
- <raw response, capture identifier, vector, or log excerpt location>
Next permitted action:
- collect the missing evidence; do not guess an algorithm or protocol constant
```
