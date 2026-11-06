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
./gradlew :demo:recordRoborazziDebug # frames of fixtures into demo/frames/
```

The instrumented tests in `demo/src/androidTest/` need a device or emulator and are run separately
with `./gradlew :demo:connectedDebugAndroidTest`.

The public API is the package `com.hotlayout` and nothing else. A change to it must be intended:
update `hotlayout/api/hotlayout.api` with `apiDump` and say why in the pull request.

## Shared case tables

The rules of the contract are checked against the case tables the HotLayout readers share
(`contract/fixtures/*/*-cases.json`). `./gradlew test` runs every group of every table, except the groups that
are verdicts of the builder's validator (which a reader does not have). `TableCoverageTest` fails when a table
has a group that is neither run nor named as a validator verdict, so a new group in the contract cannot be
ignored silently. A few rows where the platform's CLDR data writes other characters than the table are listed by
name in [docs/platform-notes.md](docs/platform-notes.md).

The tests also compare every shipped copy of contract data (built-in theme, icon set, versions, action forms,
widget list) with the artifacts, draw every fixture screen, and check the wiring of each capability through the
real screen.

## Running the demo

```sh
adb shell am start -n com.hotlayout.demo/.MainActivity \
  --es fixture rewards-showcase-screens --es screen home --es locale en
```

Without extras the demo opens the Obsidian showcase; `--es fixture list` lists everything. The three showcase
apps of the product site (`showcase-obsidian`, `showcase-sunny`, `showcase-orbit`, the last one takes
`--es scenario default|newMember|afterPayment`) live in `demo/src/main/assets/showcase/`: the demo draws edge to
edge, resolves their site-relative pictures against `https://hotlayout.com` through its own `ImagePolicy`, and
bundles their fonts. `demo/frames/` holds frames of the fixtures drawn by this reader, for comparing the readers
side by side; `./gradlew test` draws them again without writing.

## Publishing

`./gradlew :hotlayout:publishToMavenLocal` builds the artifact (`com.hotlayout:hotlayout-android`) with its
sources and javadoc jars. Signing uses the key given by the Gradle properties `signingInMemoryKey` and
`signingInMemoryKeyPassword`; without them nothing is signed. `scripts/publish-central.sh` builds the signed
release and uploads it to the Central Portal, where the deployment waits to be published by hand.

To work on the library from another project, include its module from a clone of this repository:

```kotlin
include(":hotlayout")
project(":hotlayout").projectDir = file("../hotlayout-android/hotlayout")
```

and depend on it with `implementation(project(":hotlayout"))`, or depend on the artifact from `mavenLocal()`.

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

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).


- Keep `./gradlew test` and `:hotlayout:apiCheck` green. Do not weaken an assertion to make a test pass.
- Documents and themes come from the network and are untrusted input. A piece of the wrong shape is
  skipped and reported, never a crash; see [docs/security.md](docs/security.md).
- The library has no dependencies besides AndroidX and Compose. Please do not add one without
  discussing it first.
- New code and tests are written in English. Do not commit machine paths, keys or signing material.
