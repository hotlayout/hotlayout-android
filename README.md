# HotLayout for Android

A Kotlin library that draws screens published by the [HotLayout](https://hotlayout.com) builder: a JSON
document, a theme and your data become a native Jetpack Compose screen.

It is a reader of the same contract as the React, Flutter and iOS readers and is measured by the same shared
case tables.

**Status: complete for contract 9.28.0.** Every widget of the contract is drawn: text and rich text, buttons,
lists and list rows, stacks and layers, progress bars and rings, the form controls and text fields (with forms
and their validation), the screen anatomy (app bar, body, bottom zone and navigation bar), modal layers with
the system back key, carousels, node motion and glass, images, and the adaptive width classes, plus the
alignment rules on both axes. The rules of the contract (versions, data, conditions, text, numbers, dates,
plurals, locale, actions and navigation) pass the shared case tables. The reader is also exercised by
instrumented tests on a device or emulator.

Known platform limit: the blur under a modal layer and the glass of a node need a render effect, which exists
from API 31, so below that the picture stays sharp and nothing is blurred.

## Requirements

- Android 8.0 (API 26) and later, Jetpack Compose
- No third-party dependencies besides AndroidX and Compose

## Install

The library is on Maven Central. With `mavenCentral()` among the repositories of your
`settings.gradle.kts`, add the dependency:

```kotlin
dependencies {
    implementation("com.hotlayout:hotlayout-android:0.2.1")
}
```

Your project needs the Compose compiler plugin, as any Compose app does.

To work on the library itself, include its module from a clone of this repository:

```kotlin
include(":hotlayout")
project(":hotlayout").projectDir = file("../hotlayout-android/hotlayout")
```

and depend on it with `implementation(project(":hotlayout"))`. You can also run `./gradlew :hotlayout:publishToMavenLocal` and depend on the artifact from `mavenLocal()`.

The library does not declare the `INTERNET` permission. The default picture loader downloads over the
network, so an app that shows pictures from `https:` addresses adds it to its own manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

An app that loads pictures through its own client (`ImagePolicy.fetch`) does not need it for the reader.

## Use

The whole public API is in the package `com.hotlayout`.

```kotlin
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutFonts
import com.hotlayout.HotlayoutLoadException
import com.hotlayout.HotlayoutScreen
import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.dataScopeOf
import com.hotlayout.loadDocument
import com.hotlayout.runStandardAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PromoScreen(documentJson: String, themeJson: String, inter: FontFamily) {
    val context = LocalContext.current

    // Reading is work for a background thread, and it throws on a document the reader cannot use
    // (malformed, a newer major version): the screen waits for it and the host decides what to do on failure.
    val document by produceState<HotlayoutDocument?>(null, documentJson) {
        value = withContext(Dispatchers.Default) {
            try {
                loadDocument(documentJson)                      // tolerant read + version gate
            } catch (error: HotlayoutLoadException) {
                Log.w("promo", "document refused: ${error.message}")
                null
            }
        }
    }
    val theme = remember(themeJson) { HotlayoutThemeSet.read(themeJson) ?: HotlayoutThemeSet.builtIn }

    document?.let { loaded ->
        HotlayoutScreen(
            document = loaded,
            theme = theme,
            data = dataScopeOf("user.name" to "Maya", "user.points" to 1850),
            entry = "promo",                                    // a named entry point, optional
            fonts = HotlayoutFonts { name -> if (name == "Inter") inter else null },
            onAction = { action, _ ->
                // The reader opens nothing itself. A standard action arrives checked:
                runStandardAction(context, action)
            },
            onTrack = { event, _ -> Log.i("analytics", "${event.event} ${event.params}") },
            onScreenChange = { shown -> Log.i("screen", "${shown.screenId} ${shown.cause}") },
        )
    }
}
```

`locale` defaults to the device locale. Pass `labels = HotlayoutLabels(back = …)` to give what assistive
technology hears in your language.

Separate from the view: `checkDocumentVersion` (verdicts `Ok`, `NewerMinor`, `NewerMajor`, `OlderMajor`,
`Unparsable`; it takes JSON text, bytes or a `JsonValue`), `loadDocument`, `HotlayoutThemeSet.read`,
`resolveLocale`, `loadPlacement`.

- **Documents and themes are untrusted input.** A piece of the wrong shape is skipped and reported
  (`onReport`), never a crash. A screen deeper than 32 levels is skipped (and reported); the other screens of the
  document still load. A number of a theme outside the bounds of the theme schema (or not finite) is replaced by the value of
  the same token in the built-in theme of the same brightness, otherwise by the nearest bound, and reported; every length of a document is made finite, not negative and bounded.
  See [docs/security.md](docs/security.md).
- **Images** load only over `https:` by default; pass an `ImagePolicy` to decide yourself. Its limits:
  - `maxDownloadBytes` (10 MB): a larger picture is drawn as the placeholder, announced or streamed;
  - `maxPixelSide` (4096): a picture is decoded sampled down so its longest side is at most this, the ratio kept;
  - `totalDeadlineMs` (30 s): the whole transfer, not only the silence between two reads, so a server that drips
    bytes is disconnected;
  - `fetch`: your own transport, `suspend (address, maxBytes) -> ByteArray?`, called with an address that has passed
    `resolve`. With it the reader makes no request of its own, so use it to give pictures a client with its own
    cookie jar, proxy and certificates. Return at most `maxBytes`; the call is held to `totalDeadlineMs`.
  - The default loader sends and stores **no cookies**. `HttpURLConnection` cannot skip a global handler, so if your
    app has installed a `java.net.CookieHandler` (`CookieHandler.getDefault() != null`), the default loader
    refuses to fetch (the placeholder is drawn and the reason goes to `onReport`). Hosts with a global cookie
    handler pass `fetch`.
  - `data:` addresses never touch the network (the cap and `maxPixelSide` apply).
- **Standard actions**: `runStandardAction` and `StandardActions.url` check an action again before they open it
  (the form of the address, the scheme of a deeplink against the allowed ones, no `invalid`), so an action
  assembled by hand is not trusted because of its type.
- **Navigation** inside a document (`navigate`, `navigate.back`, `navigate.section`) is done by the
  reader; `openUrl`, `deeplink`, `close` and actions of your app go to `onAction`.
- **Fonts** are yours: the theme names a family, `HotlayoutFonts` gives the `FontFamily`. If the family
  has no Arabic (or other) letters, the platform draws them with a system fallback font, chosen by the
  weight of the typeface. Static font files per weight keep it. A variable font must be wrapped so each
  weight is its own typeface built with `Typeface.Builder(...).setFontVariationSettings("'wght' N").setWeight(N)`
  (an `AndroidFont` with a `TypefaceLoader`; see `DemoFonts` in the demo) — `Font(resId, weight,
  variationSettings = …)` alone draws bold Arabic regular.
- **Labels**: the few texts the reader says to assistive technology (the name of the back arrow) are not in the
  document. Pass `HotlayoutLabels` in the language of your app; the defaults are English.

## Placements

A placement is a named place of your app (`home.banner`). Which published document stands in it is decided in the
admin, not in your code: you write the name once, and the screen changes without a release.

```kotlin
// Once, in your application object: one source serves every placement of the app.
val placements = PlacementSource(
    projectBase = "https://storage.example/orgs/acme/projects/app",
    bundledMap = context.assets.open("placements.json").bufferedReader().readText(),   // optional: the map of this release
)

@Composable
fun HomeBanner() {
    HotlayoutPlacement(
        name = "home.banner",
        source = placements,
        onAction = { action, _ -> /* as for HotlayoutScreen */ },
        onPlacement = { load -> if (load.report != null) Log.i("placement", "${load.name}: ${load.report}") },
    )
}
```

`HotlayoutPlacement` draws the document of the place with `HotlayoutScreen` (it takes the same parameters, and
starts on the entry point the map names) and **nothing** when the place is empty, unknown, unreadable, or its
document cannot be had. `onPlacement` hears every result with a `PlacementLoad`: `report` is `Empty`, `Unknown`,
`Unreadable`, `DocumentMissing`, `DocumentUnreadable`, or `EntryMissing` (the entry point is gone from the
document, which is drawn from its first screen), and `null` when all is well, so you can hide the frame around a
place that draws nothing. Without Compose, `loadPlacement(source, name)` is the same as a suspend function; it
runs off the main thread and never throws on a bad answer.

- The map is `placements/published.json` under `projectBase`, read without a token and asked again every
  `refreshIntervalMs` (a minute by default, `null` for once) with its `ETag`, so an unchanged map costs no body.
- The network wins. When it is silent, broken, or of another major version, the last known map is used, then
  `bundledMap`. Only a fresh, readable map from the network becomes the last known one. A fresh map is the whole
  answer: a place it does not have is `Unknown`, it is not looked up in an older map.
- `PlacementLoad.mapStatus` tells the cases apart: `Offline` (the network was silent), `Corrupt` (it answered with
  something that is not a map), `Unsupported` (another major), `NotPublished` (404: the project has no placements).
- Documents go the same way: the network, the last known copy, then `bundledDocuments`. The last known map and
  documents live in memory; give `PlacementStore` a file or preferences to keep them over a restart.
- A name that is not the name of a place is `Unknown` without a look at the map; a place of the map is found only by
  its own key. The map and the documents come from the network, so their ids are checked by form before they
  become an address. The default transport is `https` only, follows no redirects, uses no cookies and refuses
  more than 256 KB of map; give `PlacementTransport` to use your own client.

## Window insets

The reader adds the status bar, the cutout, the navigation bar and the keyboard itself, minus whatever an ancestor already
consumed (`safeDrawingPadding()`, a `Scaffold`, `consumeWindowInsets`). The recommended host draws edge to edge
(`enableEdgeToEdge()`) and lets the reader add them; a host that pads works as well and gets nothing added twice.
`safeAreaTop` / `safeAreaBottom` still override the window's values.

Give the activity `android:windowSoftInputMode="adjustResize"` (with an edge-to-edge window it only reports the keyboard
as an inset and does not resize anything). With the default the system may pan the whole window when the keyboard comes,
and a field low on the screen is then shifted instead of scrolled above the keyboard.

## Shared case tables

The rules of the contract are checked against the case tables the readers share (`contract/fixtures/*/*-cases.json`).
`./gradlew test` runs every group of every table, except the groups that are verdicts of the builder's validator
(which a reader does not have). `TableCoverageTest` fails when a table has a group that is neither run nor named
as a validator verdict, so a new group in the contract cannot be ignored silently. A few rows where the platform's
CLDR data writes other characters than the table are listed by name (`docs/platform-notes.md`).

The tests also compare every shipped copy of contract data (built-in theme, icon set, versions, action forms,
widget list) with the artifacts, draw every fixture screen, and check the wiring of each capability through the
real screen.

## Build and test

```sh
scripts/sync-contract.sh <path-to-contract>   # copy the contract (artifacts, tables, fixtures); or set HOTLAYOUT_CONTRACT
./gradlew test                                # rules, tables and pixels on the JVM (Robolectric)
./gradlew :hotlayout:apiCheck                 # the public API against hotlayout/api/hotlayout.api
./gradlew :hotlayout:apiDump                  # after an intended change of the public API
./gradlew :demo:recordRoborazziDebug          # frames of fixtures into demo/frames/
./gradlew :demo:assembleDebug

adb shell am start -n com.hotlayout.demo/.MainActivity \
  --es fixture rewards-showcase-screens --es screen home --es locale en
```

Without extras the demo opens the Obsidian showcase; `--es fixture list` lists everything. The three showcase apps of the
product site (`showcase-obsidian`, `showcase-sunny`, `showcase-orbit`, the last one takes `--es scenario
default|newMember|afterPayment`) live in `demo/src/main/assets/showcase/`: the demo draws edge to edge, resolves their
site-relative pictures against `https://hotlayout.com` through its own `ImagePolicy`, and bundles their fonts. `demo/frames/` holds frames of the fixtures drawn by this reader, for
comparing the readers side by side; `./gradlew test` draws them again without writing.

`./gradlew :hotlayout:publishToMavenLocal` builds the artifact (`com.hotlayout:hotlayout-android`) with its sources
and javadoc jars. Signing uses the key given by the Gradle properties `signingInMemoryKey` and
`signingInMemoryKeyPassword`; without them nothing is signed. `scripts/publish-central.sh` builds the signed release
and uploads it to the Central Portal, where the deployment waits to be published by hand.

## Documentation

- [docs/platform-notes.md](docs/platform-notes.md): where the Android platform and the shared tables differ, and what the reader does.
- [docs/security.md](docs/security.md): the trust boundary and the tests that pin it.

## License

HotLayout for Android is licensed under the Apache License 2.0; see [LICENSE](LICENSE).

The icon drawings include icons from Lucide (ISC); see `THIRD-PARTY-NOTICES.md`. The demo bundles
Inter, Geist and Manrope (SIL Open Font License, `demo/fonts-license/`).
