# Gemini Execution Contract — golf5-android-flasher

You are an implementation agent for this repository. Do not redesign the project from scratch and do not invent undocumented ECU/MPPS behavior.

## Required reading before editing code

Read these files completely, in this order:

1. `docs/superpowers/specs/2026-09-15-edc16-flasher-hardening-design.md`
2. `docs/superpowers/plans/2026-09-15-edc16-flasher-hardening.md`
3. `README.md`
4. The current production files named in the task you are executing.
5. The current test files named in that task, if they already exist.

The design document defines behavior. The implementation plan defines exact execution order and file boundaries. If current code conflicts with the design, follow the design unless real hardware evidence proves the design wrong.

## Execution mode

Execute `docs/superpowers/plans/2026-09-15-edc16-flasher-hardening.md` strictly from Task 1 through Task 10.

For every task:

1. Inspect only the files needed for that task plus direct dependencies.
2. Write the failing test first when the plan requires a test.
3. Run the exact test command and confirm the intended failure.
4. Implement the smallest production change that satisfies the task.
5. Run the task tests again.
6. Run `./gradlew assembleDebug` whenever Kotlin production code changed.
7. Stop on any failing test/build; fix that task before proceeding.
8. Commit the completed task with the commit message specified in the plan.
9. Record the commit SHA and commands/results in the work log.
10. Only then move to the next task.

Do not combine Tasks 1-7 into one large refactor.

## Zero-invention rule

The following are evidence-controlled and must never be guessed:

- VAG/Bosch real ECU seed/key behavior;
- MPPS v18 proprietary challenge/response behavior;
- unknown MPPS commands;
- checksum regions outside the documented profile;
- CAN or TP2.0 flashing behavior;
- recovery behavior that bypasses documented safety gates.

When evidence is insufficient, keep the affected physical capability disabled and report the missing evidence.

A compilation-successful formula is not proof of real ECU behavior.

## Known facts you may rely on

Primary profile:

- ECU: Bosch EDC16U34
- VAG HW/part identity: `03G906021QJ`
- SW: `391847`
- full image size: `0x200000`
- calibration region: `0x180000..0x1FFFFF` (`0x080000` bytes)
- checksum block 1: `[0x180000, 0x1C0000)`, patch word `0x1BFFFC`
- checksum block 2: `[0x1C0000, 0x1FE000)`, patch word `0x1FDFFC`
- expected additive 32-bit big-endian residue for each declared block: `0xD01FE500`

Repository-known MPPS challenge/response capture vectors:

- `1E B9 87 D7` -> `65 E3 DB EE`
- `DB 0B 83 ED` -> `51 D6 EC 90`

These two vectors do not prove a generic MPPS authentication formula.

The current legacy BLS seed/key formula is unverified until real known-good vectors are committed with provenance.

## Mandatory physical-write gates

A physical flash must not start unless the implementation proves all applicable conditions:

- connected physical transport;
- parsed ECU identification matches both `03G906021QJ` and `391847`, except ID may be bypassed only in explicit recovery mode;
- voltage is readable and at least `12.2 V` immediately before destructive programming;
- selected image is exactly 2 MiB;
- checksum is fixed and independently verified against the declared profile;
- security algorithm is explicitly marked verified;
- MPPS authentication is explicitly verified for the current session when MPPS authentication applies;
- a same-session calibration backup completed before the destructive request;
- no competing transport transaction is active.

Recovery mode does not bypass voltage, image, checksum, transport-integrity, security-verification, authentication-verification or backup requirements.

## Success definition

A transfer finishing without an exception is not a successful flash.

Physical or emulator write success requires:

1. preflight passed;
2. backup completed;
3. programming/security/download stages completed;
4. all calibration bytes transferred;
5. transfer exit completed;
6. complete 512 KiB calibration read-back completed;
7. SHA-256 of intended post-checksum calibration equals SHA-256 of read-back calibration;
8. only then reset and return success.

Do not clear DTCs before read-back verification as a substitute for verification.

## Emulator requirement

`MockEdc16Transport` is the mandatory destructive-flow test target.

It must actually mutate its in-memory flash on RequestDownload/TransferData and must support a complete backup -> write -> read-back verify integration test.

The emulator must support fault injection required by the design so failure paths are tested without a car.

Do not mark the emulator complete while RequestDownload only returns a positive response without modifying flash memory.

## KWP parser requirement

Do not search raw transport bytes for a positive SID or `0x7F` anywhere in the buffer.

Parse a complete ISO 14230 frame, validate declared length, source/target, and additive checksum, then inspect the parsed service byte and payload.

NRC `0x78` means wait for the final response without retransmitting the original request.

## MPPS requirement

Move authentication logic behind `MppsAuthenticator` as specified by the plan.

For unknown challenges, fail closed. Do not use the current generic arithmetic fallback unless future repository evidence includes independent known-good vectors that validate it.

Do not change the two known capture vectors.

## Termux bridge requirement

Keep TCP bind address exactly loopback-only (`127.0.0.1`).

Normal mode is read-only for raw mutation commands. Developer mode must default to false.

Do not expose EEPROM writes by default.

## Work log

Maintain `docs/superpowers/plans/2026-09-15-edc16-flasher-hardening-worklog.md` while executing the plan.

After each completed task append:

```text
Task N: <name>
Commit: <full SHA>
Tests:
- <command> -> PASS
- <command> -> PASS
Notes:
- <only concrete evidence or deviations>
```

If a task is blocked by missing real-hardware evidence, write:

```text
BLOCKED_EVIDENCE: <exact missing fact>
Capability remains fail-closed: <capability>
Observed evidence: <capture/log/vector/response>
```

Do not replace missing evidence with a guessed implementation.

## Final commands

Before claiming completion run exactly:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
python -m unittest termux/test_edc16_flasher.py
git status --short
```

All test/build commands must pass. `git status --short` must contain no unintended source changes.

Then review the acceptance gate at the bottom of the implementation plan item by item. Do not weaken a gate to finish the task.
