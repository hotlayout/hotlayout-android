# Notes for the contract and for the next waves

Working notes of the Android reader: places where the platform, the tables or the other readers
disagree, what this reader does, and how the code is split for parallel work. Not part of the
library.

## Platform data differs from the table

Formatting goes through `android.icu` (the platform's ICU and CLDR). Tests run on the JVM through
Robolectric, which ships the same ICU as the Android release it emulates (SDK 35), so the tables
see what a device shows. Rows that differ are listed by name in `TemplateCasesTest.platformDiffers`:

- U+202F before `PM` and before the year marks `р.`, `г.`, `ж.` (en, uk, ru, kk) — as on Apple;
- plain `ar` writes Arabic-Indic digits (CLDR's default numbering of `ar` in this ICU); the table
  writes Latin ones and marks those rows `mayDiffer`. One row that is NOT marked differs for the
  same reason: `compactCases: ar: no table of its own — the English suffix` (the suffix, the point of
  the row, is right). Proposal: mark it `mayDiffer`.

## Things the platform does not give, done by hand

- Groups always (`x-template.number.grouping`): ICU follows CLDR minimum grouping; the reader groups
  the integer digits itself with the locale's separator and group sizes.
- The shortest decimal of a double (`DecimalText.shortest`) is searched with `BigDecimal`, not taken
  from `Double.toString`, whose shortest form older runtimes do not promise.
- Plural categories come from ICU `PluralRules`. ICU reads a double with about sixteen significant
  digits, so an integer part beyond a billion is replaced by one with the same last nine digits
  (every CLDR rule reads at most `i % 1000000`); without it 2^53 − 1 in Ukrainian was `few`.
- The date-time joiner is the CLDR medium joiner from a table (`DateText.glue`), like iOS and Flutter.
- Regular expressions are matched with `matches()` / `matchEntire()`: Java's `$` also accepts a
  final line feed, JavaScript's does not.
- Lengths in the contract (2048, 32, 40…) are UTF-16 units, which is what a Kotlin `String` counts.

## Rendering decisions

- Font sizes, line boxes and letter spacing are logical pixels converted to `sp` WITHOUT the system
  font scale: every reader draws the same layout for the same document. Whether to follow the font
  scale is open (iOS also uses fixed sizes).
- The line box is `lineHeight × size` with the extra space centred (`LineHeightStyle.Center`,
  `Trim.None`, no font padding).
- The paragraph direction is set from the language of the shown text (`TextDirection.Rtl/Ltr`), never
  `TextDirection.Content`, which reads the first strong letter.
- The library ships no fonts. The theme names a family (`Inter`); the host maps names to
  `FontFamily` through `HotlayoutFonts`, otherwise the system font draws. The demo bundles Inter as a
  variable font (weights through `FontVariation`).
- Icons are vector paths of `icons.json` drawn on a `Canvas`; no icon font (a release build strips
  glyphs it believes unused) and no SVG engine.
- CSS shadows are drawn outside the box only (`clipOutPath`), with a Gaussian of half the CSS blur;
  `spread` grows the shape.
- A column of natural width (in a row) is `IntrinsicSize.Max` wide; a row of natural width does not
  fill. A plain `fillMaxWidth` there made the first natural child of a row greedy.
- Pictures: memory cache (64 MB), `https:` only by default (`ImagePolicy`), `data:image/…;base64`
  when the host's policy lets the address through; redirects are not followed; no disk cache.
- Pixel tests draw the real content view in software (`View.draw`) under Robolectric's native
  graphics; `captureToImage` timed out there.

## Not built in the foundation (the waves below own them)

- `showOn`, `styleOn`, `propsOn` are not read (wave 3 adaptive; `RenderContext.isShown` is the place).

## Controls (wave 1)

- One input path for cards, buttons and icon buttons (`pressInput`): own pointer gesture (tap; hold 450 ms
  inside a slop of 10 is the long press; the touch after it is swallowed), `focusable` + Enter/Space press at
  once, Menu / Shift+F10 long press, key auto-repeat ignored. Compose's `clickable` is NOT used: it is not
  focusable in touch mode and fires on key up. The innermost control consumes the down, so the card around gets nothing.
- Disabled (`disabledIf`, `requiresFilled`): half opacity, `disabled()` semantics, still focusable, no action,
  track or haptic. The form walk is `ScreenForm` in `actions/FormRules.kt` (inputs wave).
- Write actions are performed in `HotlayoutScreen.perform` (haptic, track, write to session and `onWrite`),
  gated by `writablePaths`. Options are `Role.RadioButton` + `selected`, toggles `Role.Button` + `toggleableState`;
  the place in the group is `CollectionItemInfo` and the key `ChoicePlaceKey` (size cannot be said otherwise).
  Siblings come from `LocalSiblings` / `LocalPeers`, provided by `NodeView`.
- Swipe to confirm: host answers with a task through `onActionTask` (returns a `Job`); busy ends on task end,
  on a shown-screen change (`RenderContext.shownSerial`) or after 8 s without a task.
- `ChoiceGroup` is drawn and hands a `ChoiceScope` to rows through `RenderContext.choice`; the radio rows
  (`ListTile`) read `RenderContext.choice` and call `select` (tagged `node:<id>`, `Role.RadioButton`, `selected`).
- Dashed borders are paths with the contract's pattern; `shadowColor: background` tints in `StyleResolve.box`.
## Progress scales and value transitions

- `ProgressBar` and `CircularProgress` are drawn on a canvas by `render/widgets/Progress*.kt`; the rules
  (`ProgressRules`) are pure and run by `ProgressCasesTest` on every group of `progress-cases.json`.
- The glow is a copy of the same shapes under the scale, drawn with a `BlurMaskFilter` (a software-safe
  blur: `Modifier.blur` needs API 31 and a render effect). Skia takes a radius, so the sigma of the contract
  is converted by `radius = (sigma − 0.5) / 0.57735`.
- The ring turns the canvas a quarter, so that arcs and the sweep gradient start at the top.
- The shared value-transition rule is `render/motion/ValueTransition.kt` (pure, run by
  `ValueTransitionCasesTest`) with the clock in `ValueTransitionEngine.kt`; `ValueTransitioned` hosts a
  node, redraws it per frame (`withFrameMillis`) and stops the frames while the node is outside the window.
  Reduced motion is the device animator scale 0. Other families (Text) call the same engine.
- A bar fills the width it is given; a bar in a row without a weight takes what the row leaves (weights are
  the alignment zone's).
## Lists, rows, accordion and tabs (`render/widgets/Lists*.kt`)

- `ListsRules.kt` holds the pure rules run by the tables (`repeater-group`, `list-tile`, the grid and number
  groups of `repeater-filter`, `icon-text/colorCases`); the widgets only call them.
- Rows in a grid or a list with `columns` are laid out by one `GridCells` layout (cell width
  `(width − gaps) ÷ columns`, floored to whole pixels; the missing cells of the last row keep the width).
- A `Repeater` with a readable `carousel` goes to `CarouselRow` (see Carousel below); `RepeaterRow` draws only a plain
  row, and a list whose `carousel` field cannot be read as a plain horizontal scroll.
- A numbered template gets `RenderContext.listNumber`; the text family reads it for `Text.marker: number`.
- `ListTileChoice` / `LocalListTileChoice` is where a `ChoiceGroup` hands its selection to option rows
  (`trailing.kind: radio`); without a group the circle is drawn and nothing answers.
- A row with an `action` is one control (click only; long press belongs to the gestures wave).
- Tabs: the strip scrolls, each title is focusable (Role.Tab, selected); Left/Right move between titles,
  swapped in right-to-left text, Home/End jump. There is no wrap-around.
## Carousel (`render/widgets/Carousel*.kt`)

- `CarouselRules` is pure and run by `CarouselCasesTest` (every group of `carousel-cases.json` and `repeater-filter/carouselCases`).
  `CarouselRow` draws a `Row` (with `scrollable`) or a `Repeater` (`direction: row`, no `columns`) that has a readable `carousel`.
- The node's padding is taken off the node box (`NodeView`) and laid INSIDE the scroll; the card box width is handed to a card through
  `RenderContext.cardWidth`, which `NodeView` reads once and clears (it overrides `width`, `minWidth`, `maxWidth`).
- The viewport is the width the place gives: a bounded maximum width (the carousel fills it); unbounded is "not known" (cards keep
  their natural widths, no dots). Only the real measure reports it (`PlaceModifier`): the intrinsic queries of a parent run the same
  layout and made the value flip for ever. Stops use the MEASURED outer widths of the cards (box and margin).
- Snapping is a `FlingBehavior` that ends every drag or throw on the nearest stop to the projected offset (0.12 s of velocity);
  reduced motion (animator scale 0) jumps. Dots are decoration (`clearAndSetSemantics`, tagged `carousel:dot[:active]` for tests);
  cards are `CollectionItemInfo` items of a `CollectionInfo` row, the scroll area is focusable.
- The active stop is remembered per stack frame (`CarouselMemory`, key = frame index + screen + node + chain of list instance indexes,
  `RenderContext.instancePath`); `HotlayoutScreen` prunes it when the stack shrinks. A stop is put back once the cards are measured at their final width.
- Not here: the dot motion of contract 9.24 belongs to the navigation bar badge dot, not to the carousel; snapping is always on (this reader draws only ready screens).

## Stack

- A stack is one `Layout`; the role of each child (sizing, `fill`, overlay with its resolved place) travels as
  parent data, so a child hidden by a condition (which draws nothing) never shifts the others.
- An overlay is PLACED by the layout (logical start, mirrored by `placeRelative`) and turned with a layer
  transform, so touches follow the drawing. Compose widens the touch area of a target smaller than 48 dp, so
  a pixel on the very edge of a small turned overlay may still hit; the tests use targets above that size.
- `pull` (`offset/pullCases`, `pullReadCases`) is read and laid out by the flow layouts (`render/layout/FlowPull.kt`).
- A named icon box (`style.width` / `height`) now centres the glyph (it stood top-left).

## Alignment and flow layouts

- Column and row are own `Layout`s (`AlignedColumn`, `AlignedRow` in `render/layout/AlignedLayouts.kt`); the rules are pure
  (`AlignRules`) and the layouts only measure and call them. Children hand `pull`, weight and width kind through `FlowChild`
  (parent data).
- Free height exists only with a source: `style.height`, `style.minHeight`, a stack fill (`fills`, for the stack zone to pass),
  or the visible body of the page screen (`LocalScreenBodyHeight`, provided by `ScreenRoot`). A layer as tall as its content has none.
  Screen anatomy (wave 2) must keep providing `LocalScreenBodyHeight` (the body area) and pass `ignoresFirstPull` for the top zone.
- Natural widths come from intrinsic measurements (`maxIntrinsicWidth`), so a widget that sits in a row must implement them.
- A named `style.width` / `style.height` is the size of the content too (`NodeView` passes min constraints on): a stack or a
  column inside fills it and aligns by its own words.
- Rows: `scrollable` children are bound by the viewport (stretching ones take it); `wrap` is a `FlowRow` (gap on both axes);
  a row not given its width tightly is as wide as its content and has no shares. `Row.wrap` + `scrollable`: scroll wins.
- `Screen.justify` is not read when the content scrolls (free space is zero).

## Zones of the code for parallel waves

Shared files — change minimally, keep signatures; merge `main` before reporting:

- `render/WidgetDispatcher.kt` — `Widget(call: WidgetCall)`: ONE `when (node.type)`; a new family adds
  one line and its type to `drawnWidgets`.
- `render/NodeView.kt` — `NodeView(node, context, placement = Placement.Stretched, depth = 0,
  parentType = null, modifier = Modifier)` and `WidgetCall(node, context, style, placement, depth,
  parentType)`.
- `render/RenderContext.kt` — `RenderContext` (data class; add fields with defaults only) and
  `isShown(node, scope)`, the one place for visibility.
- `HotlayoutScreen.kt` — the public entry, the flow state, `ScreenRoot`, `ModalLayer`.

Family files (one zone per agent):

| Wave | Zone | Files |
|---|---|---|
| 1 | alignment | `render/layout/` (done) |
| 1 | text | `render/widgets/TextWidget.kt`, `TextRichWidget.kt`, `TextRichRules.kt`, `render/TextStyles.kt`, `render/motion/ValueTransition.kt`, `TextMotion.kt` (done) |
| 1 | lists | new `render/widgets/Lists*.kt` (`Repeater`, `Grid`, `ListTile`, `Accordion`, `Tabs`) |
| 1 | stack | new `render/widgets/Stack*.kt` (`Stack`, `layer`, `fill`) |
| 1 | progress | new `render/widgets/Progress*.kt` (done) |
| 1 | stack | `render/widgets/Stack*.kt` (`Stack`, `layer`, `fill`) — built |
| 1 | controls | `render/widgets/RoleWidgets.kt` (Button, Badge, `IconButton`), write actions in `actions/` |
| 2 | inputs & form | `render/widgets/Input*.kt`, `actions/FormRules.kt` (built) |
| 2 | screen anatomy | `render/screen/` (done) |
| 2 | modals | `ModalLayer` → new `render/modal/` |
| 2 | carousel | new `render/widgets/Carousel*.kt` |
| 2 | gestures & motion | new `render/motion/`, `render/interaction/` |
| 3 | images | `render/widgets/ImageWidget.kt` |
| 3 | adaptive | new `model/WidthClass*.kt`, `RenderContext.isShown` |

Each wave moves its table groups from the deferred map of `TableCoverageTest` into `run` and adds a
wiring test (semantics or pixels through `HotlayoutScreen`) for every capability it draws.

## Text (wave 1)

- Rich text is ONE `BasicText` with an `AnnotatedString`: every run sets all its fields (slant, line,
  weight, colour or brush), so a run never takes the line of the paragraph. The paragraph line
  height is the strut (tallest declared part), centred, so parts share a baseline and every line
  has the same pitch. The gradient is a `ShaderBrush` over the whole text box, per run that takes it.
- Differs: a line under a run that takes the gradient is drawn by Compose with the same brush
  (iOS draws lines in the plain colour); no table row depends on it.
- List numbering needs the siblings of ONE parent: `ColumnWidget` and `RowWidget` set
  `RenderContext.listPosition` per child (`ListSiblings`, `at`); `NodeView` hands it to the widget
  through `WidgetCall.position` and clears it for descendants. Other parents (Stack, Grid, Tabs,
  carousel) must do the same, and a list that draws a numbered template sets `listNumber`.
- `ValueTransition` and `ValueTransitionEngine` (`render/motion/`) are shared: a scale of the
  progress zone uses `shown.smooth` / `ends`. Tests hold the clock with
  `LocalValueTransitionElapsedMs` and `LocalCountdownTickedSeconds`. The countdown clock runs only
  while the node is inside the window; reduced motion is the system animator scale being 0.
- Rows of `amountPartCases` with plain `ar` digits differ like the money rows (`TextRichCasesTest.platformDiffers`).
- The demo data carries the dates of the data dictionary (`user.memberSince`, `campaign.endsAt`,
  `offer.endsAt`): without them the showcase's date texts showed their fallback «—».

## Inputs and the form (`render/widgets/Input*.kt`, `actions/FormRules.kt`)

- Rules are pure and table-run (`InputRules`, `InputPartsRules`, `CompositionTracker`; `InputCasesTest`): every group of
  `input-cases.json` that is a reader's runs; `placementCases` is the validator's. `InputFieldDriver` is the glue between
  Compose's `TextFieldValue` and the rules and is table-driven too (`InputFieldDriverTest`), because a test process has no
  IME that could produce a value with a `composition` range.
- The control is `BasicTextField(TextFieldValue)`. `composition != null` means composing: nothing is written until it is
  committed; a composition still open when the focus is lost is committed at that moment (one write). One table row
  (`composing cancelled by losing focus`) differs at the driver level for that reason; the rule alone passes it.
  Open point: Gboard with suggestions on marks the word being typed as composing, so a Latin word is written when it is
  committed (space, blur), not per letter; a button pressed mid-word sees the previous text. Fields whose keyboard is not
  `text` (email, phone, url, number) turn auto-correct off and do not compose on Gboard.
- The held text is released by an EVENT (`LaunchedEffect` keyed by the value under the path), not by comparison at paint
  time; a number field compares numbers and needs none. The session beats host data, so on a real screen a typed value
  stays; the A → B → A path is exercised with a host that forbids the write (`writablePaths`).
- Text that arrives before the first touch (autofill, `SetText` on an unfocused field) is dropped. `autofill = false` on
  `HotlayoutScreen` is read into `InputForm.autofill`; Compose content-type hints are not attached yet.
- The «next» key walks `InputOrder` (document order, shown and enabled fields, not inside a `Repeater`) through
  per-screen `InputFocusRegistry`s; «done» clears focus. The key never performs an action.
- A number field in the error state (non-number, not focused) is shown by the border colour only: Compose has no
  `aria-invalid` without a text, and the contract says the reader adds none.
- Keyboard: `keyboardInset()` (`InputForm.kt`) reads `WindowInsets.ime`; `ProvideKeyboardInset` states it for tests or a
  host that measures itself. The screen zones (anatomy wave) must lift the pinned block by it and leave the nav bar
  under the keyboard; the hook is not applied in `ScreenRoot` yet.
- Switch and checkbox: whole row is the press area (`pressInput`), `Role.Switch` / `Role.Checkbox` with `toggleableState`;
  no thumb animation.
## Modal layers (`render/modal/`)

- `ModalStack` holds the page and the layers: the page is one box, each layer a sibling with its own `zIndex` (its own
  stacking context). The page and every layer below the top are inert: `focusProperties { canFocus = false }`,
  `clearAndSetSemantics` (hidden from accessibility and from tests' merged tree), and the dim above them takes every touch.
- Box rules are pure (`ModalRules`, run by `ModalCasesTest`) in LOGICAL pixels; `ModalBox` converts. A sheet is full width at the
  bottom, a window is centred, at most 400 wide with the theme's `xl` margin (24 without). The content is measured with the room
  as its maximum height, so a taller body scrolls inside its own scroll (the app bar of a layer stays where the screen's zones
  keep it). No built-in look: the root's `style` draws background, radius and shadow.
- Blur: `Modifier.blur` (a `RenderEffect`) from API 31; below that the picture stays sharp and the 0.45 dim alone separates the
  layer. The page is blurred by the combined blur (square root of the sum of squares) of all layers, a layer by those above it.
  Robolectric's software `View.draw` does not apply render effects: tests read the radius from semantics
  (`ModalPageBlurKey`, `ModalLayerBlurKey`) and the blur is looked at in `demo/frames/motion-blur-screens-sheet.png`
  (Roborazzi draws it).
- Sheet drag runs in the capture pass (`SheetDrag.kt`): nothing is taken before the slop; once `dragStarts` holds the move is
  consumed and a button or scroll under the finger is cancelled. The scroll position of the body is NOT owned by the reader: it is
  learned from the scrolling that passes through the layer (nested scroll, starting at the top) and read when the finger goes down.
  The handle is not told from the rest of the sheet (`onHandle` false), as on iOS. Release: `dragOutcome`, return 250 ms / exit
  200 ms with the contract's curves, then one step back after the sheet has left; reduced motion (animator scale 0) is instant.
  Appear: a short slide (sheet) or scale and fade (window); leaving by tap, key or back is instant.
- Keys and back: Escape on the focused top layer is one step (`escapeSteps`); a held key is taken and closes nothing; a layer with
  `dismissible: false` leaves Escape to the host. The system back key is a `BackHandler` of `activity-compose` (a library
  dependency): one step back while the stack has more than one screen and the top layer may be dismissed; otherwise, or with
  `handleSystemBack = false`, the key goes to the host. A host `BackHandler` registered after the reader's wins. Predictive back
  follows the host's manifest. Layers also expose the semantics `dismiss` action when dismissible.
- Focus moves only when the person moved the stack (`movedSerial`, raised by a press that shows a screen): the layer takes it on
  show, the page anchor after a step back; the first screen and a host `entry` change take none.

## Screen anatomy (wave 2, `render/screen/`)

- `ScreenAnatomy` (pure, port of the contract rules) decides every zone; `ScreenZonesLayout` is ONE Compose `Layout` that
  measures the children (app bar, top zone, body, pinned block, nav bar, tagged with `inZone`) and places them with those
  numbers; `AnatomyScreen` composes the zones. `HotlayoutScreen.ScreenRoot` is a thin wrapper (a modal layer passes `shrinks`).
- A `Measurable` can be measured once, so a shrinking layer's body is measured once with the most room it can have (the rule
  run with a huge flow gives it); the body is given a minimum height only for `bodyTop: screenTop` under an app bar.
- The root style is split: background, border, radius, shadow on the container; paddings go to the zones. The zones draw the
  root's children through `ColumnWidget` with a copy of the root that has no style (the host space is then the body area);
  `LocalScreenBodyHeight` is the body area less the lead and the tail (and less the floating zone's height).
- Writing state from measure: `ZoneReport` (bar height for the scrolled threshold, the floating zone's height for the body
  tail). Both only change when a value changed.
- Safe insets: `rememberScreenInsets` reads status bar + cutout (top), navigation bar (bottom) and `keyboardInset()`;
  `HotlayoutScreen(safeAreaTop, safeAreaBottom)` replaces the window's (pass 0 when a Scaffold already consumed them).
  The top inset lies inside the bar box (or lowers the body without a bar); the bottom one only under a bottom zone.
  Layers take none. `statusBarIcons` is only in the show event; the reader never touches the system bars.
- The keyboard lifts the pinned block (not the nav bar) by `keyboardInset()`; the body area shrinks, so a focused field
  scrolls into view by Compose's own bring-into-view in the body scroll.
- Navigation bar: always at the bottom for now. `NavBarRules.place` is the hook for wave 3 (`widePlacement`, items in the
  app bar); `RenderContext.barInset/barScrolled/navInset/navLine` are what the zones hand the bar and the items.
- The badge dot is tagged `nav-badge-dot` (read with the unmerged tree); the flash runs only on a change of the condition.
- Not drawn: the bar of navigation of its own at the top and the items in the app bar (wave 3), `backdropBlur` of a floating bar.

## Node motion, glass, live record (gestures & motion)

- `motion` (`render/motion/NodeMotionRules.kt`, pure, run by `MotionCasesTest`): one sine formula, no curve field. A moving node is
  `NodeMotionBox`: an outer box that stays at rest and an inner box with a `graphicsLayer` (shift, scale about the centre, alpha),
  so layout never changes. Compose hit-tests a child outside its parent's bounds, so the rest box does not limit touches by itself:
  a guard inside the layer takes the drawn position back to the rest box and consumes a touch that starts outside it
  (the target is the overlap). The root never moves. A layer of a `Stack` is turned by the stack, so its shift is turned back
  (`LocalLayerTurn`, read only by a node whose parent is a stack): the motion goes along the screen axes, as on the other readers.
- Frames: the clock asks for a frame (`withFrameMillis`) only while the node is inside the window (`onGloballyPositioned`);
  «reduce motion» (animator scale 0) draws the node plain, no layer, no clock. `NodeMotionFrames.requested` counts the frames
  the clocks asked for; a test proves a node below the fold asks for none. An on-screen endless motion keeps a Compose test from
  becoming idle (as any infinite animation does): tests that open a fixture with motion set the animator scale to 0 or hold
  the clock with `LocalNodeMotionClockMs`.
- Audit of the other clocks: the countdown ticks only while its node is inside the window; the swipe «busy» state is a single
  timer (8 s) with no frames; the glow is static; value transitions are finite (700 ms) and end by themselves — they do not stop
  when a node leaves the window mid-run, they just finish. Frames of nodes under another page are not asked for at all: a page
  below a page is not composed (a page below a modal layer is, and is shown).
- Glass (`style.backdropBlur`, `render/paint/Glass.kt`): a page that names a glass records itself WITHOUT its glass nodes into a
  `GraphicsLayer` (one traversal), draws it, then draws every glass node over it: the recorded page blurred (`RenderEffect`, edge
  clamp) under the node's box, clipped by its radius, then the node's own content layer (background and children). A glass node
  is therefore drawn above the other nodes of its page. On a role widget (`Button`, `IconButton`, `Badge`) the glass lies on the
  role surface. Below API 31 nothing blurs and the box is drawn as it is (no frosted imitation). The JVM tests draw in software, where the
  render effect is not applied: they prove where the glass is drawn, with which radius, and that the page and the box stay whole.
  A glass on the root of a modal screen is the layer's own blur (modal wave), not this one. `styleOn` is the adaptive wave's.
- A page is keyed by its place in the stack and its screen (`HotlayoutScreen`), so a page shown again (back, or forward to a screen of
  a similar shape) is a new show for `animateIn`; closing a modal layer is not.
- Live record: wired in `HotlayoutScreen` (`LaunchedEffect(data)` → `Flow.refresh`); `LiveRecordWiringTest` proves a page opened
  from a row follows the host data in place with no show event, and keeps the last record when it is nowhere in the data.
- Fixture `motion-blur-screens`: the three drifting dots of the page do not appear in the frame (the nested stacks with a `fill`
  anchor draw nothing); the same on `main` before this work. The glass card appears.
