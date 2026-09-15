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
