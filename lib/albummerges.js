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
 * IDENTITY. Parts are keyed on normalised title+artist, the same durable key
 * album edits and rescued artwork use, so a merge survives a rescan renumbering
 * every id and offset. Two discs of a set usually differ by a "(Disc 2)"-ish
 * suffix so they key apart; if two rows genuinely share a title AND artist they
 * both match the same key and are both absorbed, which is the wanted outcome
 * anyway.
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
   *  how the label merge treats its first-selected item. */
  function merge(items) {
    if (!Array.isArray(items) || items.length < 2) return { ok: false, error: "Pick at least two albums to merge" };
    const parts = [];
    const seen = new Set();
    for (const it of items) {
      const key = partKey(it && it.title, it && (it.artist || it.subtitle));
      if (!key || seen.has(key)) continue;
      seen.add(key);
      parts.push({
        key,
        title: String((it.title || "")).trim(),
        artist: String((it.artist || it.subtitle || "")).trim(),
      });
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
    const rec = {
      id: "m_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 7),
      parts,
      title: stripDiscSuffix(parts[0].title),
      artist: parts[0].artist,
      at: Date.now(),
    };
    kept.push(rec);
    while (kept.length > MAX_MERGES) kept.shift();
    save(kept);
    return { ok: true, merge: rec };
  }

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

    // Bucket the rows that belong to a merge, keeping the merge's own part
    // order rather than the library's.
    const buckets = new Map();   // mergeId -> { merge, byKey: Map }
    for (const row of rows) {
      const k = partKey(row.title, row.subtitle);
      const m = k && keyToMerge.get(k);
      if (!m) continue;
      let b = buckets.get(m.id);
      if (!b) { b = { merge: m, byKey: new Map() }; buckets.set(m.id, b); }
      if (!b.byKey.has(k)) b.byKey.set(k, row);
    }
    if (!buckets.size) return rows;

    // The primary is the first part that's actually present.
    const primaryRow = new Map();     // mergeId -> row
    for (const [id, b] of buckets) {
      for (const p of b.merge.parts) {
        const row = b.byKey.get(p.key);
        if (row) { primaryRow.set(id, row); break; }
      }
    }

    const out = [];
    for (const row of rows) {
      const k = partKey(row.title, row.subtitle);
      const m = k && keyToMerge.get(k);
      if (!m) { out.push(row); continue; }
      const b = buckets.get(m.id);
      if (!b || primaryRow.get(m.id) !== row) continue;   // a non-primary part: absorbed
      const ordered = m.parts.map(p => b.byKey.get(p.key)).filter(Boolean);
      out.push({
        ...row,
        title:  m.title || row.title,
        subtitle: m.artist || row.subtitle,
        mergeId: m.id,
        partIds: ordered.map(r => String(r.id)),
        partCount: ordered.length,
      });
    }
    return out;
  }

  return { merge, unmerge, list, count, apply, partKey, stripDiscSuffix, _load: load };
}

module.exports = factory;
module.exports.partKey = partKey;
module.exports.norm = norm;
module.exports.stripDiscSuffix = stripDiscSuffix;
