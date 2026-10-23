# Contributing

## Setup

JDK 17 and the Android SDK (API 36). Point Gradle at the SDK with `ANDROID_HOME` or a
`local.properties` file containing `sdk.dir=…` (that file is machine-specific and ignored by git).

## Build and test

```sh
./gradlew test                       # rules, tables and pixels on the JVM (Robolectric)
./gradlew :hotlayout:apiCheck        # the public API against hotlayout/api/hotlayout.api
./gradlew :hotlayout:apiDump         # after an intended change of the public API
./gradlew :demo:assembleDebug        # the demo app
```

The instrumented tests in `demo/src/androidTest/` need a device or emulator and are run separately
with `./gradlew :demo:connectedDebugAndroidTest`.

The public API is the package `com.hotlayout` and nothing else. A change to it must be intended:
update `hotlayout/api/hotlayout.api` with `apiDump` and say why in the pull request.

## The contract snapshot

`hotlayout/src/test/resources/contract/` holds the JSON artifacts the reader implements
(`screen-schema.json`, `theme.json`, `data-contract.json`, `icons.json`, `placements.json`) and the fixtures the tests read:
sample documents and the case tables shared by every HotLayout reader. The icon set and the built-in
theme the library ships with, and the fixtures of the demo, are copies of the same files.

It is a snapshot. The contract is maintained outside this repository, so do not edit these files here:
a change to the contract arrives as a new snapshot together with the reader code that implements it.
`scripts/sync-contract.sh <path-to-contract>` copies a snapshot in; the tests compare every shipped copy
with the artifacts, and `TableCoverageTest` fails when a table has a group that is not run.

## Pull requests

- Keep `./gradlew test` and `:hotlayout:apiCheck` green. Do not weaken an assertion to make a test pass.
- Documents and themes come from the network and are untrusted input. A piece of the wrong shape is
  skipped and reported, never a crash; see [docs/security.md](docs/security.md).
- The library has no dependencies besides AndroidX and Compose. Please do not add one without
  discussing it first.
- New code and tests are written in English. Do not commit machine paths, keys or signing material.
