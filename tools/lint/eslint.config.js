// @ts-check
"use strict";
/*
 * ESLint (docs/quality-plan.md): the recommended rules over the server, the tools and the web app, run from the
 * repository root (npm run lint). What they found on the day the gate came is recorded in
 * tools/lint/eslint-suppressions.json (ESLint's own suppressions file) and may only go down: a new finding anywhere
 * fails, and a suppressed one that is fixed fails too until `npm run lint:prune` takes it out. Rules are added here as
 * the suppressions empty, never loosened.
 */
const js = require("@eslint/js");
const globals = require("globals");

// The server, its library modules and their tests, and the tools: Node, CommonJS.
const node = {
  files: ["index.js", "launcher.js", "lib/**/*.js", "tools/**/*.js"],
  languageOptions: { ecmaVersion: 2024, sourceType: "commonjs", globals: { ...globals.node, ...globals.es2024 } }
};
// The web app (the PWA iPhones and iPads install) and the wall display: classic scripts in a browser.
const pages = {
  files: ["public/**/*.js"],
  languageOptions: { ecmaVersion: 2024, sourceType: "script", globals: { ...globals.browser, ...globals.es2024 } }
};
// The share card is three classic scripts, each binding one global the next reads, in the order index.html loads
// them (sharecard-layout.js, sharecard.js, then share-sheet.js after app.js). The layout is also required by its Node
// test (lib/sharecard.test.js), hence its guarded module.exports.
const shareCard = {
  files: ["public/sharecard.js", "public/share-sheet.js"],
  languageOptions: { globals: { ShareCardLayout: "readonly", ShareCard: "readonly" } }
};
// Page scripts the server (or a Node test) also requires: sharecard-layout.js (lib/sharecard.test.js) and
// shelf-format.js (lib/shelf-routes.js, the Formats tiles Mallard is sent).
const shareCardLayout = {
  files: ["public/sharecard-layout.js", "public/shelf-format.js"],
  languageOptions: { globals: { module: "readonly" } }
};
const rules = {
  rules: {
    // An argument or a caught error left unused is the code's style here (catch (e) { /* why */ }).
    "no-unused-vars": ["error", { args: "none", caughtErrors: "none", varsIgnorePattern: "^_" }],
    "no-empty": ["error", { allowEmptyCatch: false }],
    "no-constant-condition": ["error", { checkLoops: false }],
    // Names and tags are stripped of control characters on purpose (\x00-\x1f in a pattern).
    "no-control-regex": "off",
    "no-redeclare": ["error", { builtinGlobals: false }]
  }
};
// A function declared inside a block, in a page a browser runs. Old Safari (iOS 16, an iPad still in use) mishandles
// one in a top-level block that reads the block's let or const: "Cannot access uninitialized variable", thrown as the
// page starts. A const holding the function says the same everywhere. (From Mandarin, its item 538.)
const browserPages = {
  files: ["public/**/*.js"],
  rules: { "no-inner-declarations": ["error", "functions", { blockScopedFunctions: "disallow" }] }
};

module.exports = [
  { ignores: [".claude/**", "node_modules/**", "**/node_modules/**", "android/**", "docs/**", "data/**"] },
  js.configs.recommended,
  node,
  pages,
  shareCard,
  shareCardLayout,
  rules,
  browserPages
];
