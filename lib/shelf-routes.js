// @ts-check
"use strict";
/*
 * shelf-routes.js — the Shelf: its page (/shelf), the whole library it flicks through (/api/shelf/albums) and its
 * switch (/api/settings/shelf). Ported from the Roon build's module of the same name.
 *
 * OFF BY DEFAULT, like Labels and Smart Picks (lib/features.js), and off means the work does not run: the page is the
 * one that says how to switch it on, and the list answers 403 without building anything. The web app and Mallard read
 * the same switch, so the two show Shelf in their menus together (the owner rule: the apps mirror each other).
 *
 * index.js owns the index; it hands this module the functions that read it, so nothing here holds library state of its
 * own except the answer it keeps.
 */
const path = require("node:path");
const { buildShelf, shelfSignature, stampOf } = require("./shelf");

/**
 * @typedef {object} ShelfDeps
 * @property {string} publicDir                        where shelf.html and shelf-off.html live
 * @property {{ enabled: (id: string) => boolean, setEnabled: (id: string, on: boolean) => void }} features
 * @property {() => boolean} connected                 LMS answering
 * @property {() => Promise<void>} ensureIndex         index.js's ensureIndex
 * @property {() => number} builtAt                    the index's build time: the answer is made again when it moves
 * @property {(q: object) => any[]} libraryView        index.js's libraryView (its "artist" sort is the shop's order)
 * @property {(al: any) => boolean} favOf              one of this app's favourites
 * @property {(al: any) => (string|null)} labelOf      its record label (null while Labels is off)
 * @property {(al: any) => (string|null)} formatOf     what its files are ("FLAC 24/96"), or null
 * @property {() => any[]} records                     the whole index, for "More by" (/api/album/more)
 * @property {(subtitle: string) => Array<{name: string, k: string}>} mainArtists  search.mainArtistNames
 */

/*
 * An album's format as the page's Formats tab reads it (public/shelf-format.js, the Roon build's and Mandarin's tiles:
 * "16/44.1", "24/96", "DSD64", "MP3"): [file, rate, bits, channels, MQA rate, lossy bit rate, streaming, mixed], the
 * file as Roon numbers its formats. From the first track's LMS content type, rate and bits (index.js stampAlbumFormats);
 * LMS gives no channel count, so no album is filed as Multichannel. A Qobuz or TIDAL album with no format read is filed
 * under its service, as the Roon build files a streamed one.
 */
const FILE_CODES = { flc: 1, flac: 1, aac: 2, mp4: 2, m4a: 2, alc: 3, mp3: 4, wav: 5, ogg: 6, ogf: 6, ops: 6, aif: 7, aiff: 7, wma: 8, wmal: 8, dsf: 13, dff: 14 };
/** @param {any} al @returns {Array<number|null> | null} */
function factsOf(al) {
  const file = al.fmtType ? (/** @type {Record<string, number>} */ (FILE_CODES))[String(al.fmtType).toLowerCase()] || null : null;
  const streaming = al.source === "qobuz" || al.source === "tidal";
  if (!file && !al.fmtRate && !al.fmtBits) return streaming ? [al.source === "qobuz" ? 15 : 12, null, null, null, null, null, 1, 0] : null;
  return [file, Number(al.fmtRate) || null, Number(al.fmtBits) || null, null, null, null, streaming ? 1 : 0, 0];
}

/**
 * @param {{ get: Function, post: Function }} app  the Express app
 * @param {ShelfDeps} deps
 */
function mountShelf(app, deps) {
  const on = () => deps.features.enabled("shelf");

  app.get("/shelf", (req, res) => {
    res.sendFile(path.join(deps.publicDir, on() ? "shelf.html" : "shelf-off.html"));
  });

  app.get("/api/settings/shelf", (req, res) => res.json({ enabled: on() }));
  app.post("/api/settings/shelf", (req, res) => {
    const body = req.body || {};
    if (typeof body.enabled !== "boolean") return res.status(400).json({ error: "enabled must be true or false" });
    deps.features.setEnabled("shelf", body.enabled);
    res.json({ ok: true, enabled: on() });
  });

  /*
   * "More by" on the Shelf's album view (public/shelf-about.js): the other albums the album's LEAD artist is a main
   * artist of, newest first, the album itself left out. Identity by artistKey, as the artist pages compare artists
   * (P!nk is Pink); an empty list, never an error, for an artist with nothing else.
   */
  app.get("/api/album/more", (req, res) => {
    const title = String(req.query.title || ""), artist = String(req.query.artist || "");
    const lead = deps.mainArtists(artist)[0];
    if (!lead) return res.json({ artist, by: [] });
    const by = deps.records()
      .filter((r) => !(r.title === title && r.subtitle === artist) && deps.mainArtists(r.subtitle || "").some((m) => m.k === lead.k))
      .sort((a, b) => (b.year || 0) - (a.year || 0) || String(a.title).localeCompare(String(b.title)))
      .slice(0, 24)
      .map((r) => ({ offset: r.offset, title: r.title, subtitle: r.subtitle, image_key: r.image_key || null, year: r.year || null }));
    res.json({ artist: lead.name, by });
  });

  /*
   * The whole library in one answer, in the artist order a shop files by. Built once per state of what it shows and
   * kept: the index (titles, credits, art, order, genres, years), and — read afresh each time, because nothing stamps
   * them — the favourites, labels and formats, folded to one number. A page that sends back the signature it has is
   * told `same` in a few bytes instead of the whole library again; that is how an open Shelf notices a change cheaply
   * (it asks every minute or so, there being no live-revision feed on this server).
   */
  /** @type {{ key: string, sig: string, shelf: ReturnType<typeof buildShelf> } | null} */
  let answer = null;
  app.get("/api/shelf/albums", async (req, res) => {
    if (!on()) return res.status(403).json({ error: "Shelf is off in Settings", off: true });
    if (!deps.connected()) return res.status(503).json({ error: "Not connected to LMS" });
    try {
      await deps.ensureIndex();
      const albums = deps.libraryView({ sort: "artist" });
      const key = deps.builtAt() + "." + stampOf(albums, (al) => (deps.favOf(al) ? "1" : "0") + "|" + (deps.labelOf(al) || "") + "|" + (deps.formatOf(al) || ""));
      if (!answer || answer.key !== key) {
        const shelf = buildShelf(albums, {
          genresOf: (al) => (al.genre ? [al.genre] : []),
          firstOf: (al) => al.sortArtist || al.subtitle || "",
          yearOf: (al) => (Number.isFinite(al.year) && al.year > 0 ? al.year : null),
          labelOf: deps.labelOf,
          formatOf: deps.formatOf,
          favOf: deps.favOf,
          factsOf,
        });
        // What the tabs' messages rest on: this server always reads formats and genres itself ("native"), so a tab
        // with nothing in it says the library has none, not that a mode is off.
        let withFormat = 0, withGenres = 0, favourites = 0;
        for (const a of shelf.albums) { if (a.fx) withFormat++; if (a.g.length) withGenres++; if (a.fv) favourites++; }
        Object.assign(shelf, { facts: { native: true, albums: shelf.albums.length, withFormat, withGenres, favourites } });
        answer = { key, sig: shelfSignature(shelf), shelf };
      }
      if (req.query.sig && String(req.query.sig) === answer.sig) return res.json({ same: true, sig: answer.sig });
      // No release kinds on LMS: the page's Types tab keeps away when this list is empty.
      res.json(Object.assign({ total: answer.shelf.albums.length, sig: answer.sig, kinds: [] }, answer.shelf));
    } catch (e) {
      res.status(500).json({ error: e instanceof Error ? e.message : String(e) });
    }
  });
}

module.exports = { mountShelf, factsOf };
