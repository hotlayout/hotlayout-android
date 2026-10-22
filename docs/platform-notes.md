# Platform notes

Places where the Android platform, the shared case tables or the other readers disagree, and what
this reader does about it. Not part of the library API.

## Platform data differs from the table

Formatting goes through `android.icu` (the platform's ICU and CLDR). Tests run on the JVM through
Robolectric, which ships the same ICU as the Android release it emulates (SDK 35), so the tables
see what a device shows. Rows that differ are listed by name in `TemplateCasesTest.platformDiffers`:

- U+202F before `PM` and before the year marks `р.`, `г.`, `ж.` (en, uk, ru, kk) — as on Apple;
- plain `ar` writes Arabic-Indic digits (CLDR's default numbering of `ar` in this ICU); the table
  writes Latin ones and marks those rows `mayDiffer`. One row that is NOT marked differs for the
  same reason: `compactCases: ar: no table of its own — the English suffix` (the suffix, the point of
  the row, is right).

## Rules the case tables check only as a model

These rules are proved by their tables on the rule functions alone. The real screen lays the same
thing out with Compose `Row` / `Column` / `Canvas`, and only sampled wiring tests read it back, so a
change in either side is not caught by the other:

- `ListTileRules`: `rowRects`, `leadingBox`, `trailingColumn`, `shownLines`;
- `InputRules`: `switchHeight`, `checkboxHeight` and the label widths; `InputPartsRules`: `affixRow`, `noteRow`;
- `CarouselRules.indicatorBlock`;
- `IconBesideTextRules`: `visualOrder`, `glyphBox`, `addsInline`;
- `ImageRules.isCircle`;
- `LayerRules.visibleSizers`;
- `DashedBorderRules.pathString`;
- `ScreenAnatomy.pressZone`;
- `WidthRules.contentRects`;
- `NavBarRules.sectionStack` against the stack step of `Flow.press`;
- `ListMarkerRules`: `layout`, `naturalInline` (the marker row uses `geometry()` only);
- `ProgressRules`: `segmentsKind`, `ringArc`;
- `SwipeConfirmRules.naturalWidth`; `LongPressRules.outcome` and `swallowsClick` (the card gesture is a separate state machine kept equal by `ControlsWiringTest`).

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
  `Trim.None`, no font padding). The platform adds the metrics of a FALLBACK font (Arabic, when the
  theme's family has no Arabic letters) to a line after the line height is applied, and Compose has no
  switch for it, so `LineBoxKeeper` (`TextStyles.kt`) cuts the text box back to `lines × ceil(pitch)` and
  shifts the drawing by half the surplus. Latin lines are never touched (they already measure to it).
- A text that must show whole (trailing column of a row, list marker) is laid out by
  `neverNarrowerThanItsLine`: with `softWrap = false` Compose lays the line at the rounded-up intrinsic
  width and a right-to-left line is a fraction wider, so the last character wrapped and was cut
  («+20» → «…+»). The slack is not part of the box. The marker may overflow its column toward the start
  (`wrapContentWidth(unbounded)`), as the contract says.
- A host that ships a VARIABLE font must give each weight a typeface built with `setWeight`
  (`DemoFonts.WeightedFace`): the platform picks fallback fonts by the typeface's style weight, and
  `Font(..., variationSettings)` leaves it at the file's default, so bold Arabic came out regular.
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

## Controls

- One input path for cards, buttons and icon buttons (`pressInput`): own pointer gesture (tap; hold 450 ms
  inside a slop of 10 is the long press; the touch after it is swallowed), `focusable` + Enter/Space press at
  once, Menu / Shift+F10 long press, key auto-repeat ignored. Compose's `clickable` is NOT used: it is not
  focusable in touch mode and fires on key up. The innermost control consumes the down, so the card around gets nothing.
- Disabled (`disabledIf`, `requiresFilled`): half opacity, `disabled()` semantics, still focusable, no action,
  track or haptic. The form walk is `ScreenForm` in `actions/FormRules.kt`.
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
- A row with an `action` is one control (click only).
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
- A named icon box (`style.width` / `height`) centres the glyph.

## Alignment and flow layouts

- Column and row are own `Layout`s (`AlignedColumn`, `AlignedRow` in `render/layout/AlignedLayouts.kt`); the rules are pure
  (`AlignRules`) and the layouts only measure and call them. Children hand `pull`, weight and width kind through `FlowChild`
  (parent data).
- Free height exists only with a source: `style.height`, `style.minHeight`, a stack fill (`fills`, for the stack zone to pass),
  or the visible body of the page screen (`LocalScreenBodyHeight`, provided by `ScreenRoot`). A layer as tall as its content has none.
  Screen anatomy must keep providing `LocalScreenBodyHeight` (the body area) and pass `ignoresFirstPull` for the top zone.
- Natural widths come from intrinsic measurements (`maxIntrinsicWidth`), so a widget that sits in a row must implement them.
- A named `style.width` / `style.height` is the size of the content too (`NodeView` passes min constraints on): a stack or a
  column inside fills it and aligns by its own words.
- Rows: `scrollable` children are bound by the viewport (stretching ones take it); `wrap` is a `FlowRow` (gap on both axes);
  a row not given its width tightly is as wide as its content and has no shares. `Row.wrap` + `scrollable`: scroll wins.
- `Screen.justify` is not read when the content scrolls (free space is zero).

## Text

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
  host that measures itself. A host that measures the keyboard itself provides the value.
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

## Screen anatomy (`render/screen/`)

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
- Safe insets: `rememberScreenInsets` reads status bar + cutout (top), navigation bar (bottom) and `keyboardInset()`, each with
  what an ancestor already consumed taken out side by side (`ConsumedInsets`: `onConsumedWindowInsetsChanged` on the screen box,
  `WindowInsets.exclude`), so `safeDrawingPadding()` or a `Scaffold` above the reader makes it add nothing twice, keyboard lift
  included. `HotlayoutScreen(safeAreaTop, safeAreaBottom)` still replaces the window's. Recommended host: edge to edge, the reader
  adds the insets; a padded host works too.
- Motion between screens (`modal/PageTransition.kt`, `ModalStack`): a closed layer stays drawn while it sinks back (the exit motion of a
  drag release; it takes every touch, no focus), a page forward / back slides + fades, a section switch cross-fades, entry and
  reduced motion (animator scale 0) are instant. The stack and the events have moved on before the motion starts; the motion
  only follows (`ModalWiringTest`: events and stack unchanged mid-motion).
  The top inset lies inside the bar box (or lowers the body without a bar); the bottom one only under a bottom zone.
  Layers take none. `statusBarIcons` is only in the show event; the reader never touches the system bars.
- The keyboard lifts the pinned block (not the nav bar) by `keyboardInset()`; the body area shrinks, and a focused
  field is scrolled into it with the line under it (helper, error, counter): the field asks `BringIntoViewRequester` on
  its whole column once the keyboard has moved the area (`KeepInViewAboveKeyboard`; the cursor's own request covers the
  box only). A modal layer is placed in the area above the keyboard (`ModalBox`). The host must not pan the window:
  with `adjustUnspecified` the system shifts the whole window up and the reader cannot see it; use `adjustResize` with
  an edge-to-edge window (the reader still reads the inset itself).
- Navigation bar: its place is `WideChrome.screenChrome` / `navPlace` (`NavBarRules.place` delegates); `RenderContext.barInset/barScrolled/navInset/navLine` are what the zones hand the bar and the items.
- The badge dot is tagged `nav-badge-dot` (read with the unmerged tree); the flash runs only on a change of the condition.
- Not drawn: the bar of navigation of its own at the top and the items in the app bar, `backdropBlur` of a floating bar.

## Node motion, glass, live record

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
  The recording must go through `recordContent` (the content scope draws into the canvas of its OWN context, so
  `layer.record { drawContent() }` alone drew the page straight onto the screen and left the layer empty: glass never blurred
  on a device), and the root's background lies inside the recording (`glassSource` comes before the root's box), else the blurred
  copy is transparent where the page is only background and the sharp page shows through. Guard: `LayoutOnDeviceTest.glassBlursThePageUnderIt`.
  A glass on the root of a modal screen is the layer's own blur, not this one.
- A page is keyed by its place in the stack and its screen (`HotlayoutScreen`), so a page shown again (back, or forward to a screen of
  a similar shape) is a new show for `animateIn`; closing a modal layer is not.
- Live record: wired in `HotlayoutScreen` (`LaunchedEffect(data)` → `Flow.refresh`); `LiveRecordWiringTest` proves a page opened
  from a row follows the host data in place with no show event, and keeps the last record when it is nowhere in the data.

## Pictures (`render/widgets/`)

- `ImageRules` is pure and run by `ImageCasesTest` on the picture groups of `media-chrome-cases.json` (`ratioCases`,
  `imageBoxCases`, `paintCases`, `radiusCases`, `circleCases`); the box rows also run through the real node. The loader
  (memory cache, `https:` only by default, no redirects); a refused address draws the placeholder.
- The loader: the size is read first (`inJustDecodeBounds`), a side beyond 8 × `maxPixelSide`
  (default 4096) is refused, otherwise `inSampleSize` brings the longest side to the bound; the whole transfer has a
  deadline (`totalDeadlineMs`, 30 s: a watchdog disconnects the connection, `readCapped` also checks the clock between
  reads); the host can plug a transport (`ImagePolicy.fetch`); while `CookieHandler.getDefault()` is set the default
  loader does not fetch and reports why (and does not remember the address as failed). `data:` is compared with the cap
  from its length before it is decoded. The new `ImagePolicy` parameters stand before `resolve`, so the trailing lambda
  still works.
- A named height is clamped by `minHeight` / `maxHeight` in `nodeBox` itself (the minimum wins): `height()` first would fix the
  constraints and the limits inside could not change them.
- A `fill` layer of a stack is drawn stretched (as wide as the stack), and a column or row that is one gets `fills` from the
  dispatcher (`AlignRules.fills`), so `justify: end` in a fill layer rests on the bottom of the stack. A fill layer is
  never sized by its widest child, or a nested stack in it would be zero wide.
- The demo data is every path of the data dictionary (`data-contract.json`, bundled by the sync script) with its sample value,
  plus the collections with the records of the Flutter demo (`SampleData`), so frames of two readers compare the readers, not the data.
  The catalog pictures are the builder's `showcase/media/sample/*.webp` (bundled in `demo/src/main/assets/sample`, `data:`
  addresses); the asset tokens `logo`, `banner`, `placeholder` map to three different pictures (the Flutter demo maps none).
  The survey is filled by the samples, as in Flutter: the tests of an empty form pass `data = emptyMap()` to `FixtureHost`.
- Icon from data (`iconPath`) is built with the icon-text rules (`icon-text/iconPathCases`).
- Not drawn: `backdropBlur` of a floating bar.

## Adaptive width (`model/WidthClass.kt`, `render/adaptive/`)

- The class is read from the box `HotlayoutScreen` is given (`BoxWithConstraints`, dp, not rounded; unbounded or non-finite is compact), one class per screen; a modal layer is always compact. Any change of the box (rotation, fold, split screen) resolves the tree again.
- The reader resolves the tree for the class (`DocumentNode.inWidthClass`: effective props, base style with the `styleOn` layers, no `propsOn` / `styleOn`) and then draws a plain document; no widget knows the word class. `columns` that came from a layer is read as 1…6 (`DocumentNode.columnsFromOverride`). `showOn` stays a field and is answered with `visibleIf` in `RenderContext.isShown` (`WidthRules.isShown`); forms and the key order take the class too (`ScreenForm.requiredFields(root, widthClass)`, `hiddenByClass`).
- Who stands where and the place of the navigation bar: one function, `WideChrome.screenChrome` (`bottom` / `appBar` / `top`). In the app bar the items and the actions are one block of the trailing zone (`WideChrome.appBarZones`); a bar of its own is the `TopNav` zone of `ScreenZonesLayout` (takes the top, everything else is counted under it, the status bar inset lies inside its box). Labels hide all together, decided by natural widths (`maxIntrinsicWidth`), never by the look chosen; the item gets a box narrower than its content and clips the label, which stays the accessible name.
- `Screen.contentMaxWidth`: `Modifier.contentColumn` on the body, the pinned block, the top zone, the items of the app bar and of the navigation bar; backgrounds stay edge to edge.
- Modal: a layer without `modalPlacement` on a wide box is a centred window of at most 560 (85 % of the height), no drag (`ModalLayerSpec.sheetWindow`); the class of the FRAME decides the shape, the content is compact; the bottom corners the style did not name take the top radii.
- The stack and the entered values survive the recreation of the host activity (a rotation without `configChanges`): saved as JSON text by `ConfigChangeState` (`rememberSaveable`); a stack naming a screen the document no longer has is dropped; the host is not told the entry show again. The saved state is keyed by the place in the composition, so the host's content must be the same composable after the recreation (as `onCreate` makes it).
- Tables run: every group of `adaptive-cases.json` except four validator groups (`wideSectionCases`, `olderReaderShowOnCases`, `unsetDocumentCases`, `unsetScreenCases`); pixel rows (`gridCases`, `boxCases`, `weightCases`, `contentCases`) through the real screen at 393 / 768 / 1280 / 1920 in both directions. Frames: `demo/frames/adaptive-screens-*.png`.
- Not built: the wide rows of `wideAppBarCases` / `wideNavBarCases` run through the pure rules plus real-screen checks of order, side and mode, not number for number through the private layout policies.

## Hardening (numbers, parser, names, actions)

- Every number that becomes a length goes through `render/Bounds.kt`: `.dp` of that file replaces the Compose one in the
  whole reader (finite, not negative, at most 8192; `.signedDp` for the two offsets that may be negative and for letter
  spacing), `roundToInt()` of that file replaces `kotlin.math.roundToInt` (no throw on NaN, bounded), `safePx()` for sizes.
  Do not import `androidx.compose.ui.unit.dp` in the reader: a hostile number would reach a constraint again.
- Theme numbers are held to `theme.json` `x-schema` when read (`ThemeBounds`); a typography style, a shadow or a gradient
  with one number outside is ignored whole and the value of the built-in theme of the same brightness stays; `fontWeight`
  must be a multiple of 100. `weight` is 1…4, `pull` 0…128 (as before).
- `DecimalText` writes a non-finite number as 0 (a computed label could overflow). Guard tests:
  `NumberConversionHardeningTest` (one value everywhere), `NumberMixHardeningTest` (a mix from a seed, plus finite but
  too-big values), `LengthHelpersHardeningTest`.
- `DocumentReader.read` skips a screen deeper than 32 levels (a report) and throws only when no screen is left; a host-built
  value deeper than the parser accepts (256) is refused before any recursion (`withoutForbiddenKeys`, and a theme read
  reports it); `JsonValue.of` ends a deeper host value in `null`. The JSON parser refuses leading zeros.
- A pressable card names only the children that are shown (`PressTarget.name(…, isShown)`); the publisher's «has a name
  without data» check keeps the document-only default.
- `StandardActions.url` reads the action once and checks the form again (`ActionCatalog.formProblem`, the derived scheme).
- Nested lists are held to the per-screen budget of 2550 instances (`ListBudget`, one plan per screen made in
  `ScreenSetup.context`, asked by each `Repeater` through `RenderContext.listBudget`).
- A text that paints nothing (its resolved opacity, or that of a node above it, is exactly 0, or its colour has
  opacity 0) is not part of the name of a card, as a hidden one is not (`SilentText`).
- A modal layer with `dismissible: false` is a traversal group with the title of its pane and takes neither Escape nor
  the system back key (the key goes to the host); the reader sets no Tab trap, and the layers under it are not focusable.

## Widget details
- A role surface (button) fills the minimum its box names: `NodeView` propagates min constraints for `minWidth` / `minHeight` on role widgets.
- Tab indicator is sized from the title (`IntrinsicSize.Max` column), because the strip scrolls under unbounded width.
- `Icon` clamps `glyphSize` by a named box through `GlyphGeometry` (as `IconButton` did).
- Autofill: `InputRules.autofillHint` names the hint by keyboard (email, telephoneNumber, url); Compose has a content type for the first two only. None when `autofill = false`.
- A parent whose children hold a write-option group is a `selectableGroup` (`WriteActions.holdsOptionGroup`).
- `ImagePolicy.httpsOnly` accepts only a host with a letter-only top-level domain (no digit hosts, `localhost`, bare names, brackets); downloads are capped at `maxDownloadBytes` (default 10 MB, host can override; `maxDownloadBytes` stands before `resolve` so the trailing lambda still works).
