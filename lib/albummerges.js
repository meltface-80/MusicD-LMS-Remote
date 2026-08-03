"use strict";

/* Album merges — LMS splits a multi-disc release into one album row per disc.
 * This collapses chosen rows into a single album everywhere in the app.
 *
 * WHY THIS IS NOT THE LABEL MERGE. Labels are a derived projection: merging two
 * of them re-points a lookup and nothing else cares. Albums are the PRIMARY
 * index — `offset` is the client's album identity and `id` is what LMS plays —
 * so a merge has to survive at both levels:
 *   * the collapsed row takes ONE offset (the primary part's), and
 *   * it keeps EVERY part's LMS album id, in order, because playback and track
 *     listing are per-album-id and must cover all discs.
 *
 * IDENTITY. Parts are keyed on the ORIGINAL LMS title+artist — never the
 * displayed one — the same durable key album edits and rescued artwork use, so
 * a merge survives a rescan renumbering every id and offset AND survives the
 * owner renaming any part or the merged album itself. Keying on the displayed
 * title was the v1.0.50 bug: the edit layer runs BEFORE this collapse, so a
 * rename changed the very string the key was derived from and the part fell
 * out of its own merge. Rows carry `origTitle`/`origArtist` (set by
 * albumedits.applyToRow) precisely so both stages can agree on one identity.
 * A merge stored before v1.0.51 holds display-derived keys, so `apply` matches
 * a row on EITHER its original or its current key.
 *
 * RENAMING A MERGED ALBUM writes to the merge record (`rename`), never to an
 * album edit. An edit would have to key on some raw row, and renaming that row
 * is exactly what breaks the merge.
 *
 * ORDER IS THE USER'S. There is no disc number on an LMS album row (only on
 * tracks), so nothing here guesses disc order — parts stay in the order they
 * were selected, and that order drives both the track list and playback.
 *
 * Plain JSON under DATA_DIR, following lib/plays.js and lib/favourites.js.
 */

const fs = require("fs");
const path = require("path");

const MAX_MERGES = 2000;

function norm(s) {
  return String(s || "").toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

// Strip a trailing disc marker so merging "Foo (Disc 1)" with "Foo (Disc 2)"
// yields "Foo" rather than borrowing either disc's literal title. Handles the
// common shapes: bracketed or bare, "Disc"/"Disk"/"CD"/"Volume"/"Vol", with or
// without a separating dash. Nothing else in the app parses these, so this is
// the one place that knowledge lives.
//
// Deliberately conservative: it only strips when a NUMBER follows the word, so
// a real title like "Fear of a Blank Planet" or the album "Disintegration" is
// never truncated. A title that is ONLY a disc marker keeps its own name.
const DISC_SUFFIX_RE =
  /[\s\-–—]*[\(\[\{]?\s*(?:disc|disk|cd|volume|vol\.?)\s*\.?\s*\d+\s*[\)\]\}]?\s*$/i;
function stripDiscSuffix(title) {
  const t = String(title || "").trim();
  // Tidy the separator the marker left behind too ("Foo, Vol. 2" -> "Foo").
  const out = t.replace(DISC_SUFFIX_RE, "").replace(/[\s,;:\-–—]+$/, "").trim();
  return out || t;
}

function partKey(title, artist) {
  const t = norm(title);
  if (!t) return null;
  return t + "|" + norm(artist);
}

// The two keys a raw library row can be matched by: the durable one (its
// pre-edit LMS name) and the one it currently displays under. `origTitle` is
// only set when an edit renamed the row, so for an untouched album the two are
// identical — which is why merges written before v1.0.51 keep working.
function rowKeys(row) {
  const cur  = partKey(row.title, row.subtitle);
  const orig = partKey(row.origTitle || row.title, row.origArtist || row.subtitle);
  return { cur, orig };
}

function factory(opts) {
  const FILE = path.join(opts.dataDir, "album-merges.json");
  const debug = !!opts.debug;

  function load() {
    try {
      const j = JSON.parse(fs.readFileSync(FILE, "utf8"));
      const rows = Array.isArray(j) ? j : (j && Array.isArray(j.entries) ? j.entries : []);
      // A merge needs an id and at least two parts to mean anything.
      return rows.filter(r => r && r.id && Array.isArray(r.parts) && r.parts.length >= 2);
    } catch (e) {
      return [];
    }
  }

  function save(rows) {
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      const tmp = FILE + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify({ entries: rows }, null, 2));
      fs.renameSync(tmp, FILE);
    } catch (e) { if (debug) console.error("[albummerges] save:", e.message); }
  }

  function list() { return load().sort((a, b) => (b.at || 0) - (a.at || 0)); }
  function count() { return load().length; }

  /** Create a merge from albums in the order given. items[0] is the PRIMARY —
   *  it supplies the merged album's title, artist, year and cover, mirroring
   *  how the label merge treats its first-selected item. `opts.title`/`.artist`
   *  override the derived name, which is how growing an existing merge keeps
   *  the name the owner gave it. */
  function merge(items, opts) {
    opts = opts || {};
    if (!Array.isArray(items) || items.length < 2) return { ok: false, error: "Pick at least two albums to merge" };
    const parts = [];
    const seen = new Set();
    for (const it of items) {
      if (!it) continue;
      const disp   = String(it.title || "").trim();
      const dispA  = String(it.artist || it.subtitle || "").trim();
      // The key comes from the ORIGINAL LMS name when the caller knows it. Two
      // discs the owner renamed to the SAME corrected title still key apart,
      // because their LMS names differ — the old display-derived key made them
      // collide and refused the merge outright.
      const origT  = String(it.origTitle  || disp).trim();
      const origA  = String(it.origArtist || dispA).trim();
      const key = partKey(origT, origA);
      if (!key || seen.has(key)) continue;
      seen.add(key);
      parts.push({ key, title: disp, artist: dispA, origTitle: origT, origArtist: origA });
    }
    if (parts.length < 2) return { ok: false, error: "Those albums look like the same record" };

    const rows = load();
    // A part can only belong to one merge. Drop it from any earlier merge, and
    // dissolve any merge left with fewer than two parts.
    const keys = new Set(parts.map(p => p.key));
    const kept = [];
    for (const r of rows) {
      const remaining = r.parts.filter(p => !keys.has(p.key));
      if (remaining.length === r.parts.length) { kept.push(r); continue; }
      if (remaining.length >= 2) kept.push({ ...r, parts: remaining });
      // fewer than 2 left → the merge no longer means anything, drop it
    }
    // The merged album takes the primary part's title with any disc marker
    // removed, and the primary's artist. The owner can still rename it through
    // the normal album edit if the guess isn't right.
    // Growing an existing merge keeps its name: the caller passes the name the
    // owner already gave it, so adding a third disc doesn't quietly rename the
    // set back to whatever the primary disc happens to be called.
    const rec = {
      id: "m_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 7),
      parts,
      title: (opts.title && String(opts.title).trim()) || stripDiscSuffix(parts[0].title),
      artist: (opts.artist && String(opts.artist).trim()) || parts[0].artist,
      at: Date.now(),
    };
    kept.push(rec);
    while (kept.length > MAX_MERGES) kept.shift();
    save(kept);
    return { ok: true, merge: rec };
  }

  /** Rename the merged album. Its title/artist live HERE, not in an album edit:
   *  an edit keys on a raw LMS row, and renaming that row is precisely what
   *  used to drop it out of its own merge. Pass null/"" to fall back to the
   *  title derived from the primary part. */
  function rename(id, title, artist) {
    const rows = load();
    const rec = rows.find(r => r.id === id);
    if (!rec) return { ok: false, error: "Unknown merge" };
    const t = title == null ? null : String(title).trim();
    const a = artist == null ? null : String(artist).trim();
    rec.title  = t || stripDiscSuffix(rec.parts[0].title);
    rec.artist = a || rec.parts[0].artist;
    save(rows);
    return { ok: true, merge: rec };
  }

  function byId(id) { return load().find(r => r.id === id) || null; }

  function unmerge(id) {
    const rows = load();
    const next = rows.filter(r => r.id !== id);
    if (next.length === rows.length) return { ok: false, error: "Unknown merge" };
    save(next);
    return { ok: true };
  }

  /** Collapse raw LMS album rows.
   *
   *  Returns a NEW array in which every merged group is one row, positioned
   *  where its primary part was, carrying:
   *    mergeId   — so the UI can offer "unmerge"
   *    partIds   — every LMS album id, in the merge's order; playback and the
   *                track list walk these, since LMS only understands one
   *                album_id at a time
   *    partCount — for display ("3 discs")
   *  Rows in no merge pass through untouched.
   *
   *  A merge whose parts aren't all present (a disc removed from the library)
   *  still collapses whatever IS present — a half-present set should read as
   *  one album, not silently split back apart. */
  function apply(rows) {
    const merges = load();
    if (!merges.length) return rows;

    const keyToMerge = new Map();
    for (const m of merges) for (const p of m.parts) keyToMerge.set(p.key, m);
    // Original key first: it is the durable one. Falling back to the current
    // key keeps merges written before v1.0.51 (which stored display-derived
    // keys) matching, and costs nothing when the two are the same string.
    const matchOf = (row) => {
      const { cur, orig } = rowKeys(row);
      if (orig && keyToMerge.has(orig)) return { key: orig, merge: keyToMerge.get(orig) };
      if (cur  && keyToMerge.has(cur))  return { key: cur,  merge: keyToMerge.get(cur)  };
      return null;
    };

    // Bucket the rows that belong to a merge, keeping the merge's own part
    // order rather than the library's. A key holds an ARRAY: if the library
    // genuinely has two rows under one name they are BOTH absorbed, in library
    // order. Keeping only the first used to drop the second from the output
    // entirely — an album vanishing from the library, tracks and all.
    const buckets = new Map();   // mergeId -> { merge, byKey: Map<key, row[]> }
    const rowMatch = new Map();  // row -> match
    for (const row of rows) {
      const hit = matchOf(row);
      if (!hit) continue;
      rowMatch.set(row, hit);
      let b = buckets.get(hit.merge.id);
      if (!b) { b = { merge: hit.merge, byKey: new Map() }; buckets.set(hit.merge.id, b); }
      const list = b.byKey.get(hit.key);
      if (list) list.push(row); else b.byKey.set(hit.key, [row]);
    }
    if (!buckets.size) return rows;

    // The primary is the first part that's actually present.
    const primaryRow = new Map();     // mergeId -> row
    for (const [id, b] of buckets) {
      for (const p of b.merge.parts) {
        const list = b.byKey.get(p.key);
        if (list && list.length) { primaryRow.set(id, list[0]); break; }
      }
    }

    const out = [];
    for (const row of rows) {
      const hit = rowMatch.get(row);
      if (!hit) { out.push(row); continue; }
      const m = hit.merge;
      const b = buckets.get(m.id);
      if (!b || primaryRow.get(m.id) !== row) continue;   // a non-primary part: absorbed
      const ordered = [];
      for (const p of m.parts) for (const r of (b.byKey.get(p.key) || [])) ordered.push(r);
      out.push({
        ...row,
        title:  m.title || row.title,
        subtitle: m.artist || row.subtitle,
        // The primary part's LMS identity, NOT the merged title — artwork and
        // year edits key on this, and search.indexRecord would otherwise
        // default origTitle to the synthesised merged name, which matches no
        // real album row.
        origTitle:  row.origTitle  || row.title,
        origArtist: row.origArtist || row.subtitle,
        mergeId: m.id,
        partIds: ordered.map(r => String(r.id)),
        partCount: ordered.length,
      });
    }
    return out;
  }

  return { merge, unmerge, rename, byId, list, count, apply, partKey, stripDiscSuffix, _load: load };
}

module.exports = factory;
module.exports.partKey = partKey;
module.exports.rowKeys = rowKeys;
module.exports.norm = norm;
module.exports.stripDiscSuffix = stripDiscSuffix;
