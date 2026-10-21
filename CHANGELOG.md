# Changelog

All notable changes to `com.hotlayout:hotlayout-android` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the library follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
While the version is `0.x`, a minor release may contain breaking changes; they are listed under
**Changed** or **Removed**.

The library version is independent of the contract versions. Which documents and themes a release reads
is stated in every entry.

## [Unreleased]

### Changed

- `StandardAction.Close` and `VersionVerdict.Ok` are `data object`s: they gain `equals` and `hashCode`
  (all references to `INSTANCE` are unchanged; `toString` still returns `Close` / `Ok`).
- The message of `HotlayoutLoadException.MalformedJson` no longer repeats its prefix: it reads
  `malformed JSON: <reason> at byte <offset>`.
- KDoc of `ScreenShownEvent.modalPlacement` and `statusBarIcons` names their closed sets of words.
- The README example reads the document off the main thread and handles a document the reader refuses.

## [0.2.0] - 2026-10-20

**Reads:** schema 9.28.x, theme contract 4.0.x, icon set 1.0.0.

First version prepared for public release.

### Added

- A Jetpack Compose reader of HotLayout documents: `HotlayoutScreen`, `loadDocument`,
  `checkDocumentVersion`, `HotlayoutThemeSet`, `resolveLocale`, `runStandardAction`. Every widget of the
  contract is drawn, with the form controls, screen anatomy, modal layers, carousels, motion, glass and
  adaptive width classes.
- A bounded trust boundary for documents and themes: wrong-shaped pieces are skipped and reported
  (`onReport`), numbers are bounded, images load over `https:` by default through an `ImagePolicy`
  with size, pixel and time limits, and standard actions are checked again before they are opened.
- Public API validation (`apiCheck`), Maven publication configuration (published on Maven Central as `com.hotlayout:hotlayout-android`) and a demo
  application with the three showcase apps.
- Contract 9.28.0: the picture address rule, the list budget, the replacement of out-of-bounds theme
  numbers, silent text and reports that arrive late.

### Known limits

- The blur under a modal layer and the glass of a node need a render effect (API 31 and later); below that
  the picture stays sharp.
