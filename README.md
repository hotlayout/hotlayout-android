# HotLayout for Android

A Kotlin library that draws screens published by the [HotLayout](https://hotlayout.com) builder:
a JSON document, a theme and your data become a native Jetpack Compose screen.

It is a reader of the same contract as the React, Flutter and iOS readers and is measured by the
same shared case tables.

**Status: foundation.** The rules of the contract (versions, data, conditions, text, numbers,
dates, plurals, locale, actions and navigation) are in place and pass the shared tables. The basic
widgets are drawn; the others draw their children for now.

## Requirements

- Android 8.0 (API 26) and later, Jetpack Compose
- No third-party dependencies besides Compose

## Use

```kotlin
val document = loadDocument(jsonText)                       // tolerant read + version gate
val theme = HotlayoutThemeSet.read(themeText) ?: HotlayoutThemeSet.builtIn

HotlayoutScreen(
    document = document,
    theme = theme,
    data = dataScopeOf("user.name" to "Maya", "user.points" to 1850),
    locale = Locale.getDefault().toLanguageTag(),           // the device locale, as is
    entry = "promo",                                        // a named entry point, optional
    fonts = HotlayoutFonts { name -> if (name == "Inter") inter else null },
    onAction = { action, source ->
        // The reader opens nothing itself. A standard action arrives checked:
        runStandardAction(context, action)
    },
    onTrack = { event, source -> analytics.log(event.event, event.params) },
    onScreenChange = { shown -> Log.i("screen", "${shown.screenId} ${shown.cause}") },
)
```

Separate from the view: `checkDocumentVersion` (verdicts `Ok`, `NewerMinor`, `NewerMajor`,
`OlderMajor`, `Unparsable`), `loadDocument`, `resolveLocale`.

- **Documents and themes are untrusted input.** A piece of the wrong shape is skipped and reported
  (`onReport`), never a crash. A tree deeper than 32 levels is refused.
- **Images** load only over `https:` by default; pass an `ImagePolicy` to decide yourself.
- **Navigation** inside a document (`navigate`, `navigate.back`, `navigate.section`) is done by the
  reader; `openUrl`, `deeplink`, `close` and actions of your app go to `onAction`.
- **Fonts** are yours: the theme names a family, `HotlayoutFonts` gives the `FontFamily`.

## What it does today

| Area | State |
|---|---|
| Document model, versions, compatibility policy | done |
| Theme, tokens, own values, node style and states (`styleWhen`) | done |
| Data layers, record scope, live record, conditions, list filter | done |
| Text templates, number, currency, money look, compact numbers, dates, date parts, countdown parts, plurals | done |
| Locale resolution and right-to-left | done |
| Actions: forms, handed actions, analytics event, haptic request, screen stack, shown events | done |
| Widgets: `Screen`, `Column`, `Row`, `Container`, `Text`, `Image`, `Icon`, `Button`, `Spacer`, `Divider`, `Badge`; pressable containers | drawn |
| Every other widget | its children are drawn |

## Shared case tables

`./gradlew test` runs these groups of `contract/fixtures/*/*-cases.json` (all rows pass, or a row is
listed by name where the platform's CLDR data writes other characters than the table):

- `template`: all seven groups; `locale`: `cases`
- `actions`: `formCases`, `handedCases`, `flowCases`, `hapticCases`
- `scope`: all six groups (layers, record, flow, live record)
- `inputs`: `emptinessCases`, `valuePathCases`; `repeater-filter`: `filterCases`, `limitCases`, `emptinessCases`
- `icon-text`: `orderCases`, `glyphCases`, `buttonSizeCases`, `iconButtonCases`, `nameCases`, `iconPathCases`

Every other group is named in `TableCoverageTest` with the wave that will run it, or as a verdict of
the builder's validator. Tests also compare every shipped copy of contract data (built-in theme,
icon set, versions, action forms, widget list) with the artifacts, draw every fixture screen, and
check the wiring of each capability through the real screen.

## Run

```sh
scripts/sync-contract.sh ../bank-ui-builder   # copy the contract (artifacts, tables, fixtures)
./gradlew test                                # rules, tables and pixels on the JVM (Robolectric)
./gradlew :demo:recordRoborazziDebug          # frames of fixtures into demo/frames/
./gradlew :demo:assembleDebug

adb shell am start -n com.hotlayout.demo/.MainActivity \
  --es fixture rewards-showcase-screens --es screen home --es locale en
```

Without extras the demo lists every fixture.

## License

The icon drawings include icons from Lucide (ISC); see `THIRD-PARTY-NOTICES.md`. The demo bundles
Inter (SIL Open Font License, `demo/fonts-license/OFL-Inter.txt`).
