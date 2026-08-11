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

function ruleBody(sel) {
  const re = new RegExp(sel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s*\\{([^}]*)\\}");
  const m = code.match(re);
  return m ? m[1] : null;
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
}
// `main`'s reserve lives in a media query, not the base rule, so match the
// declaration wherever it is rather than looking inside one rule body.
if (/main\s*\{[^}]*padding-bottom:\s*calc\(80px \+ env\(safe-area-inset-bottom\)\)/.test(code))
  ok("main keeps its bottom reserve (clearing the transport + the indicator)");
else bad("main", "lost its bottom safe-area reserve");

// The runtime viewport shim is gone (v1.0.78): it moved every full-screen
// fixed container off a measurement, and it was built on a diagnosis the
// v1.6.50 comparison replaced. v1.6.50 has nothing like it.
if (!/--app-gap/.test(CSS)) ok("no --app-gap shim left in the stylesheet");
else bad("--app-gap", "the runtime viewport shim is still referenced");

console.log(fail ? `\n${fail} FAILED (${pass} passed)` : `\n${pass}/${pass} safe-area invariants hold.`);
process.exit(fail ? 1 : 0);
