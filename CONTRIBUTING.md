# Contributing to Carlink

Thanks for helping. This page is the standard every change meets: one gate, the same for you, for CI
and for the maintainer, and tests written the same way throughout.

## Before you start

- **JDK 21.** The Gradle wrapper provisions the daemon's JVM (JetBrains 21,
  `gradle/gradle-daemon-jvm.properties`); a JDK 21 on the path bootstraps it.
- **Android SDK** with `platforms;android-37.0`, `platforms;android-36` and `build-tools;37.0.0`.
- **Python 3**, for the protocol check.
- **Network on the first test run.** Robolectric downloads its Android runtime jars once and caches
  them.

Build output goes to `~/.cache/gradle-builds/carlink`, not into the source tree. Pass
`-Pcarlink.buildRoot=/some/path` if `~/.cache` isn't writable.

## The gate

Every commit passes the gate, and every push and pull request runs it in CI
(`.github/workflows/gate.yml`). Run it yourself before you push:

```sh
python3 tools/proto_check.py
./gradlew :app:assembleDebug :bridge:assembleDebug :app:ktlintCheck :app:detekt :app:testSideloadDebugUnitTest
```

It builds the app and the USB bridge, then runs ktlint, detekt and the unit tests. A pull request
merges only with the gate green.

- **ktlint** follows `.editorconfig` (lines up to 180). Fix what it reports in the files you changed.
  Don't reformat files you didn't touch: some are verbatim ports from Carlink2, excluded in
  `.editorconfig` on purpose.
- **detekt** runs `detekt.yml` against `detekt-baseline.xml`, so only new findings fail. Fix a
  finding rather than adding it to the baseline. An `@Suppress` needs a comment saying why (see
  `StockConversion` and `BulkOnlyStorage` for the pattern).
- **Unit tests** must all pass. There is no "flaky, re-run it" category: if a test is timing
  sensitive, fix the test.

## Writing tests

Tests live in `app/src/test/kotlin`, next to the package they test (`com.carlink.convert` ->
`app/src/test/kotlin/com/carlink/convert`). JUnit 4, with Robolectric where Android is involved.

**What needs a test**

- A bug fix comes with a test that fails without the fix. `PaneSwipeBandTest` and
  `PaneDeckReshuffleTest` are examples: each reproduced a bug from the vehicle before it was fixed.
- New logic comes with tests for each way it can end, not only the happy path. `StockConversionTest`
  covers every phase of the conversion, and `BulkOnlyStorageTest` every recovery path.
- A change to how the app reads something from the adapter or a file (MGMT_INFO, OCBM_RESULT.txt,
  a FAT32 volume) comes with a parsing test on real examples, including the malformed and the
  missing.

**How tests are written**

- **Names say what happens, as a sentence**, in backticks:
  `` `a stick that isn't FAT32 is turned away, and read again when it is put back` ``. The JVM
  rejects some characters in method names: no `:` `.` `/` `[` `]` `<` `>`. Write
  `2025_10_15_1127`, not `2025.10.15.1127`.
- **A one-line KDoc on the class** says what the file tests.
- **Robolectric** (`@RunWith(RobolectricTestRunner::class)`) for anything that reaches `android.*`,
  including the app's logger (`android.util.Log`). Plain JUnit for pure Kotlin. `org.json` is real
  in unit tests, so JSON parsing needs no Robolectric.
- **No mocking library.** Write a small fake of the interface instead: `FakeTransport` (an
  in-memory adapter link, in `ocbm/Transport.kt`), `FakeStickTransport` over `MemoryStick` (a USB
  stick that is as strict as the emulator's), `FakeUsb`, `FakeBox`, `FakeBridge`. Keep a fake
  private to its test unless several tests share it, and then put it in a fixtures file
  (`StickFixtures.kt`).
- **Fixtures copy the real thing.** Reports, frames and protocol bytes are copied from what the
  adapter or the installer produces, with their source named (`Reports` in `StickFixtures.kt` names
  the enigy/ocbm commit its texts come from).
- **Time is injected, not slept away.** Classes with waits take a `Timing` data class; tests pass
  short values and poll for the state with a `waitUntil`/`awaitPhase` helper. They don't sleep a
  fixed time and hope. A test may sleep only to show that something did *not* happen.
- **Compose UI** is tested under Robolectric with `createComposeRule()` (see `PaneSwipeBandTest`).
- **Regexes run on Android's ICU engine**, which rejects some patterns the desktop JVM accepts, so a
  regex can pass every unit test and still crash on the head unit. A character class that opens with
  `[:` did exactly that in v16; `AndroidRegexTest` scans the app and bridge source for that case.
  Write `[-:]`, not `[:-]`.
- **Tests don't depend on order, the network, the clock or the machine.** Each builds its own state
  and cancels its own coroutine scope in `@After`.

## Making a change

1. Branch from `main`, one topic per branch.
2. Keep commits focused, and explain in the message why the change is made. The diff already shows
   what changed.
3. Run the gate, then open a pull request against `main`.

**Leave these to the maintainer:**

- **Versions and releases.** Don't change `appVersionCode` in `app/build.gradle.kts` and don't push
  `v*` tags: a tag push uploads to Google Play.
- **The package id.** `com.enigy.carlink3` never changes; it is the Play listing and the id the
  bridge answers.
- **New dependencies.** Ask in an issue first.

## Areas with extra rules

- **The OCBM protocol.** `app/src/main/kotlin/com/carlink/ocbm/OcbmProto.kt` is a hand copy of the
  box's constant table, whose reference copy is `proto/ocbm-proto.lib.rs`. Change either only
  together with the box side, and run `tools/proto_check.py`. A value that disagrees is a wire bug.
  `proto/README.md` says how to refresh the copy.
- **The USB bridge** (`bridge/`, `bridge-api/`) is sideloaded by hand, so any change there means
  users install a new bridge, and the next release rebuilds and re-signs it. Add AIDL methods at the
  end of `ICarlinkBridge.aidl` only, because the two apps update independently. Bump
  `BridgeContract.API_VERSION` and the bridge's `versionCode`/`versionName`, and keep the app
  working with the oldest bridge `BridgeContract.MIN_API_VERSION` allows.
- **Anything that changes an adapter's flash** (the stock conversion, the one-file install, the way
  back) is tested on a bench adapter before a vehicle. Changes there need a test plan in the pull
  request.

## Logs, keys and private data

- Never commit `keystore.properties`, keystores other than `keystores/debug.p12`, or anything taken
  from an adapter. An adapter's flash backups, `identity.tgz` and stick dumps hold its Wi-Fi password
  and Bluetooth keys.
- Every log line written to storage goes through `LogRedactor.line`, which shortens MAC addresses
  and hides the phone's name. Other personal data is redacted where it is logged: callers through
  `CallState.logLabel`, roads and instructions through `LogRedactor.place`. Do the same for anything
  personal you add to a log line.
- Attach logs to issues only after reading them for anything private.
