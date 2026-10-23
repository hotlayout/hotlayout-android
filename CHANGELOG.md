# Changelog

All notable changes to `com.hotlayout:hotlayout-android` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the library follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
While the version is `0.x`, a minor release may contain breaking changes; they are listed under
**Changed** or **Removed**.

The library version is independent of the contract versions. Which documents and themes a release reads
is stated in every entry.

## [Unreleased]

### Fixed

- A page with glass (`style.backdropBlur`) in its scrolling body no longer redraws on every frame while nothing on it
  changes: it kept the device busy and drained the battery, and a test that waited for an idle page never finished.
  The glass now follows the body when it scrolls, and keeps its place when the page is recomposed.
- A glass node (`style.backdropBlur`) and a node over it, scrolled under the pinned top zone, are clipped by the
  bottom edge of the zone, as every other node is. They are drawn at the page, outside the clip of their scrolling
  ancestors, so a scrolled glass card showed over the top zone and the status bar. Horizontal scrolls (a scrolling
  row, a carousel, a list as a row) clip glass the same way.
- A focused text field rises above the on-screen keyboard together with the line under it (helper, error, counter):
  it used to be scrolled only by its box, so the box could end under the keyboard or the pinned block and the line
  under it stayed hidden. A modal layer now rises above the keyboard too (it stayed under it).
- A glass inside a glass (`style.backdropBlur`; a glass button in an app bar that turns to glass after a scroll)
  blurs what is drawn under it, the outer glass and its tint included, as a backdrop filter does. It used to
  blur the page under both, so the button showed the sharp, bright content of the body through a blurred bar.

## [0.2.1] - 2026-10-22

### Changed

- `StandardAction.Close` and `VersionVerdict.Ok` are `data object`s: they gain `equals` and `hashCode`
  (all references to `INSTANCE` are unchanged; `toString` still returns `Close` / `Ok`).
- The message of `HotlayoutLoadException.MalformedJson` no longer repeats its prefix: it reads
  `malformed JSON: <reason> at byte <offset>`.
- KDoc of `ScreenShownEvent.modalPlacement` and `statusBarIcons` names their closed sets of words.
- The README example reads the document off the main thread and handles a document the reader refuses.

### Fixed

- Glass (`style.backdropBlur`) on a node that stands inside another glass (a glass button in an app bar that is glass in its
  scrolled state), or inside a node with its own layer (motion, opacity, a pinned zone), no longer loses the node: the glass
  nodes of a page stay registered while they are on it, and a glass is drawn after the glass it stands in.
- A glass node with `motion` is drawn once, at the place it has moved to. It no longer leaves a second, shifted copy of itself
  in the page it blurs (a sharp second edge below the tile and a halo around its icon).

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
