"use strict";

/* Live Playlists — rule-based playlists that are re-evaluated every time they
 * are opened, rather than a stored list of tracks.
 *
 * A Live Playlist stores a saved LIBRARY VIEW (the same sort + focus query the
 * Library wall builds) and nothing else. Opening one runs that query against
 * the current index, so a playlist like "80s Jazz I've never played" gains and
 * loses albums on its own as the library and the play history change. This is
 * modelled on the sibling Roon build's smart playlists; the LMS port adds
 * GENRE as a rule, which Roon couldn't offer because it partitions genre into
 * a separate browse tree.
 *
 * Storage is a plain JSON file under DATA_DIR — same no-native-deps approach as
 * lib/plays.js and lib/albumedits.js.
 *
 * Shape per entry:
 *   { id, name, view, at }
 * where `view` is { sort, dir, seed, decade[], source[], genre[], played }.
 *
 * SANITISE ON LOAD: every record is re-validated against the current rule
 * vocabulary each time it is read, not just when written. A hand-edited or
 * corrupt file, or a view saved by an older build whose sort no longer exists,
 * degrades to a sane default instead of producing a broken query — the caller
 * supplies the vocabulary so this module never drifts from the Library's own.
 */

const fs = require("fs");
const path = require("path");

const MAX = 50;   // plenty for a person; stops a runaway writer growing the file

function factory(opts) {
  const FILE = path.join(opts.dataDir, "live-playlists.json");
  // The Library owns the rule vocabulary; it is injected so there is exactly
  // one definition of "a valid sort/played value" in the app.
  const sorts  = new Set(opts.sorts  || ["album"]);
  const playeds = new Set(opts.playeds || ["any"]);

  const str = (v) => String(v == null ? "" : v);
  const list = (v) => Array.isArray(v) ? [...new Set(v.map(str).filter(Boolean))] : [];

  // Every facet the Library engine offers. Values are STRINGS end to end, and a
  // leading "!" means EXCLUDE — so validation must look at the value AFTER the
  // prefix and put it back, never parse the whole string. Parsing the decade to
  // an int (as this used to) silently destroyed "!1990".
  const FACET_IDS = ["genre", "source", "decade", "label", "letter", "added"];
  const splitNot = (v) => (v.charAt(0) === "!" ? { not: true, val: v.slice(1) } : { not: false, val: v });
  const keepDecade = (val) => {
    const d = parseInt(val, 10);
    return Number.isFinite(d) && d % 10 === 0 && d >= 1000 && d <= 3000 ? String(d) : null;
  };
  function facetList(id, raw) {
    const out = [];
    for (const item of list(raw)) {
      const { not, val } = splitNot(item);
      // Decades are decade-START years; anything else is dropped rather than
      // silently matching nothing at query time.
      const keep = id === "decade" ? keepDecade(val) : (val || null);
      if (keep) out.push(not ? "!" + keep : keep);
    }
    return [...new Set(out)];
  }

  function sanitizeView(v) {
    v = v && typeof v === "object" ? v : {};
    const seed = parseInt(v.seed, 10);
    const out = {
      sort:   sorts.has(str(v.sort)) ? str(v.sort) : "album",
      dir:    str(v.dir) === "desc" ? "desc" : "asc",
      seed:   Number.isFinite(seed) && seed > 0 ? seed : 1,
      played: playeds.has(str(v.played)) ? str(v.played) : "any",
    };
    for (const id of FACET_IDS) out[id] = facetList(id, v[id]);
    return out;
  }

  function sanitizeRecord(r) {
    if (!r || typeof r !== "object") return null;
    const name = str(r.name).trim().slice(0, 120);
    if (!name) return null;                       // unnamed → not a playlist
    return {
      id:   str(r.id) || newId(),
      name,
      view: sanitizeView(r.view),
      at:   Number.isFinite(r.at) ? r.at : Date.now(),
    };
  }

  const newId = () => "lp_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

  function load() {
    try {
      const j = JSON.parse(fs.readFileSync(FILE, "utf8"));
      const rows = Array.isArray(j) ? j : (j && j.playlists) || [];
      return rows.map(sanitizeRecord).filter(Boolean).slice(0, MAX);
    } catch (e) {
      return [];   // first run / unreadable → empty, never throws
    }
  }

  function save(rows) {
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      fs.writeFileSync(FILE, JSON.stringify(rows.slice(0, MAX), null, 2));
    } catch (e) {
      // Best-effort: a failed write loses this edit, nothing else.
    }
    return rows;
  }

  function list_() { return load(); }

  // Create, or update in place when an id is supplied. Updating by id (rather
  // than by name) is what lets a playlist be renamed without forking a
  // duplicate, and stops an edit that was started and abandoned from
  // resurrecting itself over a later save.
  function put({ id, name, view }) {
    const rows = load();
    const rec = sanitizeRecord({ id: id || newId(), name, view, at: Date.now() });
    if (!rec) return null;
    const at = id ? rows.findIndex(r => r.id === id) : -1;
    if (at >= 0) {
      rows[at] = rec;
    } else {
      // Saving under a name that already exists is a SAVE-OVER, not a new
      // playlist: the picker is a flat list, and two rows with the same name
      // would be indistinguishable. Reuse the existing record's id so the
      // rename lands in place instead of forking a twin.
      const byName = rows.findIndex(r => r.name.toLowerCase() === rec.name.toLowerCase());
      if (byName >= 0) { rec.id = rows[byName].id; rows[byName] = rec; }
      else {
        // The cap applies ONLY to genuinely new records — renaming or editing
        // an existing playlist must keep working once the list is full.
        if (rows.length >= MAX) return null;      // caller surfaces "list is full"
        rows.push(rec);
      }
    }
    save(rows);
    return rec;
  }

  function remove(id) {
    const rows = load();
    const kept = rows.filter(r => r.id !== String(id));
    if (kept.length === rows.length) return false;
    save(kept);
    return true;
  }

  function get(id) { return load().find(r => r.id === String(id)) || null; }

  return { list: list_, get, put, remove, sanitizeView, MAX };
}

module.exports = factory;
