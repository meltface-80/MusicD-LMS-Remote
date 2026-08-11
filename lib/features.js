"use strict";

/* Opt-in features, and the Home screen's row layout.
 *
 * Both reach the network on their own schedule — Smart Picks queries
 * ListenBrainz and MusicBrainz and writes into a Qobuz account; the label
 * pipeline scrapes Qobuz and walks MusicBrainz — so neither should be running
 * for somebody who never asked for it. OFF is the default, and off means the
 * work does not run at all, not merely that a row is hidden.
 *
 * Everything here is pure over plain data (the store is injected), in the style
 * of lib/radio.js and lib/smartpickalgo.js: the interesting decisions are the
 * repair rules, and those are worth testing without standing a server up.
 *
 * THE IDEA THAT MAKES THE DEFAULT SAFE: a flag is THREE-VALUED — true, false,
 * or ABSENT. Absent is not false; it means "nobody has decided yet, so look at
 * whether this feature has already been running and take that as the answer".
 * An existing install with a label cache or built picks has evidence of use and
 * keeps its features; a fresh install has neither and starts off. The inference
 * runs once and is written down, so it is never re-derived.
 */

// The features that can be switched off. Kept here rather than in index.js so
// the server and the settings UI cannot describe different sets.
const FEATURE_IDS = ["labels", "smartPicks"];

/* The Home rows, in their default order.
 *
 * This is the vocabulary: the loader, the settings list and the stored layout
 * all resolve against it, so a row can never appear in one and not the others.
 * `id` matches the `data-row` attribute in index.html.
 */
const HOME_ROW_IDS = ["unplayed", "picks", "random", "library", "lotw", "genres"];

const isPlainObject = (v) => !!v && typeof v === "object" && !Array.isArray(v);

/* Read a stored layout, repaired against the CURRENT vocabulary.
 *
 * Unknown ids are dropped (a row removed by an update) and missing ids are
 * appended, switched ON. That last part matters: if a row added by a future
 * update defaulted to hidden, shipping one would mean nobody with an existing
 * install ever saw it, and the bug would look like "the feature didn't ship".
 *
 * `on: r.on !== false` — a falsy-but-not-false stored value (0, null, absent)
 * reads as ON. Only an explicit `false` hides a row.
 */
function repairHomeRows(stored, ids) {
  const valid = new Set(ids || HOME_ROW_IDS);
  const out = [];
  const seen = new Set();
  if (Array.isArray(stored)) {
    for (const r of stored) {
      const id = r && typeof r.id === "string" ? r.id : null;
      if (!id || !valid.has(id) || seen.has(id)) continue;
      seen.add(id);
      out.push({ id, on: r.on !== false });
    }
  }
  for (const id of (ids || HOME_ROW_IDS)) {
    if (!seen.has(id)) out.push({ id, on: true });
  }
  return out;
}

/* Which flags still need deciding, and what the evidence says.
 *
 * `evidence[id]` returns true (has been used), false (has not), or NULL —
 * "cannot tell". Null must NOT be written down: an inference recorded from an
 * unreadable data volume would switch an existing user's features off
 * permanently on one bad boot, and repairing the volume would not bring them
 * back, because the setting is explicit by then. A deferred inference is
 * re-derived on the next boot, which is the correct outcome.
 */
function inferDefaults(stored, evidence, ids) {
  const patch = {};
  const deferred = [];
  for (const id of (ids || FEATURE_IDS)) {
    if (stored && stored[id] !== undefined) continue;   // already decided
    let used = null;
    try { used = evidence && evidence[id] ? evidence[id]() : null; }
    catch (e) { used = null; }
    if (used === null || used === undefined) { deferred.push(id); continue; }
    patch[id] = !!used;
  }
  return { patch, deferred };
}

/* Factory. `load()` returns the whole settings object, `save(patch)` merges —
 * matching index.js's existing loadSettings/saveSettings, so this rides in the
 * one settings file rather than adding a second.
 */
function makeFeatures(opts) {
  const load = opts.load;
  const save = opts.save;
  const log = opts.log || { info() {}, warn() {}, debug() {} };

  // Mirrored in memory: a gate is checked on every scheduler tick and every
  // guarded route, and re-reading a JSON file there would be silly.
  let flags = {};
  let decided = {};

  function readFlags() {
    const s = load() || {};
    flags = {};
    decided = {};
    for (const id of FEATURE_IDS) {
      flags[id] = s[id] === true;
      decided[id] = s[id] !== undefined;
    }
    return flags;
  }
  readFlags();

  /** Is this feature switched on? The one question every gate asks. */
  function enabled(id) { return flags[id] === true; }

  /** Explicitly stored, as opposed to still-to-be-inferred. */
  function isDecided(id) { return decided[id] === true; }

  /** Set and persist. Returns the new value. */
  function setEnabled(id, on) {
    if (FEATURE_IDS.indexOf(id) === -1) return null;
    flags[id] = !!on;
    decided[id] = true;
    save({ [id]: !!on });
    return flags[id];
  }

  /* Run the one-time inference. Call AFTER the stores it reads exist.
   * Reports what it did, so an unwritten inference is never mistaken for an
   * applied one. */
  function applyDefaults(evidence) {
    const { patch, deferred } = inferDefaults(load() || {}, evidence, FEATURE_IDS);
    if (Object.keys(patch).length) {
      save(patch);
      for (const [k, v] of Object.entries(patch)) { flags[k] = v; decided[k] = true; }
      log.info("feature defaults from existing use: " +
               Object.entries(patch).map(([k, v]) => k + "=" + v).join(" "));
    }
    if (deferred.length) {
      log.warn("feature defaults deferred (evidence unreadable): " + deferred.join(", "));
    }
    return { patch, deferred };
  }

  function all() {
    const out = {};
    for (const id of FEATURE_IDS) out[id] = flags[id] === true;
    return out;
  }

  function homeRows() { return repairHomeRows((load() || {}).homeRows, HOME_ROW_IDS); }

  /** Store a layout. Returns the REPAIRED result, so the caller is told what
   *  was actually stored rather than what it sent. */
  function setHomeRows(rows) {
    if (!Array.isArray(rows)) return null;
    const clean = repairHomeRows(rows, HOME_ROW_IDS);
    // Every id came back but none was recognised from the input — the caller
    // sent nothing usable, which is a client bug worth reporting rather than
    // silently resetting the layout to default.
    const known = rows.filter(r => isPlainObject(r) && HOME_ROW_IDS.indexOf(r.id) !== -1);
    if (!known.length) return null;
    save({ homeRows: clean });
    return clean;
  }

  return { enabled, isDecided, setEnabled, applyDefaults, all,
           homeRows, setHomeRows, readFlags,
           FEATURE_IDS, HOME_ROW_IDS };
}

module.exports = { makeFeatures, repairHomeRows, inferDefaults,
                   FEATURE_IDS, HOME_ROW_IDS };
