"use strict";

/* Favourites — THIS APP'S OWN collection, stored in its own database.
 *
 * Deliberately separate from the Qobuz heart. That one calls the Qobuz plugin
 * and shows up in the Qobuz app; this one never leaves the remote, and can hold
 * albums from anywhere: local files, Qobuz, Tidal, or a service added later.
 *
 * IDENTITY. The obvious key — the LMS album id, or the library `offset` — is
 * wrong here for two reasons:
 *   * ids/offsets move on a library rescan, so favourites would silently point
 *     at the wrong album (album-edits and rescued artwork hit this first and
 *     solved it the same way);
 *   * a Qobuz catalogue album that isn't in the library has neither.
 * So the key is normalised TITLE + ARTIST, exactly like lib/albumedits.js. The
 * service id (qobuz_id / extid) is stored alongside when known — useful for
 * playback and for matching, but never the identity.
 *
 * Plain JSON under DATA_DIR, following lib/plays.js and lib/homepicks.js.
 */

const fs = require("fs");
const path = require("path");

// Generous, but bounded — this is an array in memory and a file on disk, and a
// runaway writer shouldn't grow it forever.
const MAX_ENTRIES = 5000;

// Same folding as the search index's normalize(): case, accents and
// punctuation all collapse, so "Björk" == "Bjork" and "Vol. 1" == "Vol 1".
function norm(s) {
  return String(s || "").toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function keyFor(title, artist) {
  const t = norm(title), a = norm(artist);
  if (!t) return null;             // an album with no title can't be identified
  return t + "|" + a;
}

function factory(opts) {
  const FILE = path.join(opts.dataDir, "favourites.json");

  function load() {
    try {
      const j = JSON.parse(fs.readFileSync(FILE, "utf8"));
      const rows = Array.isArray(j) ? j : (j && Array.isArray(j.entries) ? j.entries : []);
      return rows.filter(r => r && r.key);
    } catch (e) {
      return [];   // absent/corrupt → empty, never throws
    }
  }

  function save(rows) {
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      const tmp = FILE + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify({ entries: rows }, null, 2));
      fs.renameSync(tmp, FILE);
    } catch (e) {
      // Best-effort; a failed write loses this one change, nothing else.
    }
  }

  function list() {
    // Newest first — a favourites screen should lead with what was just added.
    return load().sort((a, b) => (b.at || 0) - (a.at || 0));
  }

  function keys() { return new Set(load().map(r => r.key)); }

  function has(title, artist) {
    const k = keyFor(title, artist);
    if (!k) return false;
    return load().some(r => r.key === k);
  }

  /** Add (or refresh) one album. Returns the stored record, or null if it has
   *  no usable title. Re-adding an existing favourite refreshes its metadata
   *  (art, ids) without duplicating it or changing when it was added. */
  function add(album) {
    const title  = String((album && album.title) || "").trim();
    const artist = String((album && (album.artist || album.subtitle)) || "").trim();
    const k = keyFor(title, artist);
    if (!k) return null;
    const rows = load();
    const at = rows.findIndex(r => r.key === k);
    const rec = {
      key: k,
      title, artist,
      source:    (album && album.source) || null,       // "qobuz" | "tidal" | null (local)
      image_key: (album && album.image_key) || null,
      qobuz_id:  (album && album.qobuz_id) || null,
      extid:     (album && album.extid) || null,
      at: at === -1 ? Date.now() : (rows[at].at || Date.now()),
    };
    if (at === -1) {
      rows.push(rec);
      // Oldest first out, so the cap trims history rather than recent picks.
      while (rows.length > MAX_ENTRIES) {
        let oldest = 0;
        for (let i = 1; i < rows.length; i++) if ((rows[i].at || 0) < (rows[oldest].at || 0)) oldest = i;
        rows.splice(oldest, 1);
      }
    } else {
      rows[at] = rec;
    }
    save(rows);
    return rec;
  }

  function remove(title, artist) {
    const k = keyFor(title, artist);
    if (!k) return false;
    const rows = load();
    const next = rows.filter(r => r.key !== k);
    if (next.length === rows.length) return false;
    save(next);
    return true;
  }

  /** Flip one album's state. `want` forces a direction; omit it to toggle.
   *  Returns the resulting boolean. */
  function toggle(album, want) {
    const title  = String((album && album.title) || "").trim();
    const artist = String((album && (album.artist || album.subtitle)) || "").trim();
    if (!keyFor(title, artist)) return false;
    const on = has(title, artist);
    const target = (want === undefined || want === null) ? !on : !!want;
    if (target === on) return on;
    if (target) add(album); else remove(title, artist);
    return target;
  }

  function count() { return load().length; }
  function clear() { save([]); }

  return { list, keys, has, add, remove, toggle, count, clear, keyFor };
}

module.exports = factory;
module.exports.keyFor = keyFor;
module.exports.norm = norm;
