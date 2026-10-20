# Security and the trust boundary

The reader treats documents, themes and data as untrusted input: they arrive over the network, and a
document names its own addresses, numbers and actions. This file states what the library guarantees, and
which test pins each guarantee. The tests live in `hotlayout/src/test/kotlin/com/hotlayout/hardening/`
and run with `./gradlew test` (JVM and Robolectric, no device).

To report a vulnerability, open a private security advisory in the GitHub repository
(`hotlayout/hotlayout-android`, "Security" tab) rather than a public issue.

## Summary

| Guarantee | Test |
|---|---|
| A number of a theme, a document or the data never throws when it becomes a size | `NumberConversionHardeningTest`, `NumberMixHardeningTest`, `LengthHelpersHardeningTest`, `ThemeNumbersHardeningTest` |
| A picture is decoded at a bounded size and downloaded within a deadline | `ImageLoaderHardeningTest` |
| A picture request neither sends nor stores the host's cookies | `ImageLoaderHardeningTest` |
| A card's accessible name says only what is shown | `AccessibleNameHardeningTest` |
| `runStandardAction` opens only a checked address | `ActionsHardeningTest` |
| A too-deep screen is rejected alone, and a host-built value of any depth does not overflow the stack | `JsonParserHardeningTest`, `BoundsHardeningTest` |
| The JSON parser is strict (leading zeros, lone surrogates, duplicate keys, `__proto__`) | `JsonParserHardeningTest` |
| Writes reach the host only for allowed paths, once per gesture | `WritesHardeningTest` |
| Nothing is logged | source scan and run-time log capture in the hardening tests |

## JSON parser and the depth gate

- The parser refuses nesting above `MAX_PARSE_DEPTH` (256) with a `JsonParseException` before it recurses
  further; deeper host-built values given to `loadDocument(JsonValue)`, `HotlayoutThemeSet.read(JsonValue)`
  or `JsonValue.of` are refused (or end in `null`) before any recursion.
- A screen deeper than 32 levels under its root (the contract's reader limit) is skipped and reported;
  the other screens of the document still load. The reader throws only when no readable screen is left.
- Numbers beyond the double range parse to a non-finite value that `JsonValue.number` hides, so every
  reader sees "absent". `NaN`, `Infinity`, `+1`, `.5`, `1.`, `0x10` and leading zeros are syntax errors.
- Duplicate keys: the first position and the last value, as `JSON.parse` does. `__proto__` is dropped at
  any depth.
- Invalid UTF-8 and lone surrogates become U+FFFD; raw control characters and unknown escapes are refused.
- Parse time is linear for long strings, long arrays, many keys and repeated deep nesting.

## Actions

- The action catalog is the contract artifact's `x-actions.catalog` entry for entry; the address forms,
  length limit, forbidden schemes and the order of checks are the artifact's.
- Hostile addresses are `invalid`, never `standard`: IPv4 in every spelling, IPv6, `localhost`, bare names,
  private ranges, userinfo, whitespace and control characters, homoglyph hosts, bad ports, `http`,
  `javascript`, `file`, `data`, `intent`, and forbidden deeplink schemes.
- Parameters the artifact marks `bindable: false` keep their literal whatever the data say.
- The library opens nothing itself: no `startActivity`, `Intent`, `UriHandler`, `Linkify` or `WebView`
  outside `StandardActions.kt` (a source scan checks it), and the reader never calls `runStandardAction`.
  The helper opens a `standard` verdict as a browsable `ACTION_VIEW` with no component, and nothing for
  `invalid`, `close`, actions of the app, or a deeplink whose scheme the host did not allow.
- `runStandardAction` trusts the value, not the type: it reads the action once and checks the form of the
  address again (and the scheme of a deeplink against the allowed ones), so an action assembled or
  deserialised by hand is not opened because of its class.
- One gesture is one action: a tap gives haptic, track and the action once; a held key's auto-repeat is
  not a press; nothing acts without a press.

## Pictures

- The default policy lets through only `https://` with a host of labels and a letter-only top-level domain
  (or punycode). Digit hosts in any spelling, IPv6, `localhost`, bare names, userinfo, other schemes and
  addresses over 2048 characters are refused. An address from data goes through the host's `resolve` like
  a literal one.
- The default rule is exactly the address form of the action `openUrl`, and a host's own `resolve` is final except
  for `javascript:` and `vbscript:` (read as a URL parser reads them), which are never a picture. A refused address
  draws the placeholder, makes no request and is reported: the document's address cut at 120 characters, an address
  from data by scheme and host only.
- Redirects are not followed (the reader makes no hop of its own, so none escapes the rule above); the
  transfer carries no cookies, no credentials and no `Referer`.
- Downloads are capped (`maxDownloadBytes`, 10 MB): an announced length over the cap is refused before the
  body is read, a streamed body is cut when it passes the cap, and a `data:` address is compared with the
  cap from its length before it is decoded.
- Decoding is bounded (`maxPixelSide`, 4096): the size is read first and the picture is decoded sampled down so
  that its longest side is within the bound, the ratio kept.
- The whole transfer has a deadline (`totalDeadlineMs`, 30 s), so a server that drips bytes cannot hold a
  loader thread. Connect (15 s) and read (30 s) timeouts are set on the connection.
- The default loader sends and stores no cookies. `HttpURLConnection` cannot skip a global handler, so
  when `CookieHandler.getDefault() != null` the default loader fetches nothing and reports why. A host
  that needs pictures through its own client passes `ImagePolicy.fetch`.

Known limits:
- The default host rule checks the NAME, not where it resolves: a public name that resolves to a private
  address is fetched. A host that cares resolves and checks addresses in its own `resolve`, or passes a
  policy that allows only its CDN.
- `ImageStore` remembers failed addresses for the life of the process until `reset()`.

## Writes

- A written path must have the shape of a data path (segments of `[A-Za-z_][A-Za-z0-9_-]*`, at most 128
  characters, never `__proto__`, `constructor`, `prototype`) before the host's `writablePaths` is asked.
  The path is not normalised, and a set matches exactly.
- Every control that writes (text field, switch, checkbox, `ChoiceGroup` row, `setValue`, `toggleValue`)
  writes nothing outside the host's list, and exactly once per gesture inside it.
- Nothing is written without a gesture: idle time, the host's data changing under a control and text the
  platform puts into an untouched field (autofill) write nothing.

## Bounds

- Every number that becomes a length goes through `render/Bounds.kt`: finite, not negative, at most 8192.
  Theme numbers are held to the ranges of `theme.json` (`ThemeBounds`); one value outside them (or not finite) is
  replaced by the built-in theme's value of the same token and field, otherwise by the nearest bound (a part of a
  shadow or a gradient always takes the bound), and reported. `fontWeight` must be a multiple of 100.
- The screen draws only inside the box the host gave it; an overlay, a wide shadow or a pulse leaves the
  host's page untouched, and a tap where an overlay would be drawn goes to the host.
- A list draws at most `x-delivery.maxCollectionItems` (50) records.
- Endless node motion asks for no frame under "reduce motion".

The 50-record ceiling applies per `Repeater`, and a document decides the nesting, so nested lists multiply it:
a screen draws at most 2550 instances of list templates, counted in document order depth first over the tree of
the screen for its width class (`ListBudget`); the instance that would pass the budget is not drawn, nor anything
after it, and one report says how many were left out. A modal layer and the page under it have a budget each.

## Accessible names

A pressable card names only the children that are shown. A child hidden by `visibleIf` or by `showOn` for
the current width class is not read out, because what a condition hides may be exactly what must not be said.

## Logs

The library writes nothing to `android.util.Log`, standard output or standard error, and none of the
values of the data, typed text, presses, or broken documents reach them. Reasons go only to the host
through `onReport`.
