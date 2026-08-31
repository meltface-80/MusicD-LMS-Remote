"use strict";

/* The modal family must NOT carry `env(safe-area-inset-top)`.
 *
 * WHY THIS IS A STATIC TEST: headless Chromium has no notch, so every inset
 * resolves to 0 and a rule that wrongly carries one measures identically to a
 * rule that does not. The defect is only visible on a real iPhone. So the
 * invariant is pinned against the source instead.
 *
 * THE HISTORY. The owner's report was that the now-playing screen and the
 * screens with the mini transport were "not OVERLAYING the iOS safe zone".
 * v1.0.69 and v1.0.70 read that backwards and added `env(safe-area-inset-top)`
 * across the modal family — which pads content OUT of the safe area, the
 * opposite of what was asked, and pushes the whole screen down by the
 * status-bar height.
 *
 * Diffing the Roon build's v1.6.50 (the last version that displays correctly
 * on the owner's phone) against its v1.7.43 (which has the same fault as ours)
 * isolated it exactly: every one of these top insets is present only in the
 * broken version. v1.6.50's values are what this test pins.
 *
 * The insets that REMAIN elsewhere are correct and are in v1.6.50 too — the
 * top bar, the filter bar and panel, the browse-sheet pin, the menu drawer.
 * Only the modal family must stay clear of them. Bottom insets are untouched
 * throughout: the home indicator is a real obstruction and v1.6.50 reserves
 * for it in the same places we do.
 */

const fs = require("fs");
const path = require("path");

const CSS = fs.readFileSync(path.join(__dirname, "..", "public", "style.css"), "utf8");
let pass = 0, fail = 0;
const ok = (l) => { pass++; console.log("  [PASS]", l); };
const bad = (l, m) => { fail++; console.log("  [FAIL]", l, "—", m); };

// Comment-stripped, so prose about the insets cannot trip the checks.
const code = CSS.replace(/\/\*[\s\S]*?\*\//g, "");

// The rule whose selector is EXACTLY `sel` — a STANDALONE rule, never a member
// of a comma list. CLAUDE.md requires these six to stay standalone; this is
// what enforces it, and it closes a hole that made the test go quietly vacuous
// rather than red.
//
// The hole: the previous version anchored on `(?:^|[},])`, and `[},]` includes
// the COMMA. So a comma list placed earlier in the file than the standalone
// rule — say `.modal-share,\n.modal-close { background: … }` — was matched
// instead, and the test then read that rule's body and never looked at the pin.
// Because every assertion here is "must NOT contain an inset", finding the
// wrong rule PASSES. (The Roon build's equivalent asserts the opposite way, so
// the same hijack fails loudly there.)
//
// Walking braces also lets a rule inside an @media be found, which the flat
// regex could not do at all.
function ruleBody(sel) {
  const want = sel.replace(/\s+/g, " ").trim();
  const stack = [];
  let start = 0;
  for (let i = 0; i < code.length; i++) {
    if (code[i] === "{") {
      stack.push({ prelude: code.slice(start, i).replace(/\s+/g, " ").trim(), open: i });
      start = i + 1;
    } else if (code[i] === "}") {
      const f = stack.pop();
      if (f) {
        const body = code.slice(f.open + 1, i);
        // A block with no nested block is a declaration block; an at-rule is not.
        if (!/[{}]/.test(body) && f.prelude === want) return body;
      }
      start = i + 1;
    }
  }
  return null;
}

// The modal family, with the value v1.6.50 uses.
const MUST_BE_CLEAN = [
  [".modal-close", "top", "12px"],
  [".modal-share", "top", "12px"],
  [".modal-edit", "top", "12px"],
  [".modal-fav", "top", "12px"],
  [".modal.np-mode .modal-home", "top", "12px"],
  [".modal.np-mode .modal-body", "padding-top", "14px"],
];
for (const [sel, prop, want] of MUST_BE_CLEAN) {
  const body = ruleBody(sel);
  if (body === null) { bad(sel, "rule not found — was it renamed?"); continue; }
  if (/safe-area-inset-top/.test(body))
    bad(sel, `carries a top inset; v1.6.50 uses a plain ${want} and that is what displays correctly`);
  else ok(`${sel} has no top inset`);
}

// .modal-body's own padding: the shorthand only, no inset-bearing longhand.
{
  const body = ruleBody(".modal-body");
  if (body === null) bad(".modal-body", "rule not found");
  else if (/padding-top:\s*calc\([^)]*safe-area-inset-top/.test(body))
    bad(".modal-body", "padding-top carries a top inset; v1.6.50 uses `padding: 64px 18px 40px`");
  else ok(".modal-body's top padding carries no inset");
}

// …AND ONLY ARTWORK IS ALLOWED TO GIVE THAT RESERVE UP (v1.0.85).
// The base rule's 64px top padding is what keeps the track list clear of the
// pinned corner buttons on every screen. The album-art hero zeroes it so the
// cover can reach the top of the panel — that is the ONE legitimate case, and
// only because what lands under those buttons is an image the same block
// scrims for them. Zeroing it anywhere else puts text under the back chevron.
// So: at most one `.modal-body` block may carry `padding-top: 0`, and its
// selector must name `:not(.np-mode)`.
//
// This walks braces rather than matching a regex over the whole file, because
// the hero lives inside an `@media (max-width: 719px)` block and a flat
// `prelude { body }` pattern reads the media query's own prelude instead —
// which silently matched NOTHING and reported a pass.
function declBlocks() {
  const out = [];
  const stack = [];
  let start = 0;
  for (let i = 0; i < code.length; i++) {
    if (code[i] === "{") {
      stack.push({ prelude: code.slice(start, i).trim(), open: i });
      start = i + 1;
    } else if (code[i] === "}") {
      const f = stack.pop();
      if (f) {
        // A block whose body contains no nested block is a declaration block;
        // an at-rule wrapper is not.
        const body = code.slice(f.open + 1, i);
        if (!/[{}]/.test(body) && !f.prelude.startsWith("@")) {
          out.push({ prelude: f.prelude.replace(/\s+/g, " ").trim(), body });
        }
      }
      start = i + 1;
    }
  }
  return out;
}
{
  const zeroed = declBlocks().filter((b) =>
    b.prelude.split(",").some((sel) => /\.modal-body$/.test(sel.trim())) &&
    /(^|;)\s*padding-top:\s*0(px)?\s*(;|$)/.test(b.body));
  if (zeroed.length === 1 && /:not\(\.np-mode\)/.test(zeroed[0].prelude))
    ok("only the album hero gives up .modal-body's top reserve");
  else if (zeroed.length === 0)
    bad(".modal-body", "nothing zeroes its top padding — the album hero's rule has gone, " +
                       "or this check has stopped finding it (it found the hero when written)");
  else
    bad(".modal-body",
        `${zeroed.length} rule(s) zero its top padding and one is not the hero — text would ` +
        `land under the pinned back chevron. Selectors: ` +
        zeroed.map((b) => b.prelude).join(" / "));
}

// The insets that MUST stay. Removing these is the opposite mistake, and the
// top bar one in particular is in v1.6.50.
const MUST_KEEP = [".topbar", ".menu-drawer"];
for (const sel of MUST_KEEP) {
  const body = ruleBody(sel);
  if (body === null) bad(sel, "rule not found");
  else if (/safe-area-inset-top/.test(body)) ok(`${sel} keeps its top inset (correct, and in v1.6.50 too)`);
  else bad(sel, "lost its top inset — this one is legitimate");
}

// Bottom reserves are a separate matter: the home indicator really does sit
// there, and v1.6.50 reserves for it in the same places.
{
  const body = ruleBody(".mini-transport");
  if (body && /safe-area-inset-bottom/.test(body)) ok(".mini-transport keeps its bottom reserve");
  else bad(".mini-transport", "lost its bottom safe-area reserve");
  // LIFT OR PAD, NEVER BOTH (v1.0.84). The transport is a floating pill now:
  // its `bottom` carries the inset, which moves the whole pill clear of the
  // home indicator. The old full-bleed bar padded instead. Doing both puts 34px
  // of dead glass inside the pill on any device that has an indicator — and
  // headless Chromium reports every inset as 0, so it measures a perfectly
  // proportioned pill either way. Counted across EVERY .mini-transport block,
  // because the sizing overrides live 600 lines from the base rule.
  const blocks = code.match(/(?:^|[},])\s*\.mini-transport\s*\{[^}]*\}/gm) || [];
  const n = blocks.filter((b) => /safe-area-inset-bottom/.test(b)).length;
  if (n === 1) ok(".mini-transport applies the bottom inset exactly once");
  else bad(".mini-transport", `applies the bottom inset ${n} times — lift or pad, never both`);
}
// `main`'s reserve lives in a media query, not the base rule, so match the
// declaration wherever it is rather than looking inside one rule body. The
// CONSTANT is deliberately not pinned: it tracks the transport's height, which
// grew when the bar became a floating pill (v1.0.84). What must never go is the
// inset — that is the invariant this test is named for.
if (/main\s*\{[^}]*padding-bottom:\s*calc\(\d+px \+ env\(safe-area-inset-bottom\)\)/.test(code))
  ok("main keeps its bottom reserve (clearing the transport + the indicator)");
else bad("main", "lost its bottom safe-area reserve");

// The runtime viewport shim is gone (v1.0.78): it moved every full-screen
// fixed container off a measurement, and it was built on a diagnosis the
// v1.6.50 comparison replaced. v1.6.50 has nothing like it.
if (!/--app-gap/.test(CSS)) ok("no --app-gap shim left in the stylesheet");
else bad("--app-gap", "the runtime viewport shim is still referenced");

console.log(fail ? `\n${fail} FAILED (${pass} passed)` : `\n${pass}/${pass} safe-area invariants hold.`);
process.exit(fail ? 1 : 0);
