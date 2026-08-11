"use strict";

/* The installed app's viewport geometry, guarded as a static invariant.
 *
 * WHY THIS IS A TEST AND NOT A COMMENT: this one meta tag is the difference
 * between the app filling the display and the app being pushed off the bottom
 * of it, and it is invisible everywhere it could be caught. Headless Chromium
 * does not read it. A browser tab does not read it. Only an iPhone with the
 * app installed from the Home Screen reads it, which is precisely the thing no
 * harness here can be.
 *
 * `black-translucent` makes iOS run the web view FULL-BLEED under the status
 * bar. Installed, that shifted the whole app down the display and took its
 * bottom off the screen — the mini transport and the bottom of the Now playing
 * screen ended up below the visible area. Nothing inside the page can correct
 * that, which is why three releases of safe-area padding and panel-height
 * arithmetic measured green in every harness and all failed on the device.
 *
 * `black` keeps the app installed and chrome-free but has iOS lay the web view
 * out below the status bar as an ordinary viewport — the same geometry the
 * Roon build gets in Safari, which is why that build never had this bug.
 *
 * diag.html carries its own copy because it is a separate top-level document;
 * if the two ever disagree, the diagnostic stops describing the app it is
 * supposed to be diagnosing, which is worse than having no diagnostic.
 */

const fs = require("fs");
const path = require("path");

const PUB = path.join(__dirname, "..", "public");
let pass = 0, fail = 0;
const ok = (l) => { pass++; console.log("  [PASS]", l); };
const bad = (l, m) => { fail++; console.log("  [FAIL]", l, "—", m); };

const read = (f) => fs.readFileSync(path.join(PUB, f), "utf8");

// The meta, in both documents that declare it.
for (const file of ["index.html", "diag.html"]) {
  const html = read(file);
  const m = html.match(/<meta\s+name="apple-mobile-web-app-status-bar-style"\s+content="([^"]*)"/i);
  if (!m) { bad(file + " status-bar-style", "tag missing entirely"); continue; }
  if (m[1] === "black") ok(file + ' declares status-bar-style "black"');
  else bad(file + " status-bar-style", 'is "' + m[1] + '" — must be "black"; ' +
    '"black-translucent" pushes the installed app off the bottom of the display');
}

// Belt and braces: the string must not survive anywhere in a live tag, even if
// somebody adds a third document later. Comments explaining it are fine.
for (const file of ["index.html", "diag.html"]) {
  const html = read(file);
  const tags = html.match(/<meta[^>]*black-translucent[^>]*>/gi) || [];
  if (!tags.length) ok(file + " has no black-translucent meta tag");
  else bad(file + " black-translucent", tags.join(" | "));
}

// viewport-fit=cover must STAY. It is what makes env(safe-area-inset-*)
// resolve at all, and the bottom inset is still live for the home indicator
// even now that the top one resolves to 0. The Roon build sets it too, so this
// is not a difference — don't "simplify" it away while cleaning up the above.
{
  const html = read("index.html");
  const m = html.match(/<meta\s+name="viewport"\s+content="([^"]*)"/i);
  if (m && /viewport-fit\s*=\s*cover/.test(m[1]))
    ok("index.html keeps viewport-fit=cover, so the insets still resolve");
  else bad("viewport-fit", m ? m[1] : "no viewport meta");
}

// The app is still installable — the fix must not have cost the PWA.
{
  const html = read("index.html");
  const checks = [
    [/<link\s+rel="manifest"/i, "manifest link"],
    [/<meta\s+name="apple-mobile-web-app-capable"\s+content="yes"/i, "apple-mobile-web-app-capable"],
    [/<link\s+rel="apple-touch-icon"/i, "apple-touch-icon"],
  ];
  for (const [re, what] of checks) {
    if (re.test(html)) ok("still installable: " + what + " present");
    else bad("installability", what + " went missing");
  }
}

console.log(fail ? `\n${fail} FAILED (${pass} passed)` : `\n${pass}/${pass} PWA meta tests passed.`);
process.exit(fail ? 1 : 0);
