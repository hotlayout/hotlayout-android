# Tester findings — Android reader at `cb7f146`

Independent check of the Android reader against the Flutter reader (the reference look) and against the
contract artifacts (`contract/screen-schema.json`, `contract/fixtures/**`). Method:

- **Side by side.** 37 phone screens and 5 tablet screens, same fixtures, same logical width: Android emulator
  `Pixel_6` set to 1179×2556 @ 480 dpi (393 dp, the iPhone 16 width) and 1668×2420 @ 320 dpi (834 dp, iPad Pro 11)
  against the Flutter demo on a headless iPhone 16 / iPad Pro 11 simulator. Pairs in `tester-findings/` (a few) —
  the full set was made with the iOS side-by-side scripts (`flutter-shots.sh`, `pair.py`).
  The demo's data is not Flutter's data (texts like «Challenge 1» vs «Spend 500 with your card», `1,250` vs `12,400`
  are data, not reader differences) and are not listed.
- **Behaviour on the emulator.** `demo/src/androidTest/` — 33 instrumented tests (`./gradlew :demo:connectedDebugAndroidTest`
  with an emulator running). 20 pass where the behaviour is right; **13 fail, one per defect below**, named for it.
- **Hostile documents** through the real screen.

## Findings, most severe first

1. **Insets the host already consumed are added again** (top, bottom and keyboard).
   `rememberScreenInsets` reads `WindowInsets.statusBars / navigationBars` and `WindowInsets.ime` raw, so under a host
   that pads with `safeDrawingPadding()` / a `Scaffold` (as the demo itself does) every screen starts one status bar
   lower (+45 dp on a Pixel 6) and — worst — the keyboard lifts the pinned block a second time: on `survey-screens` /
   `details` the «Next» button floats a keyboard height above the keyboard and the body collapses to one clipped
   field (`tester-findings/android-keyboard-lifted-twice.jpg`). Every phone pair shows the extra band at the top.
   Right: Compose descendants are told which insets are consumed (`consumeWindowInsets`, `exclude(consumed)`); the
   Flutter reader reads `MediaQuery` padding, which a host `SafeArea` removes, so it never adds twice.
   Tests: `LayoutOnDeviceTest.insetsConsumedByTheHostAreNotAddedAgain` (16 dp expected, 61 dp got),
   `KeyboardOnDeviceTest.pinnedBlockIsNotLiftedTwiceUnderAPaddedHost` (pinned bottom at 230 dp, keyboard top at 549 dp).
   `KeyboardOnDeviceTest.pinnedBlockRidesOnTheKeyboard` (edge-to-edge host) passes.

2. **`style.backdropBlur` (glass) does not blur on a device** (API 37 emulator, hardware rendering).
   `motion-blur-screens` / `home`: the drifting dot under «Your points» is drawn sharp through the card; Flutter blurs
   it (`tester-findings/phone-motion-blur-screens-home-en.jpg`). A white block half under a glass card keeps a hard
   edge (0 intermediate pixels across it). The modal layer's `backdropBlur` does blur on the device.
   Test: `LayoutOnDeviceTest.glassBlursThePageUnderIt`.

3. **Bold Arabic text is drawn regular.** Every Arabic heading, button label and bold run of `text-emphasis` (ar-EG),
   `money` (ar), `bottom-zone` (ar), `hero` (ar), `offset` (ar) is regular; Flutter draws them bold
   (`tester-findings/phone-text-emphasis-screens-article-ar-EG.jpg`). The theme names Inter, which has no Arabic
   letters; the platform's fallback font is chosen without the requested weight when the family is built from the
   variable font with `FontVariation` (the demo's `DemoFonts`, the pattern the README suggests). Latin in the same
   test is bold. Either the library must pass the weight to fallback text, or the README / demo must build the
   family so the fallback keeps it. Test: `LayoutOnDeviceTest.boldArabicTextIsBold` (regular 294 px, «bold» 291 px);
   `boldLatinTextIsBold` passes.

4. **Arabic text breaks the line box.** The line box is `lineHeight × size` whatever font draws the letters
   (NOTES «Rendering decisions»); a fallback Arabic font makes lines taller: `money-screens`, the «add 500» button is
   130 px in Arabic and 120 px in English (Flutter: the same in both). Every Arabic screen is taller than Flutter's.
   Test: `LayoutOnDeviceTest.arabicTextKeepsTheLineBox`.

5. **Trailing text of a right-to-left list row is cut.** `bottom-zone-screens` / `home`, `ar`: «+20» shows as «…+» and
   «+200» as «2…+», while «+50», «+80», «+30» are whole (`tester-findings/phone-bottom-zone-screens-home-ar.jpg`).
   The trailing column must never shrink (`ListsTile.kt` says so); Flutter shows them whole.
   Test: `LayoutOnDeviceTest.trailingTextOfARightToLeftRowIsNotCut`.

6. **`style.minWidth` of a `Button` is not applied.** `rewards-showcase-screens` / `mission`: «Claim reward» has
   `minWidth: 220` in a centred row; Flutter draws a 220-wide pill in the middle, Android a 170-wide one standing left
   of the centre (`tester-findings/phone-rewards-showcase-screens-mission-en.jpg`).
   Test: `LayoutOnDeviceTest.buttonSurfaceFillsItsMinimumWidth` (170 dp).

7. **The open tab has no indicator.** `composition-screens` / `home`, «Activity» tabs: Flutter draws the primary bar
   under «Rewards», Android draws nothing (`tester-findings/phone-composition-screens-home-en.jpg`). In `TabTitle`
   the indicator is `fillMaxWidth()` in a `Column` of the horizontally scrolling strip, i.e. under an unbounded
   width, so it is 0 wide. Test: `LayoutOnDeviceTest.openTabShowsItsIndicator`.

8. **The number marker loses its dot when the digit is wider than the column.** `text-emphasis-screens` / `article`,
   `ar-EG`: items read «١ / ٢ / ٣» without the dot; the table says «٣.» (`list-marker-cases.json`, `textCases`) and
   `x-style.layout.listMarker.overflow` is `visible-toward-start`. The glyph is a `BasicText(softWrap = false)` in a
   `Box(width = column)`, which clips. English («1.») fits and is fine. Test: `LayoutOnDeviceTest.numberMarkerIsNotClipped`.

9. **A named icon box does not clamp `glyphSize`.** `app-bar-screens` / `icons`, «Icon named 48 × 48 … then 64
   clamped to 48»: Android draws the 64 drawing cut by the 48 box (the gift fills and overflows the tile); Flutter draws
   it at 48 (`x-style.layout.ownGlyphSize.namedBox: glyph-not-larger-than-the-content-box`;
   `tester-findings/phone-app-bar-screens-icons-en.jpg`). `IconWidget` passes `own` as the glyph without the clamp.
   Test: `LayoutOnDeviceTest.namedBoxClampsTheOwnGlyphSize`.

10. **No autofill hints at all.** `autofill = true` (the default) attaches nothing to the email and phone fields;
    the artifact's keyboards name the hints (`email` → `emailAddress`, `phone` → `telephoneNumber`; the Flutter reader
    passes `autofillHints`). The `autofill` parameter is read into `InputForm.autofill` and never used, so `false`
    is trivially right and `true` is wrong. Test: `AccessibilityOnDeviceTest.emailAndPhoneFieldsCarryAutofillHints`.

11. **Options of a write action are not a group for TalkBack.** `choice-controls-screens` / `wallet`, «Week / Month /
    Planned»: each option is a radio with a `CollectionItemInfo`, but no ancestor has a `CollectionInfo` or
    `selectableGroup`, and TalkBack reads item info only inside a collection — the place («1 of 3») is never said.
    (`ChoiceGroup` options are fine: `selectableGroup`.) Test: `AccessibilityOnDeviceTest.writeOptionsAreAGroupTalkBackCanCount`.

12. **Leaving a modal by a tap on the dim (or the back key / Escape) is instant; only a drag animates.** Flutter lets the
    sheet sink back on every close (and also animates page pushes / pops, the tab panel and its indicator); Android
    has no page transitions at all. Not a contract rule — the look of a native app. Test:
    `BehaviourOnDeviceTest.sheetLeftByATapOnTheDimSinksBack` (the layer is gone two frames after the tap).

13. **The default picture policy fetches from a host of digits.** `ImagePolicy.httpsOnly("https://2130706433/x.png")`
    (127.0.0.1) and `https://127.0.0.1/…` pass, so any document makes the phone connect to its own loopback; the
    action forms refuse exactly these hosts for `openUrl`. No size limit on the download either. Low.
    Test: `HostileDocumentsOnDeviceTest.defaultImagePolicyRefusesAHostOfDigits`.

### Demo (not the library)

14. **The catalog shows one picture for every item.** `SampleData` gives all five items the single `item.image`
    sample; the dictionary's `items` collection has three distinct samples (headphones, backpack, coffee set) — the
    Flutter demo shows them. The demo also maps every asset token (`logo`, `banner`, `placeholder`) to the same
    headphones picture, and lacks `offer.*`, item prices and descriptions, so `media-chrome` / `badges`,
    `composition` / `home`, `adaptive` / `home`, `rewards-showcase` / `redeem` look wrong for data reasons
    (`tester-findings/phone-media-chrome-screens-badges-en.jpg`). The artifact `data-contract.json` carries no
    collection samples, so the demo cannot take them from it.

## Checked and right

- System back: one step of the stack (cause `back`), then the host's `BackHandler` at the first screen.
- Carousel: a drag ends on a stop, one active dot. Long press: only the long-press action and its haptic; TalkBack
  gets it as `OnLongClick` labelled «Mission options». Swipe-to-confirm: a tap does nothing, a drag past the threshold
  runs the action, TalkBack's click runs it at once. Sheet dragged down leaves with exactly one `back`.
- Keyboard: on real Gboard a word still composing is written — «Alex» typed and «Next» tapped mid-word enables the
  button and moves on (the open point in NOTES «Inputs» does not reproduce); the same through
  `InputConnection.setComposingText`. The pinned block rides on the keyboard in an edge-to-edge host.
- Rotation (activity recreated): the stack and entered text survive.
- Animator scale 0: a page with endless node motion goes idle; a moving node below the fold asks for no frames.
- TalkBack: every button has a name, the required «Next» is disabled, `ChoiceGroup` rows are radios that report
  selection, carousel cards are collection items, an empty `alt` hides the picture from accessibility only (it still
  takes its box and is drawn).
- Hostile documents: 40- and 2000-deep nesting, numbers up to 1e308 in values, sizes, `glyphSize`, `columns`,
  `__proto__` / `constructor` keys and paths, colours like `red;position:fixed` / `javascript:` / `url(...)`, missing and
  wrong-typed props, `null` and scalars as children, unknown widgets: no crash, no host action without a press;
  `openUrl` with `javascript:` or `https://2130706433/` is handed `invalid`, never `standard`. Ten thousand records in a
  `Repeater` show in under five seconds.
- Layout matches Flutter on: buttons and chips, app bar (challenges, details), alignment (all three), parts, progress,
  glow and press, carousel and steps, float nav, appear, text emphasis (en), ring, offset, adaptive phone and tablet
  (834 dp: nav in the app bar, 2-column grid, lab).

## Where Flutter is wrong and Android right

- `glow-press-screens` / `cards`, «Weekend trip» `angle: 135`: Android lays the CSS gradient line (bands at 45°
  across the whole card); Flutter maps it to the box corners (bands nearly horizontal).
- «Still» card: Flutter draws the shadow inside the translucent box; Android only outside, as CSS `box-shadow` does.
- `reward-catalog` option thumbnails without a picture: Flutter draws a picture glyph in the placeholder, Android a
  plain tile; the contract names no glyph (neither is wrong).
- Same in both readers, noted: the `Switch` thumb jumps without motion.
