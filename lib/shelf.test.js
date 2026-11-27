// @ts-check
"use strict";
/*
 * The Shelf (lib/shelf.js, lib/shelf-routes.js): the letter an artist is filed under, the list the page is given, and
 * the switch — off by default, and OFF MEANS THE WORK DOES NOT RUN: the list answers 403 without the index being read.
 */
const { shelfBucket, buildShelf, shelfSignature } = require("./shelf");
const { mountShelf, factsOf } = require("./shelf-routes");

let pass = 0, fail = 0;
/** @param {string} label @param {boolean} cond */
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }

ok("letters", shelfBucket("beatles") === "B" && shelfBucket("zz top") === "Z" && shelfBucket("2pac") === "2");
ok("a 0, a symbol or nothing is #", shelfBucket("0 5") === "#" && shelfBucket("!!!") === "#" && shelfBucket("") === "#" && shelfBucket(undefined) === "#");

/** @type {any[]} */
const recs = [
  { offset: 3, title: "Homogenic", subtitle: "Björk", image_key: "k3", genre: "Electronic", year: 1997, sortArtist: "bjork", quality: "FLAC 16/44.1", fmtType: "flc", fmtRate: 44100, fmtBits: 16 },
  { offset: 1, title: "Mezzanine (Deluxe Edition)", subtitle: "Massive Attack", image_key: "k1", genre: "Electronic", year: 1998, sortArtist: "massive attack" },
  { offset: 2, title: "OK Computer", subtitle: "Radiohead", image_key: null, genre: null, year: 0, sortArtist: "radiohead" },
];
const fns = {
  genresOf: (/** @type {any} */ al) => (al.genre ? [al.genre] : []),
  firstOf: (/** @type {any} */ al) => al.sortArtist,
  yearOf: (/** @type {any} */ al) => (al.year > 0 ? al.year : null),
  formatOf: (/** @type {any} */ al) => al.quality || null,
  favOf: (/** @type {any} */ al) => al.offset === 2,
  labelOf: () => null,
};
const s = buildShelf(recs, fns);
ok("albums in the order given, keyed short", s.albums.map((a) => a.o).join() === "3,1,2" && s.albums[0].t === "Homogenic" && s.albums[0].a === "Björk");
ok("genres counted, commonest first, and indexed", s.genres.length === 1 && s.genres[0].name === "Electronic" && s.genres[0].count === 2 && s.albums[0].g[0] === 0);
ok("an album with no genre has none", s.albums[2].g.length === 0);
ok("filed under its artist's letter", s.albums.map((a) => a.b).join("") === "BMR");
ok("an undated album has no year", s.albums[2].y === null && s.albums[0].y === 1997);
ok("its format", s.albums[0].fm === "FLAC 16/44.1" && s.albums[1].fm === null);
ok("a favourite is marked", s.albums[2].fv === 1 && s.albums[0].fv === 0);
ok("the version is taken off the title for the spine", JSON.stringify(s.albums[1].v) === JSON.stringify(["Mezzanine", "Deluxe Edition"]));
ok("no catalogue number or release kind on LMS", s.albums.every((a) => a.c === null && a.ty === null));
ok("the same shelf, the same signature", shelfSignature(s) === shelfSignature(buildShelf(recs, fns)));
ok("a favourite changed, a new signature", shelfSignature(s) !== shelfSignature(buildShelf(recs, Object.assign({}, fns, { favOf: () => false }))));

ok("a FLAC's facts as the Formats tab reads them", JSON.stringify(factsOf({ fmtType: "flc", fmtRate: 96000, fmtBits: 24 })) === JSON.stringify([1, 96000, 24, null, null, null, 0, 0]));
ok("an MP3 is lossy by its file", (factsOf({ fmtType: "mp3", fmtRate: 44100, fmtBits: 16 }) || [])[0] === 4);
ok("a streamed album with nothing read is filed under its service", JSON.stringify(factsOf({ source: "qobuz" })) === JSON.stringify([15, null, null, null, null, null, 1, 0]));
ok("nothing known, no facts", factsOf({}) === null);

// The routes, on a stand-in app.
/** @type {Record<string, Function>} */
const routes = {};
const app = { get: (/** @type {string} */ p, /** @type {Function} */ f) => { routes["GET " + p] = f; }, post: (/** @type {string} */ p, /** @type {Function} */ f) => { routes["POST " + p] = f; } };
let flag = false, reads = 0;
mountShelf(app, {
  publicDir: "/pub",
  features: { enabled: (id) => id === "shelf" && flag, setEnabled: (id, v) => { if (id === "shelf") flag = v; } },
  connected: () => true,
  ensureIndex: async () => {},
  builtAt: () => 1,
  libraryView: () => { reads++; return recs; },
  favOf: fns.favOf, labelOf: fns.labelOf, formatOf: fns.formatOf,
  records: () => recs.concat([{ offset: 4, title: "Vespertine", subtitle: "Björk & Matmos", image_key: "k4", year: 2001 }]),
  mainArtists: (sub) => String(sub).split(/ & /).map((n) => ({ name: n, k: n.toLowerCase() })),
});
/** @param {string} key @param {any} req */
async function call(key, req) {
  /** @type {any} */
  const out = { status: 200, body: null, file: null };
  const res = { status(/** @type {number} */ c) { out.status = c; return res; }, json(/** @type {any} */ b) { out.body = b; return res; }, sendFile(/** @type {string} */ f) { out.file = f; } };
  await routes[key](Object.assign({ query: {}, body: {} }, req), res);
  return out;
}
(async () => {
  let r = await call("GET /api/shelf/albums", {});
  ok("off: 403, and the index is not read", r.status === 403 && r.body.off === true && reads === 0);
  ok("off: /shelf says how to switch it on", (await call("GET /shelf", {})).file === "/pub/shelf-off.html");
  ok("the switch reads off", (await call("GET /api/settings/shelf", {})).body.enabled === false);
  ok("a switch that isn't true or false is refused", (await call("POST /api/settings/shelf", { body: { enabled: "yes" } })).status === 400 && !flag);
  r = await call("POST /api/settings/shelf", { body: { enabled: true } });
  ok("switched on", r.body.enabled === true && flag);
  ok("on: /shelf is the Shelf", (await call("GET /shelf", {})).file === "/pub/shelf.html");
  r = await call("GET /api/shelf/albums", {});
  ok("on: the whole library, with its signature", r.status === 200 && r.body.total === 3 && r.body.albums.length === 3 && typeof r.body.sig === "string" && Array.isArray(r.body.kinds));
  ok("Mallard's Formats tiles: each album's, and the tiles in order", r.body.albums.find((/** @type {any} */ a) => a.o === 3).q === "16/44.1" && JSON.stringify(r.body.formats) === JSON.stringify(["16/44.1"]));
  ok("and what the tabs' messages rest on", r.body.facts && r.body.facts.native === true && r.body.facts.favourites === 1 && r.body.facts.withGenres === 2);
  const sig = r.body.sig;
  r = await call("GET /api/shelf/albums", { query: { sig } });
  ok("the signature it has: told `same` in a few bytes", r.body.same === true && r.body.sig === sig && !r.body.albums);
  r = await call("GET /api/album/more", { query: { title: "Homogenic", artist: "Björk" } });
  ok("More by: the lead artist's other albums, the album itself left out", r.body.artist === "Björk" && r.body.by.length === 1 && r.body.by[0].title === "Vespertine" && r.body.by[0].offset === 4);
  ok("an artist with nothing else: an empty list", (await call("GET /api/album/more", { query: { title: "OK Computer", artist: "Radiohead" } })).body.by.length === 0);
  console.log(`\n${pass}/${pass + fail} shelf tests passed.`);
  if (fail) process.exit(1);
})();
