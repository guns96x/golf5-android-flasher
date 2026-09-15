# EDC16 Android Flasher Hardening Design

## Purpose

Turn the current Golf 5 Android flasher from a proof-of-concept into a deterministic, testable implementation where protocol behavior is explicit and hardware write operations fail closed when any required fact is unverified.

The primary supported target remains Bosch EDC16U34, VAG ECU `03G906021QJ`, SW `391847`, using Android USB-OTG with MPPS v18 / compatible K-Line transport. Emulator support is mandatory because all destructive flows must be exercised offline before any physical ECU write.

## Current-state findings that drive this design

1. `Kwp2000Protocol` accepts a response when the expected positive SID appears anywhere in raw bytes. It does not parse and validate a complete ISO 14230 frame before accepting it.
2. NRC `0x78` handling waits for another response, but response framing, echo stripping and checksum validation are not modeled as an explicit parser.
3. `MockEdc16Transport` emulates upload/read, but its `RequestDownload`/`TransferData` path does not mutate `flashMemory`, so it cannot validate a full write/read-back cycle.
4. `EcuFlasher.fixEdc16Checksum()` mutates a 2 MiB image using hard-coded EDC16U34 absolute offsets and an assumed `0xD01FE500` residue. That behavior needs a named profile plus post-fix verification.
5. `Edc16Security.calculateKey()` is deterministic code but has no validated real-ECU test vectors in the repository. It must not be treated as proven only because it compiles.
6. `MppsHardwareTransport.computeChallengeResponse()` receives `secNum` but does not use it. Two challenge/response pairs are hard-coded and an unverified generic formula is used for other challenges.
7. The current flash flow reports success after transfer exit/reset without a read-back comparison of the written calibration region.
8. There is no repository CI proving JVM unit tests and `assembleDebug` on every change.

## Non-negotiable safety model

The application must distinguish four states:

- `OFFLINE_EMULATOR`: all read/write operations allowed against in-memory mock only.
- `PHYSICAL_READ_ONLY`: identification, voltage, diagnostics and backup allowed; flash disabled.
- `PHYSICAL_WRITE_ELIGIBLE`: all write prerequisites verified for the exact ECU/profile.
- `PHYSICAL_WRITE_ACTIVE`: one serialized write transaction is in progress; no background voltage polling or competing transport operation may run.

Physical write eligibility requires all of these conditions at the same time:

- Adapter transport is physical, not mock.
- ECU identity matches both `03G906021QJ` and `391847` after parsed identification responses.
- Battery voltage is available and `>= 12.2 V` immediately before programming.
- Firmware input is exactly 2 MiB.
- Calibration range is exactly `[0x180000, 0x200000)` for this profile.
- Checksum profile is `EDC16U34_03G906021QJ_391847` and post-fix verification passes.
- Security access implementation is marked verified by repository test vectors.
- MPPS hardware authentication implementation is marked verified for the connected adapter path, or a non-MPPS serial path does not depend on MPPS authentication.
- A pre-flash backup of the 512 KiB calibration region has completed successfully in the same application session.

If any condition is false or unknown, the write button remains disabled and the API returns a typed refusal reason. Recovery mode may bypass ECU identification only; it may not bypass voltage, image-size, checksum-profile, transport-integrity, security-access verification, or explicit user confirmation.

## Architecture

### 1. Pure protocol layer

Introduce protocol parsing and framing code that depends only on Kotlin/JVM types. Android USB classes must not be required to unit-test KWP behavior.

Create:

- `protocol/KwpFrame.kt`: immutable parsed frame model.
- `protocol/KwpFrameCodec.kt`: encode request frames and parse response frames.
- `protocol/KwpTransport.kt`: minimal interface with `write`, `read`, `sendFastInit`, `getBatteryVoltage`, `name`, `isPhysical`, `capabilities`.

`Kwp2000Protocol` must consume `KwpTransport`, not `UsbSerialManager` directly. `UsbSerialManager` becomes a transport selector/delegator and implements or exposes the selected `KwpTransport`.

The parser validates:

- short and long ISO 14230 header format;
- declared length versus actual payload;
- target/source addresses for ECU responses;
- 8-bit additive checksum;
- positive SID exactly at the parsed service byte;
- negative response structure `0x7F <requested SID> <NRC>`.

Raw byte scanning for a SID is not permitted after this refactor.

### 2. Deterministic request engine

`Kwp2000Protocol.sendRequest()` becomes a request state machine:

1. Encode request.
2. Write once.
3. Read one or more complete frames until deadline.
4. Discard exact local echo only when it equals the transmitted frame prefix.
5. On parsed NRC `0x78`, keep waiting without retransmitting.
6. On another NRC for the requested SID, throw `KwpNegativeResponseException(serviceId, nrc)`.
7. On a valid positive response, return `KwpFrame`.
8. On malformed frame, record parser error and continue only while deadline remains.
9. On deadline, throw a timeout that includes the last parser error/raw bytes for diagnostics.

### 3. Complete offline ECU emulator

`MockEdc16Transport` becomes a true stateful emulator for the subset used by the app.

Required session state:

- diagnostic session type;
- security seed issued / security unlocked;
- upload active with start, size, cursor and negotiated block size;
- download active with start, size, cursor and expected block sequence;
- reset count;
- configurable voltage;
- fault injection settings.

For `0x34 RequestDownload`, parse start and size, validate bounds, activate download state and return negotiated block size.

For `0x36 TransferData` while download is active, validate sequence, copy payload bytes into `flashMemory` at the current cursor, advance cursor and return `0x76 <sequence>`.

For `0x37`, require transferred byte count to equal requested size before marking the download complete.

Provide test-only accessors that return a copy of the calibration region and fault-injection configuration. No production UI should directly mutate emulator memory.

Fault injection must support at least:

- NRC `0x78` for N responses before success;
- wrong checksum in one response;
- sequence mismatch;
- short read;
- voltage drop below threshold;
- disconnect/timeout at a specified transfer block.

### 4. Firmware profile and checksum engine

Create a profile object `EcuFirmwareProfile` and a single constant profile `EDC16U34_03G906021QJ_391847` containing:

- full image size `0x200000`;
- calibration start `0x180000`;
- calibration size `0x080000`;
- checksum block 1 `[0x180000, 0x1C0000)` with patch word `0x1BFFFC`;
- checksum block 2 `[0x1C0000, 0x1FE000)` with patch word `0x1FDFFC`;
- expected additive 32-bit big-endian residue `0xD01FE500` for each declared block;
- required ECU identifiers `03G906021QJ` and `391847`.

Create a pure `Edc16ChecksumEngine` with separate `fix(image, profile)` and `verify(image, profile)` methods. `fix()` returns a copy; it must not mutate the caller's input. `verify()` returns per-block observed and expected residues.

No other checksum region or checksum algorithm may be inferred automatically.

### 5. Security access verification gate

Replace direct static use of `Edc16Security.calculateKey()` with `SecurityAccessAlgorithm`:

```kotlin
interface SecurityAccessAlgorithm {
    val id: String
    val verified: Boolean
    fun calculateKey(seed: ByteArray): ByteArray
}
```

The existing formula may remain as `LegacyBlsSecurityAlgorithm`, but `verified` must be `false` until repository test vectors sourced from a known-good real ECU/MPPS session are added and documented.

The emulator uses a separate deterministic test algorithm with known vectors so emulator write tests do not depend on an unverified real-car algorithm.

Physical flashing must refuse to start when the selected real security algorithm has `verified == false`.

### 6. MPPS authentication isolation

Move MPPS challenge/response logic from `MppsHardwareTransport` into `MppsAuthenticator`.

Repository-known vectors are:

- challenge `1E B9 87 D7` -> response `65 E3 DB EE`;
- challenge `DB 0B 83 ED` -> response `51 D6 EC 90`.

These vectors prove only those captured cases. Unknown challenges must not use the current generic fallback formula unless an independently validated test vector set demonstrates it. Until then, `MppsAuthenticator` must return an explicit `UnknownChallenge` result and physical write eligibility remains false.

MPPS read-only diagnostics may still be used after hardware initialization if the transport can establish a safe session, but write capability must advertise false whenever authentication confidence is insufficient.

### 7. Flash transaction state machine

Replace the current linear `flashFirmware()` boolean contract with a typed result and explicit transaction stages:

`PREFLIGHT -> BACKUP -> PROGRAM_SESSION -> SECURITY_ACCESS -> REQUEST_DOWNLOAD -> TRANSFER -> TRANSFER_EXIT -> READ_BACK_VERIFY -> RESET -> COMPLETE`.

Failure at any stage returns the failed stage and reason. The application must never print a success message before read-back verification passes.

Read-back verification minimum requirement:

- read the entire 512 KiB calibration region after write;
- compare SHA-256 of intended post-checksum calibration bytes against SHA-256 of read-back bytes;
- on mismatch, report failure and do not clear DTCs as a substitute for success.

A completed pre-flash backup is stored before the first destructive request and its SHA-256 is logged.

### 8. UI capability gating

The UI must display a persistent mode banner:

- `EMULATOR`;
- `PHYSICAL / READ ONLY`;
- `PHYSICAL / WRITE ELIGIBLE`;
- `FLASHING - DO NOT DISCONNECT`.

Write and recovery buttons derive enabled state from a `FlashEligibility` model, not from `usbSerialManager.isConnected` alone.

Before a physical write, confirmation dialog must show ECU ID, adapter, voltage, selected file name, checksum verification result, and backup status.

### 9. Termux bridge

Keep the server bound to `127.0.0.1` only.

Default bridge mode is read-only. Raw mutating commands (`WRITE_RAW`, arbitrary OUT `CONTROL`, future EEPROM writes) require an in-process developer-mode flag that is false in normal builds.

`parseHex()` must reject odd-length input and non-hex characters with a typed error instead of silently producing invalid bytes.

Termux CLI and Android app must share the same documented firmware profile constants. If code sharing is impractical, parity tests must assert equal constants and checksum outputs.

### 10. CI and release gate

Add GitHub Actions that runs on push and pull request:

- `./gradlew testDebugUnitTest`;
- `./gradlew assembleDebug`;
- Python unit tests for `termux/` checksum and frame helpers if those helpers remain independent.

No release readiness claim is permitted until CI passes and the emulator integration test completes a full backup -> write -> read-back verify cycle.

## Required test matrix

At minimum, automated tests must cover:

- KWP short frame encode/parse;
- KWP long frame encode/parse;
- checksum rejection;
- exact local echo removal;
- NRC `0x78` followed by success;
- non-`0x78` NRC failure;
- request timeout;
- emulator ECU identification;
- emulator security access success and rejection;
- 512 KiB emulator upload;
- 512 KiB emulator download mutating flash;
- transfer sequence mismatch;
- interrupted transfer;
- checksum fix + verify on synthetic 2 MiB image;
- wrong image size rejection;
- ECU ID mismatch blocking physical write;
- low voltage blocking physical write;
- unverified security algorithm blocking physical write;
- unknown MPPS challenge blocking physical write;
- successful emulator full flash transaction with post-write SHA-256 equality;
- read-back mismatch causing transaction failure.

## Out of scope for this hardening pass

- Adding support for new ECU families such as EDC17.
- Guessing additional MPPS proprietary commands.
- General CAN/TP2.0 flashing support.
- Automatic checksum discovery.
- Automatic seed/key reverse engineering.
- Changing repository visibility or rewriting Git history to remove existing binaries.

Those items may be handled only after the EDC16/K-Line path above is deterministic and green in CI.

## Definition of done

This design is complete only when:

1. All required tests are present and passing in CI.
2. Full emulator backup -> write -> read-back verify succeeds.
3. Every physical flash refusal produces a specific eligibility reason.
4. Physical write cannot start with an unverified security algorithm or unknown MPPS authentication challenge.
5. Success is reported only after SHA-256 read-back comparison passes.
6. README no longer describes unverified functions as fully proven hardware behavior.
