"use strict";

/* Album edits — owner overrides for album metadata, stored in the app's own
 * database (data/album-edits.json). The music mount is read-only, so nothing
 * is ever written back to the files or to LMS: edits are applied on top of
 * what LMS reports, at index-build time and again live when saved.
 *
 * Keying: an edit is keyed by the ORIGINAL LMS title + artist (normalized),
 * so it survives library rescans and LMS album-id churn. The album's
 * MusicBrainz id is stored alongside when known, purely as extra context.
 *
 * Shape per entry:
 *   { origTitle, origArtist, mbid,           — identity (as LMS reports it)
 *     title?, artist?, year?,                — overridden fields (absent = keep)
 *     art?,                                  — image_key override ("art-…" store key)
 *     at }                                   — last-edited timestamp
 */

const fs = require("fs");
const path = require("path");
const { normalize } = require("./search");

function factory(opts) {
  const dataDir = opts.dataDir;
  const debug = !!opts.debug;
  const FILE = path.join(dataDir, "album-edits.json");

  let edits = null;          // Map key → entry
  let dirty = false;
  let flushTimer = null;

  const editKey = (title, artist) => normalize(title || "") + "||" + normalize(artist || "");

  function load() {
    if (edits) return edits;
    edits = new Map();
    try {
      const j = JSON.parse(fs.readFileSync(FILE, "utf8"));
      for (const [k, v] of (j.entries || [])) edits.set(k, v);
    } catch (e) { /* first run / unreadable → empty */ }
    return edits;
  }

  function flushNow() {
    if (!dirty) return;
    dirty = false;
    try {
      fs.mkdirSync(dataDir, { recursive: true });
      fs.writeFileSync(FILE, JSON.stringify({ entries: [...load().entries()] }, null, 2));
    } catch (e) { if (debug) console.error("[albumedits] save:", e.message); }
  }
  function scheduleFlush() {
    dirty = true;
    if (flushTimer) return;
    flushTimer = setTimeout(() => { flushTimer = null; flushNow(); }, 2000);
    if (flushTimer.unref) flushTimer.unref();
  }

  // The edit for an album as LMS reports it (original title/artist), or null.
  function get(origTitle, origArtist) {
    return load().get(editKey(origTitle, origArtist)) || null;
  }

  // Store (merge) an edit. Fields set to undefined are left alone; fields set
  // to null are cleared. Returns the stored entry.
  function set(origTitle, origArtist, fields) {
    const key = editKey(origTitle, origArtist);
    const cur = load().get(key) || { origTitle, origArtist };
    const next = { ...cur };
    for (const f of ["title", "artist", "year", "art", "mbid"]) {
      if (fields[f] === undefined) continue;
      if (fields[f] === null || fields[f] === "") delete next[f];
      else next[f] = fields[f];
    }
    next.at = Date.now();
    // An entry that overrides nothing is just deleted.
    if (!("title" in next) && !("artist" in next) && !("year" in next) && !("art" in next)) {
      edits.delete(key);
      scheduleFlush();
      return null;
    }
    edits.set(key, next);
    scheduleFlush();
    return next;
  }

  function remove(origTitle, origArtist) {
    const had = load().delete(editKey(origTitle, origArtist));
    if (had) scheduleFlush();
    return had;
  }

  // Apply the stored edit (if any) to a raw album row from lib/lms.js
  // (fields: title/subtitle/year/coverId). Mutates and returns the row.
  // The row keeps `origTitle`/`origArtist` so the modal can key later edits
  // by the LMS identity even after a rename.
  function applyToRow(row) {
    const e = load().get(editKey(row.title, row.subtitle));
    if (!e) return row;
    row.origTitle  = row.title;
    row.origArtist = row.subtitle;
    if (e.title  != null) row.title    = e.title;
    if (e.artist != null) row.subtitle = e.artist;
    // Stash the pre-override year/cover so removing the edit can restore them.
    if (e.year   != null) { row.origYear = row.year != null ? row.year : null; row.year = e.year; }
    if (e.art    != null) { row.origCoverId = row.coverId || null; row.coverId = e.art; }
    row.edited = true;
    return row;
  }

  function count() { return load().size; }

  return { get, set, remove, applyToRow, count, flushNow, _editKey: editKey };
}

module.exports = factory;
