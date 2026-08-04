"use strict";

/* Random Album Radio — keep a player fed with whole random albums.
 *
 * Ported from the Roon build (lib/radio.js there), reshaped for LMS. The
 * decision is kept PURE so it can be tested without a server: given a player's
 * status and whether radio is on for it, say what to do.
 *
 * WHOLE ALBUMS, NOT TRACKS. That is the point of it and the reason it isn't
 * just shuffle: it appends the next album while the last track of the current
 * one is still playing, so the join is gapless and the album stays intact.
 *
 * WHY IT DEFERS TO DON'T STOP THE MUSIC. LMS ships its own queue-filler (DSTM,
 * per-player pref `dontstopthemusic`). Two things appending to one queue would
 * fight — you'd get an album and a DSTM track interleaved — so radio stands
 * down whenever DSTM is enabled for that player, exactly as the Roon build
 * stands down when Roon Radio is on.
 *
 * The zone set is persisted (data/radio-zones.json) so it survives a restart,
 * like every other owner choice in this app.
 */

const fs = require("fs");
const path = require("path");

/** What should radio do for this player right now?
 *  @param st      player status: { mode, index, total }  (lib/lms.js playerStatus)
 *  @param enabled is radio on for this player?
 *  @param dstmOn  is LMS's Don't Stop The Music enabled for this player?
 *  @returns "queue" | "play" | null
 */
function radioDecision(st, enabled, dstmOn) {
  if (!st || !enabled) return null;
  if (dstmOn) return null;               // LMS's own filler owns the queue

  const total = Number.isFinite(st.total) ? st.total : null;
  const index = Number.isFinite(st.index) ? st.index : null;
  // Tracks left AFTER the current one. LMS reports a position and a length,
  // not a remaining count, so derive it.
  const remaining = (total == null || index == null) ? null : (total - index - 1);

  if (st.mode === "play") {
    // Top up while the LAST track is playing, so the next album is already
    // queued when it ends — appending after the queue drains would gap.
    if (remaining != null && remaining <= 0) return "queue";
    return null;
  }
  if (st.mode === "stop") {
    // Idle with nothing (or nothing left) to play: start something.
    if (total == null || total <= 0) return "play";
    if (remaining != null && remaining <= 0) return "play";
    return null;
  }
  return null;                            // paused — never interrupt
}

function factory(opts) {
  const FILE = path.join(opts.dataDir, "radio-zones.json");
  const log = opts.log || { debug() {}, info() {}, warn() {}, error() {} };
  let zones = null;

  function load() {
    if (zones) return zones;
    try {
      const j = JSON.parse(fs.readFileSync(FILE, "utf8"));
      const list = Array.isArray(j) ? j : (j && Array.isArray(j.zones) ? j.zones : []);
      zones = new Set(list.map(String).filter(Boolean));
    } catch (e) { zones = new Set(); }
    return zones;
  }

  function save() {
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      const tmp = FILE + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify({ zones: [...load()] }, null, 2));
      fs.renameSync(tmp, FILE);
    } catch (e) { log.debug("radio save failed:", e.message); }
  }

  function isOn(zoneId)  { return load().has(String(zoneId)); }
  function list()        { return [...load()]; }
  function set(zoneId, on) {
    const z = String(zoneId || "");
    if (!z) return false;
    if (on) load().add(z); else load().delete(z);
    save();
    return on;
  }
  // A player that has gone away shouldn't keep a radio flag forever.
  function prune(livePlayerIds) {
    const live = new Set((livePlayerIds || []).map(String));
    const cur = load();
    let changed = false;
    for (const z of [...cur]) if (!live.has(z)) { cur.delete(z); changed = true; }
    if (changed) save();
    return changed;
  }

  return { isOn, list, set, prune, decide: radioDecision, _file: FILE };
}

module.exports = factory;
module.exports.radioDecision = radioDecision;
