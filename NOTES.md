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

- The screen root is one scroll; the zones (fixed app bar and bottom zone, safe insets) are wave 2.
- Modal layers are a dimmed backdrop with the layer at the bottom or centre; sheets, windows, blur
  and drag are wave 2. The system back key is not wired yet (it needs `activity-compose` or a host
  hook; decide in wave 2).
- `setValue` / `toggleValue` are reported and not performed (wave 1 controls).
- Badge and Button have no press feedback; no disabled state (`disabledIf`, `requiresFilled`).
- Dashed borders are drawn solid; `style.backdropBlur` is read but not drawn.
- `showOn`, `styleOn`, `propsOn` are not read (wave 3 adaptive; `RenderContext.isShown` is the place).

## Lists, rows, accordion and tabs (`render/widgets/Lists*.kt`)

- `ListsRules.kt` holds the pure rules run by the tables (`repeater-group`, `list-tile`, the grid and number
  groups of `repeater-filter`, `icon-text/colorCases`); the widgets only call them.
- Rows in a grid or a list with `columns` are laid out by one `GridCells` layout (cell width
  `(width − gaps) ÷ columns`, floored to whole pixels; the missing cells of the last row keep the width).
- `RepeaterRow` is the hook of the carousel wave: a `carousel` list is a plain horizontal scroll until the
  carousel family replaces its body (card width, stops, dots). `repeater-filter/carouselCases` is that wave's.
- A numbered template gets `RenderContext.listNumber`; the text family reads it for `Text.marker: number`.
- `ListTileChoice` / `LocalListTileChoice` is where a `ChoiceGroup` hands its selection to option rows
  (`trailing.kind: radio`); without a group the circle is drawn and nothing answers.
- A row with an `action` is one control (click only; long press belongs to the gestures wave).
- Tabs: the strip scrolls, each title is focusable (Role.Tab, selected); Left/Right move between titles,
  swapped in right-to-left text, Home/End jump. There is no wrap-around.
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
| 1 | stack | `render/widgets/Stack*.kt` (`Stack`, `layer`, `fill`) — built |
| 1 | progress | new `render/widgets/Progress*.kt` |
| 1 | controls | `render/widgets/RoleWidgets.kt` (Button, Badge, `IconButton`), write actions in `actions/` |
| 2 | inputs & form | new `render/widgets/Input*.kt`, `data/` session |
| 2 | screen anatomy | `ScreenRoot` in `HotlayoutScreen.kt` → new `render/screen/` |
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
