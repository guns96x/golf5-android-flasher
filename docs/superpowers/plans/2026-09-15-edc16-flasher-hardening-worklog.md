# EDC16 Flasher Hardening Work Log

Plan: `docs/superpowers/plans/2026-09-15-edc16-flasher-hardening.md`

Status: `COMPLETE (emulator)` — physical write `BLOCKED_EVIDENCE` (see end of task records)

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
Commit: 00e563a48003c6794915dc1b74a4279254d0ee3b
Tests:
- ./gradlew testDebugUnitTest -> PASS (32 tests)
- ./gradlew assembleDebug -> PASS
Notes:
- Created MppsAuthenticator object with only 2 proven captured vectors (1EB987D7->65E3DBEE, DB0B83ED->51D6EC90).
- Generic fallback formula (0x78D3035C/0x3F30029D) removed from verified path; preserved in git history only.
- MppsHardwareTransport.computeChallengeResponse now delegates to MppsAuthenticator; throws MppsAuthenticationUnverifiedException on unknown challenges.
- Added mppsAuthVerified: Boolean property on MppsHardwareTransport; reset to false on close().

## Task 7: Add typed flash eligibility and full verified transaction
Commit: 6b572cbcb6a36665fdaf043f575c1814f2227990
Tests:
- ./gradlew testDebugUnitTest -> PASS (all tests)
- ./gradlew assembleDebug -> PASS
Notes:
- Created FlashEligibility.kt: pure evaluateEligibility() with all 9 refusal reasons. Physical gates (voltage, MPPS auth, backup, ECU ID) skipped for emulator path. Recovery mode bypasses ECU ID mismatch only; all other gates enforced.
- Created FlashTransaction.kt: FlashProtocol interface (no Android deps), backup-first orchestration, SHA-256 read-back verification before ECU reset, typed FlashResult (Success/Refused/Failed with stage).
- Fixed test compile error: FakeProtocol and transferData must be open to allow anonymous subclassing in backupIsCalledBeforeFirstTransfer test.


## Task 7 follow-up: route all writes through FlashTransaction
Commit: b0fc49ccd1cdbdbba8911209312f9568b18d04a6
Tests:
- ./gradlew testDebugUnitTest -> PASS (75 tests, 0 failures)
- ./gradlew assembleDebug -> PASS
Notes:
- Acceptance gap fixed: the Task 7 "emulator integration test" used a FakeProtocol; added EmulatorFlashIntegrationTest running FlashTransaction -> FlashProtocolAdapter -> Kwp2000Protocol -> MockEdc16Transport.
- Bug: FlashTransaction wrapped block sequence 255 -> 1, emulator/old path 0xFF -> 0x00; the real stack failed at block 256. Now 0xFF -> 0x00. The real ECU convention is not hardware-verified.
- Deviation from spec stage order: PROGRAM_SESSION and SECURITY_ACCESS run before BACKUP because upload needs an unlocked session; backup still completes and is persisted before RequestDownload.
- Removed EcuFlasher.flashFirmware/recoveryFlash: ECU ID check used `!a && !b` (either identifier passed), no security-verified gate; recovery had no read-back.
- Emulator now rejects 0x34/0x35 without security access (NRC 0x33).

## Task 8: Wire capability state into Android UI
Commit: 0a897ddeb959db16e7ff92ad31c83f94c0a00283
Tests:
- ./gradlew testDebugUnitTest -> PASS
- ./gradlew assembleDebug -> PASS
Notes:
- Bug: setControlsEnabled(true) after any read re-enabled WRITE/RECOVERY regardless of eligibility. Replaced by refreshCapabilityUi().
- Fifth banner text `NO TRANSPORT / WRITE DISABLED` added for the disconnected state (previously showed EMULATOR).
- Manual emulator smoke test on a device NOT run in this session (no device/emulator available); covered by JVM integration test only.

## Task 9: Harden Termux bridge and keep Python behavior in parity
Commit: 5f052c98907a6fbddb8419240a777d983f2a752f
Tests:
- python -m unittest termux/test_edc16_flasher.py -> PASS (10 tests)
- ./gradlew testDebugUnitTest -> PASS
- ./gradlew assembleDebug -> PASS
Notes:
- Besides WRITE_RAW/WRITE_MASKED/OUT CONTROL, link-mutating SET_BAUD/SET_DTR/SET_RTS/FAST_INIT/CAN_*/REINIT also require developer mode.
- Python send_request still scans raw bytes for SIDs (read/ID path only); write path is unreachable while gates are false.

## Task 10: Add CI and correct repository claims
Commit: cdbf0703c53a1ad0cf980908ef1614d8797cc5c4
Tests:
- ./gradlew testDebugUnitTest -> PASS
- ./gradlew assembleDebug -> PASS
- python -m unittest termux/test_edc16_flasher.py -> PASS
Notes:
- gradlew was mode 100644 in git; set to 100755.
- README MD5 for *_dpf_egr_off.bin was wrong (claimed d688cf73..., actual 25b08285...). SHA-256 48c8f368... matches.
- *_dpf_egr_off.bin fails the declared checksum profile; it differs from *_CS_OK.bin only at 0x1BFFFC..0x1BFFFF and 0x1FDFFC..0x1FDFFF; after fix it equals *_CS_OK.bin byte for byte.
- CI run result: pending first push.

## Standalone app (owner request: no Termux)
Commit: 0ceb929f080b4eb288694356f2a373f157bde39a
Tests:
- ./gradlew testDebugUnitTest -> PASS (68 tests, 0 failures)
- ./gradlew assembleDebug -> PASS
Notes:
- Supersedes Task 9: Termux bridge, Python CLI and their tests removed entirely; INTERNET permission and exported DIAGNOSTIC broadcast removed.
- Python profile-parity test no longer applies (single Kotlin implementation).

## Physical write
Status: BLOCKED_EVIDENCE
Missing fact: real EDC16U34 03G906021QJ/391847 seed -> key vectors with provenance
Capability remains fail-closed: physical write and recovery (SECURITY_ALGORITHM_UNVERIFIED)
Observed evidence:
- none committed; LegacyBlsSecurityAlgorithm.verified = false
Next permitted action:
- collect the missing evidence; do not guess an algorithm or protocol constant
- also unverified on hardware: TransferData block-sequence wrap and 128-byte download block size

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
