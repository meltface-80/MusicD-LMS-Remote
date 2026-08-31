"use strict";

/* The glass material, pinned against the source (v1.0.84).
 *
 * WHY MOST OF THIS IS STATIC. Two of the invariants below cannot be measured in
 * a headless browser at all:
 *
 *   - every `env(safe-area-inset-*)` resolves to 0 in headless Chromium, so a
 *     transport that applies its bottom inset TWICE — lifted by `bottom` and
 *     padded as well — measures as a perfectly proportioned pill and only shows
 *     34px of dead glass on a device that has a home indicator;
 *   - a `backdrop-filter` on a surface that sits over a scroller is not a
 *     rendering bug, it is a FRAME-RATE bug on iOS Safari plus a visible
 *     brightness shift from `saturate()`. Nothing here can see either.
 *
 * The rest is static because it is a rule about the SOURCE, not about a
 * rendered pixel: --bg-veil must be the palette's own --bg with alpha. That is
 * what makes a veiled bar composite to exactly the page colour where nothing is
 * behind it, so no screen has a step under its header. Recomputed from --bg
 * here rather than eyeballed, so a palette whose ground moves and whose veil
 * does not fails the build.
 */

const fs = require("fs");
const path = require("path");

const CSS = fs.readFileSync(path.join(__dirname, "..", "public", "style.css"), "utf8");
let pass = 0, fail = 0;
const ok = (l) => { pass++; console.log("  [PASS]", l); };
const bad = (l, m) => { fail++; console.log("  [FAIL]", l, "—", m); };

// Comment-stripped, so the prose explaining these rules cannot satisfy them.
const code = CSS.replace(/\/\*[\s\S]*?\*\//g, "");

// Every declaration block whose selector list CONTAINS `sel`, wherever it is —
// including inside an @media, and including a comma list, because unlike
// lib/safearea.test.js this file has no interest in how a rule is grouped, only
// in what it declares. A regex cannot do that reliably (a selector's own text
// appears inside longer descendant selectors), so this walks braces and splits
// each prelude on commas.
function rules(sel) {
  const want = sel.replace(/\s+/g, " ").trim();
  const out = [];
  let depth = 0, start = 0;
  for (let i = 0; i < code.length; i++) {
    const c = code[i];
    if (c === "{") {
      if (depth === 0) {
        const prelude = code.slice(start, i).replace(/\s+/g, " ").trim();
        // An at-rule prelude (@media …) opens a nested block, not a rule.
        if (!prelude.startsWith("@")) {
          const parts = prelude.split(",").map((x) => x.trim());
          if (parts.includes(want)) {
            // Find this block's matching close brace.
            let d = 1, j = i + 1;
            for (; j < code.length && d > 0; j++) {
              if (code[j] === "{") d++;
              else if (code[j] === "}") d--;
            }
            out.push(code.slice(i + 1, j - 1));
          }
        }
      }
      depth++;
    } else if (c === "}") {
      depth--;
      if (depth < 0) depth = 0;
      start = i + 1;
    } else if (depth === 0 && start === i && /\s/.test(c)) {
      start = i + 1;
    }
  }
  return out;
}
const rule = (sel) => { const r = rules(sel); return r.length ? r.join("\n") : null; };

// The four palette blocks, by the selector each one is keyed on.
// `:root` matches by itself because the walk splits the comma list — which is
// also the point: the dark-classic block is `:root, [data-theme="dark"]…`, and
// a lookup that demanded the whole prelude verbatim would break the moment
// either half moved.
const PALETTES = [
  [":root", "dark classic"],
  ["[data-theme=\"light\"]", "light classic"],
  ["[data-theme=\"dark\"][data-palette=\"copper\"]", "copper dark"],
  ["[data-theme=\"light\"][data-palette=\"copper\"]", "brass light"],
];

function tokens(sel) {
  const body = rule(sel);
  if (body === null) return null;
  const out = {};
  for (const m of body.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

const hexToRgb = (h) => {
  const m = /^#([0-9a-f]{6})$/i.exec(h.trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
};
const parseRgba = (v) => {
  const m = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(v.trim());
  return m ? { rgb: [+m[1], +m[2], +m[3]], a: m[4] === undefined ? 1 : +m[4] } : null;
};

// ---------------------------------------------------------------------------
// 1. Every palette carries the whole material, and the veil IS its ground.
// ---------------------------------------------------------------------------
const NEEDED = ["--bg-veil", "--glass-edge", "--glass-fill", "--glass-fill-strong"];
for (const [sel, name] of PALETTES) {
  const t = tokens(sel);
  if (!t) { bad(name, "palette block not found — was its selector changed?"); continue; }
  const missing = NEEDED.filter((k) => !(k in t));
  if (missing.length) { bad(name, "missing " + missing.join(", ")); continue; }

  const ground = hexToRgb(t["--bg"] || "");
  const veil = parseRgba(t["--bg-veil"]);
  if (!ground) { bad(name, "--bg is not a plain hex colour: " + t["--bg"]); continue; }
  if (!veil) { bad(name, "--bg-veil is not an rgba(): " + t["--bg-veil"]); continue; }
  if (veil.rgb.join(",") !== ground.join(","))
    bad(name, `--bg-veil is rgb(${veil.rgb}) but --bg is rgb(${ground}) — the veil must be` +
              " THE ground with alpha, or a veiled bar shows a step wherever nothing is behind it");
  else if (!(veil.a > 0.5 && veil.a < 1))
    bad(name, `--bg-veil alpha ${veil.a} — it has to be translucent, and opaque enough to`
              + " keep the bar's text legible over a wall of covers");
  else ok(`${name}: --bg-veil is --bg with alpha ${veil.a}`);

  for (const k of ["--glass-edge", "--glass-fill", "--glass-fill-strong"]) {
    const c = parseRgba(t[k]);
    if (!c || !(c.a > 0 && c.a < 1)) bad(name, `${k} must be a translucent rgba(): ${t[k]}`);
  }
}

// ---------------------------------------------------------------------------
// 1b. THE BAR'S TEXT SURVIVES WHATEVER SCROLLS UNDER IT.
// ---------------------------------------------------------------------------
// This is the assertion that actually constrains the alpha, and it is the one
// thing the whole design risks: the top bar and the transport pill are now
// see-through, and what shows through them is album art, which can be anything
// from a black sleeve to a white one. Composite each palette's veil over BOTH
// extremes and require the bar's own text to clear 4.5:1 on each. A veil that
// looked fine over the app's ground can fail badly over one wall of covers.
const relLum = ([r, g, b]) => {
  const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
};
const contrast = (a, b) => {
  const l1 = relLum(a), l2 = relLum(b);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
};
const composite = (fg, alpha, backdrop) =>
  fg.map((c, i) => Math.round(c * alpha + backdrop[i] * (1 - alpha)));

for (const [sel, name] of PALETTES) {
  const t = tokens(sel);
  if (!t) continue;
  const veil = parseRgba(t["--bg-veil"]);
  const text = hexToRgb(t["--text"] || "");
  const dim = hexToRgb(t["--text-dim"] || "");
  if (!veil || !text || !dim) { bad(name, "cannot read --bg-veil / --text / --text-dim"); continue; }
  let worstText = Infinity, worstDim = Infinity;
  for (const backdrop of [[255, 255, 255], [0, 0, 0]]) {
    const bar = composite(veil.rgb, veil.a, backdrop);
    worstText = Math.min(worstText, contrast(text, bar));
    worstDim = Math.min(worstDim, contrast(dim, bar));
  }
  if (worstText >= 4.5)
    ok(`${name}: the bar's text clears 4.5:1 over any cover (worst ${worstText.toFixed(2)})`);
  else
    bad(name, `the bar's text measures ${worstText.toFixed(2)}:1 against the veil composited over`
            + " the worst-case cover — raise --bg-veil's alpha or the text's contrast");
  // Secondary text on the bar (the search placeholder, the album count) is held
  // to the lower tier the rest of the app uses for --text-dim.
  if (worstDim >= 3.0)
    ok(`${name}: the bar's secondary text clears 3:1 (worst ${worstDim.toFixed(2)})`);
  else
    bad(name, `the bar's secondary text measures ${worstDim.toFixed(2)}:1 over the worst-case cover`);
}

// The token it replaced must be gone in both directions: no definition left
// behind, and no rule still reading one that nothing defines.
if (!/--bg-translucent/.test(CSS)) ok("--bg-translucent is gone (one material, not two)");
else bad("--bg-translucent", "still referenced — --bg-veil replaced it");

// ---------------------------------------------------------------------------
// 2. NO backdrop-filter on anything that sits over a scroller.
// ---------------------------------------------------------------------------
// Modal BACKDROPS keep theirs: nothing moves behind a scrim, so there is
// nothing to re-blur per frame. These are the surfaces content really does
// scroll under.
const NO_FILTER = [
  ".topbar", ".mini-transport", ".filter-bar", ".mt-vol-popover",
  ".mt-zone-popover", ".np-popover", ".dropdown-menu", ".menu-drawer",
  ".settings-sheet", ".lib-sheet", ".toast",
];
for (const sel of NO_FILTER) {
  const bodies = rules(sel);
  if (!bodies.length) { bad(sel, "rule not found — was it renamed?"); continue; }
  if (bodies.some((b) => /backdrop-filter/.test(b)))
    bad(sel, "carries a backdrop-filter; iOS Safari re-blurs everything beneath one on"
           + " EVERY scroll frame, and saturate() visibly brightens what shows through");
  else ok(`${sel} has no backdrop-filter`);
}

// ---------------------------------------------------------------------------
// 3. The chrome that floats really is glass.
// ---------------------------------------------------------------------------
for (const sel of [".topbar", ".mini-transport", ".menu-drawer", ".settings-sheet",
                   ".lib-sheet", ".mt-vol-popover", ".mt-zone-popover", ".np-popover",
                   ".share-panel", ".confirm-box", ".album-edit-sheet",
                   ".label-unmerge-sheet", ".label-merge-bar"]) {
  const bodies = rules(sel);
  if (bodies.some((b) => /background:\s*var\(--bg-veil\)/.test(b))) ok(`${sel} is glass`);
  else bad(sel, "does not take var(--bg-veil)");
}
// …AND THE DECISION SURFACES ARE NOT. These float over live content with no
// scrim between, and they carry two tiers of text — a row plus the sentence
// under it saying what it does. At 84% over a wall of album covers the second
// tier stops being readable. They keep the lit edge and the shadow so they
// still belong to the set; only the background is opaque.
for (const sel of [".dropdown-menu", ".toast", ".settings-info-toast"]) {
  const bodies = rules(sel);
  if (!bodies.length) { bad(sel, "rule not found"); continue; }
  if (bodies.some((b) => /background:\s*var\(--bg-veil\)/.test(b)))
    bad(sel, "takes the veil — its second tier of text is unreadable over album art");
  else if (bodies.some((b) => /border:\s*1px solid var\(--glass-edge\)/.test(b)))
    ok(`${sel} is opaque, with the lit edge`);
  else bad(sel, "opaque but has lost its --glass-edge, so it no longer matches the set");
}
// The pill's border and shadow are load-bearing, not decoration: where nothing
// is scrolled behind it the veil settles to exactly --bg, and these two are all
// that keep it from reading as a hole in the page.
{
  const b = rules(".mini-transport").join("\n");
  if (/border:\s*1px solid var\(--glass-edge\)/.test(b)) ok(".mini-transport has a lit edge");
  else bad(".mini-transport", "lost its --glass-edge border");
  if (/box-shadow:/.test(b)) ok(".mini-transport has a drop shadow");
  else bad(".mini-transport", "lost its drop shadow");
}

// ---------------------------------------------------------------------------
// 4. The top bar overlays the scroller, and <main> reserves its height.
// ---------------------------------------------------------------------------
{
  const b = rule(".topbar");
  if (b && /position:\s*absolute/.test(b)) ok(".topbar overlays the scroller");
  else bad(".topbar", "is not position: absolute — nothing would be behind it to show through");
}
if (/main\s*\{[^}]*padding-top:\s*calc\(var\(--topbar-h\)/.test(code))
  ok("main reserves the overlaid bar's measured height");
else bad("main", "does not reserve var(--topbar-h) — content would start under the bar");
if (/--topbar-h:/.test(code)) ok("--topbar-h has a no-JS fallback");
else bad("--topbar-h", "no fallback value in the stylesheet");
{
  // .filter-bar sticks BELOW the bar. A hard-coded height here is what it used
  // to be, and it drifted the moment the bar's padding changed.
  const b = rule(".filter-bar");
  if (b && /top:\s*var\(--topbar-h\)/.test(b)) ok(".filter-bar sticks to the measured bar height");
  else bad(".filter-bar", "does not stick to var(--topbar-h)");
}

// ---------------------------------------------------------------------------
// 5. Dismiss controls never scroll away.
// ---------------------------------------------------------------------------
// Each entry is a host that CONTAINS a back or close control and lives inside a
// scroller. If the host is not pinned, its control scrolls off the top — which
// is exactly the defect this release was asked to fix, on eight screens.
const PINNED = [
  [".settings-head", "Settings home / overlay titles"],
  [".settings-pane-head", "every Settings pane's back chevron"],
  [".qobuz-sheet .qobuz-pin", "Favourites / Merged / Playlists / Dynamic Playlists / Qobuz / Pitchfork"],
  [".menu-head", "the side menu's close"],
  [".labels-bar", "the labels browser's second-level back"],
  [".pf-back", "the Pitchfork review's back"],
  [".album-edit-header", "the album-edit sheet's close"],
  [".label-unmerge-header", "the label-unmerge sheet's close"],
  [".track-select-row", "track multi-select's cancel"],
];
for (const [sel, what] of PINNED) {
  const b = rule(sel);
  if (b === null) { bad(sel, "rule not found — was it renamed?"); continue; }
  if (/position:\s*sticky/.test(b)) ok(`${sel} is pinned (${what})`);
  else bad(sel, `is not position: sticky — ${what} would scroll away`);
}
// The artist view has no back control of its own any more: it uses the top
// bar's, via an override on the shared chevron. A rule for `.artist-view-back`
// coming back means someone reintroduced the in-flow button that scrolled away.
if (!/\.artist-view-back\s*\{/.test(code)) ok("the artist view uses the pinned top-bar chevron");
else bad(".artist-view-back", "the in-flow artist Back button is back — it scrolls off the top");

// The panel's corner buttons are pinned the other way: absolutely positioned on
// .modal-panel, i.e. OUTSIDE .modal-body, which is what scrolls there.
for (const sel of [".modal-close", ".modal-chrome", ".modal.np-mode .modal-home"]) {
  const b = rule(sel);
  if (b && /position:\s*absolute/.test(b)) ok(`${sel} is pinned to the panel`);
  else bad(sel, "is not absolutely positioned on .modal-panel — it would scroll with the body");
}
// The four overlays that were missing from the old id list are covered by a
// class selector now, so the list cannot fall out of step with the markup.
if (!/#playlists-overlay\s+\.qobuz-pin/.test(code)) ok("the sheet pin is keyed on its class, not a list of ids");
else bad(".qobuz-pin", "back to an id list — the four overlays it missed will drift out again");

// ---------------------------------------------------------------------------
// 6. Sticky bands over LIVE content stay opaque.
// ---------------------------------------------------------------------------
// A sticky head inside a translucent SHEET can take the sheet's veil: two 84%
// layers composite to 97.4% and nothing shows through. A sticky band over the
// page or over the album modal has only ONE layer, so a veil there ghosts the
// rows passing beneath it at 16%.
for (const sel of [".filter-bar", ".track-select-row", ".labels-bar"]) {
  const b = rule(sel);
  if (b === null) { bad(sel, "rule not found"); continue; }
  if (/background:\s*var\(--bg-veil\)/.test(b))
    bad(sel, "takes the veil, but it is a single layer over live content — rows would ghost through");
  else ok(`${sel} stays opaque over live content`);
}

console.log(fail ? `\n${fail} FAILED (${pass} passed)` : `\n${pass}/${pass} glass invariants hold.`);
process.exit(fail ? 1 : 0);
