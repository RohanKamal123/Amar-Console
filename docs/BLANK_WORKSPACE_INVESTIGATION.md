# The OpenHands workspace renders blank in the embedded WebView

Open investigation. Chrome on the same phone, against the same server, renders the
same URL correctly. The app's embedded WebView shows a blank page.

Everything below is **measured**, not inferred. Several earlier rounds were lost to
fixes shipped on plausible-sounding theories, so this file records what was tested and
what the test returned. Add to it the same way.

## Environment

- OpenHands 0.62.0 (self-hosted OSS, Docker) at `http://100.87.52.65:3000`
- Phone reaches it over Tailscale; services bind the tailnet IP only, nothing on loopback
- WebView engine reports `Chrome 151`; phone is a Tecno Camon 40
- Diagnostics run from the app's command bar: `/diag`, `/plain`, `/clearcache`, `/devtools`

## Current state of the page

```
html:       0px  block
body:       0px  block
rootLayout: 0px  flex  overflow:hidden
viewport:   380x566      bodyChildren: 13
appRoute:   absent       chatInput:    absent
```

One console error, every load:

```
Uncaught Error: Minified React error #418
  entry.client-D7JCGg8F.js:28:24704
```

React #418 is a hydration failure — the server's markup did not match the client's
first render.

## Ruled out, with the evidence

| Theory | How it died |
|---|---|
| Stale WebView cache serving an old `index.html` | `/clearcache` and a manual Storage → Clear cache both produced byte-identical reports |
| Old WebView engine that cannot parse a modern bundle | `engine: Chrome 151` |
| U+2028/U+2029 in an inline script | `hasLineSeparator: false` on all five; a byte scan of the served document reports `odd: "none"` |
| The Claude stylesheet collapsing the layout | Blank with `styled:false`, and with `applyStyling` off entirely |
| A bundle answered by the SPA catch-all as `text/html` | All 17 referenced assets return 200 with correct content types, from both curl and `fetch` inside the WebView |
| The server varying its response by User-Agent | Document is 2881 bytes under both curl's and the WebView's UA |
| A missing `html { height: 100% }` rule | No such rule exists in the served CSS at all — heights are content-driven, so zero height means *nothing rendered*, not a broken chain |
| Any script this app injects | `/plain` reloads with every injection disabled. Still blank. |

## Found and fixed along the way

`claude_workspace.js` named the `__CLAUDE_CSS__` placeholder in its own doc comment, and
`WorkspaceStyleInjector` substituted it with `String.replace`, which rewrites *every*
occurrence. The quoted stylesheet went into the comment too, and CSS contains `*/`, so
the comment closed early and left stylesheet text in the file as bare JavaScript.

That produced `Uncaught SyntaxError: Invalid or unexpected token` at line 9 on every
load — attributed to the *page's* URL, because `evaluateJavascript` runs in the
document's context and carries no filename of its own. Four per load: `onPageFinished`
plus each `doUpdateVisitedHistory`.

**Those errors were read as the OpenHands bundle failing to parse, and three separate
theories were built on them.** They were this app's own bug. Fixed in `67ab206`;
`substitute` now requires exactly one placeholder and the generated script is parsed by
`node` in a unit test.

The lesson worth keeping: an error reported against the page's URL is not necessarily
the page's error.

## What is not yet known

Why React's hydration fails here and succeeds in Chrome on the same device. The
remaining candidates, none confirmed:

- **A hydration input differing between WebView and Chrome.** `navigator.languages`
  drives i18next; a client that picks a different language than the server rendered
  produces exactly this. Colour scheme, time zone, cookie and `localStorage`
  availability are the others. `/diag` now reports all of them under `environment`.
- **The frontend's own first data call failing.** `/api/options/config` is a different
  path from the static assets — cookies, credentials, a different handler. `/diag` now
  fetches it and reports status, content type and size.
- **What is actually in the DOM.** 13 body children totalling zero height, contents
  unknown. `/diag` now reports `bodyOutline`: tag, testid, class, child count, measured
  box and leading text for each.

### Getting Chrome's side of the comparison

Chrome for Android refuses `data:` URLs typed into the address bar — it searches
instead, which has already produced a fabricated answer that looked like a real
measurement. Serve a real page from the VPS and open it in both browsers:

```bash
mkdir -p /tmp/probe && cat > /tmp/probe/index.html <<'HTML'
<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="font:14px monospace;padding:16px"><pre id=o></pre><script>
o.textContent = [
  'ua: ' + navigator.userAgent,
  'language: ' + navigator.language,
  'languages: ' + (navigator.languages || []).join(','),
  'colorScheme: ' + (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
  'timeZone: ' + Intl.DateTimeFormat().resolvedOptions().timeZone,
  'cookies: ' + navigator.cookieEnabled
].join('\n');
</script>
HTML
cd /tmp/probe && python3 -m http.server 8099 --bind 100.87.52.65
```

Open `http://100.87.52.65:8099/` in Chrome. Compare against the `environment` block
`/diag` reports from inside the WebView.

## Ground rules that earned their place here

- Do not ship a fix for an unconfirmed cause. Measure first.
- An error blamed on the page may belong to this app. `/plain` is the control.
- Debug builds carry the commit in the version name (`0.1.0-debug+<sha>`). Check it
  before trusting a test result — a stale install already cost one full round.
