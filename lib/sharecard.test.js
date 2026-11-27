// @ts-check
"use strict";
/*
 * The share card's layout (public/sharecard-layout.js), held to THE SAME CASES as Mallard's ShareCardTest.kt, with the
 * same stand-in font (half a size a letter): the page's card and the app's are two copies of one layout, and these two
 * suites are what keeps them one. Change a number here, change it there.
 */
const L = require("../public/sharecard-layout.js");

let pass = 0, fail = 0;
/** @param {string} label @param {boolean} cond */
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }
/** @param {string} t @param {number} _w @param {number} size */
const font = (t, _w, size) => t.length * size * 0.5;

{
  const l = L.measure(font, { title: "White Sky", artist: "Peter Green", releaseRaw: "1982", review: "", reviewSource: "" });
  ok("a card of art, title and artist is 600 tall", l.cardH === 600);
  ok("its release line", l.metaText === "Released 1982");
  ok("the title on one line at 56 on 68", l.title.lines.join("|") === "White Sky" && l.title.size === 56 && l.title.lh === 68);
  ok("the artist with its 'by'", l.artist.lines.join("|") === "by Peter Green");
  ok("no review, no block", l.desc === null);
  ok("the cover is the taller column", l.headerH === 464);
  ok("the cover centred in the 600, 68 from the top", l.artY === (600 - 464) / 2);
  ok("the words centred against it", l.textY === 68 + Math.round((464 - l.headerTextH) / 2));
}
{
  ok("a 580px text column", L.TEXT_W === 580);
  const mid = L.fit(font, "abc ".repeat(26).trim(), L.TEXT_W, 4, 700, [56, 48, 42, 36, 31, 27], 68 / 56);
  ok("a long title steps down: four lines first fit at 42", mid.size === 42 && mid.lines.length === 4 && !mid.lines[3].endsWith("…"));
  const long = L.fit(font, "word ".repeat(200).trim(), L.TEXT_W, 4, 700, [56, 48, 42, 36, 31, 27], 68 / 56);
  ok("only the smallest size is cut with an ellipsis", long.size === 27 && long.lines.length === 4 && long.lines[3].endsWith("…"));
  ok("the cut line still fits", font(long.lines[3], 700, 27) <= L.TEXT_W);
}
{
  const review = L.trimReview("Alpha beta gamma delta. ".repeat(80));
  const l = L.measure(font, { title: "White Sky", artist: "Peter Green", releaseRaw: "", review, reviewSource: "AllMusic" });
  const d = l.desc;
  ok("a review: 33 on 49", !!d && d.size === 33 && d.lh === 49);
  ok("the longest review the sheet sends comes through whole", !!d && d.lines.join(" ").split(/\s+/).length === review.split(/\s+/).length);
  ok("whose words they are", l.srcText === "AllMusic");
  ok("the card grows to hold it", !!d && l.contentH === 464 + 30 + 1 + 30 + d.lines.length * 49 + 26 + 24 && l.cardH === l.contentH + 56 * 2);
  ok("the hairline under the cover", l.ruleY === 56 + 464 + 30);
  ok("no review, no source", L.measure(font, { title: "T", artist: "A", review: "", reviewSource: "AllMusic" }).srcText === "");
}
ok("a full date", L.releaseDate("2006-11-20") === "20 Nov 2006");
ok("a month", L.releaseDate("2006-11") === "Nov 2006");
ok("a year, trimmed", L.releaseDate(" 2006 ") === "2006");
ok("anything else as it came", L.releaseDate("2006-13") === "2006-13");
ok("nothing, no line", L.releaseDate("") === null);
ok("ten sentences at most", L.trimReview(Array.from({ length: 14 }, (_, i) => `S${i + 1}.`).join(" ")) === Array.from({ length: 10 }, (_, i) => (i ? " " : "") + `S${i + 1}.`).join(" "));
{
  const t = L.trimReview("word ".repeat(400));
  ok("1400 characters, cut at a word", t.length <= 1399 && t.endsWith("word…"));
}
ok("the file's name", L.fileName("White Sky", "Peter Green") === "Peter_Green-White_Sky.png");

console.log(`\n${pass}/${pass + fail} share card layout tests passed.`);
if (fail) process.exit(1);
