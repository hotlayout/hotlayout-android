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
- `style.backdropBlur` is read but not drawn (modals).
- `showOn`, `styleOn`, `propsOn` are not read (wave 3 adaptive; `RenderContext.isShown` is the place).

## Controls (wave 1)

- One input path for cards, buttons and icon buttons (`pressInput`): own pointer gesture (tap; hold 450 ms
  inside a slop of 10 is the long press; the touch after it is swallowed), `focusable` + Enter/Space press at
  once, Menu / Shift+F10 long press, key auto-repeat ignored. Compose's `clickable` is NOT used: it is not
  focusable in touch mode and fires on key up. The innermost control consumes the down, so the card around gets nothing.
- Disabled (`disabledIf`, `requiresFilled`): half opacity, `disabled()` semantics, still focusable, no action,
  track or haptic. The form walk is `ScreenForm` in `actions/PressRules.kt` (the inputs wave may replace it).
- Write actions are performed in `HotlayoutScreen.perform` (haptic, track, write to session and `onWrite`),
  gated by `writablePaths`. Options are `Role.RadioButton` + `selected`, toggles `Role.Button` + `toggleableState`;
  the place in the group is `CollectionItemInfo` and the key `ChoicePlaceKey` (size cannot be said otherwise).
  Siblings come from `LocalSiblings` / `LocalPeers`, provided by `NodeView`.
- Swipe to confirm: host answers with a task through `onActionTask` (returns a `Job`); busy ends on task end,
  on a shown-screen change (`RenderContext.shownSerial`) or after 8 s without a task.
- `ChoiceGroup` is drawn and hands a `ChoiceScope` to rows through `RenderContext.choice`; the radio rows
  (`ListTile`) belong to the lists wave and must call `scope.select`.
- Dashed borders are paths with the contract's pattern; `shadowColor: background` tints in `StyleResolve.box`.

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
| 1 | alignment | `render/layout/FlowLayouts.kt` (`ColumnWidget`, `RowWidget`: `justify`, free height, `pull`) |
| 1 | text | `render/widgets/TextWidget.kt`, `render/TextStyles.kt` (spans, markers, gradient) |
| 1 | lists | new `render/widgets/Lists*.kt` (`Repeater`, `Grid`, `ListTile`, `Accordion`, `Tabs`) |
| 1 | stack | new `render/widgets/Stack*.kt` (`Stack`, `layer`, `fill`) |
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
