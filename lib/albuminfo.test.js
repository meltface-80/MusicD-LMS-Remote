"use strict";
// Biographies and reviews hard-wrapped at a fixed width read as paragraphs again (lib/albuminfo.js unwrapLines).
const { unwrapLines } = require("./albuminfo");
let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label, JSON.stringify(cond)); } }

const mai = "Jonathan Julian Hopkins (born 15 August 1979) is an English electronic\nmusician and producer. He began his career playing keyboards for Imogen\nHeap, and he has produced and contributed to albums by Brian Eno,\nColdplay, and David Holmes, among others.\n\nHopkins composed the soundtrack to the 2010 film Monsters, which was\nnominated for an Ivor Novello Award for Best Original Score. His third\nstudio album, Insides, reached No. 15 on the US Dance/Electronic Albums\nchart in 2009.";
const out = unwrapLines(mai);
ok("a wrapped paragraph is one line again", out.split("\n\n")[0] === "Jonathan Julian Hopkins (born 15 August 1979) is an English electronic musician and producer. He began his career playing keyboards for Imogen Heap, and he has produced and contributed to albums by Brian Eno, Coldplay, and David Holmes, among others.");
ok("the blank line between paragraphs is kept", out.split("\n\n").length === 2 && !/\n(?!\n)/.test(out.replace(/\n\n/g, "")));
ok("with blank-line paragraphs, a wrap after a full stop is still a wrap", /Best Original Score\. His third studio album/.test(out));
ok("CRLF is read as LF", unwrapLines("one\r\ntwo") === "one two");
ok("without blank lines, a break after a sentence's end stays a paragraph", unwrapLines("It ends here.\nThe next starts\nand wraps.") === "It ends here.\nThe next starts and wraps.");
ok("unwrapping twice changes nothing", unwrapLines(out) === out && unwrapLines("It ends here.\nThe next one.") === "It ends here.\nThe next one.");
ok("nothing in, nothing out", unwrapLines("") === "" && unwrapLines(null) === null);
console.log(`\n${pass}/${pass + fail} albuminfo tests passed.`);
if (fail) process.exit(1);
