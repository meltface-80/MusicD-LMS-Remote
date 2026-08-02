"use strict";

/* Home picks — remembers which album is "of the day" and which label is "of
 * the week", so the answer survives restarts.
 *
 * Both used to be derived on every request from in-memory state:
 *   album of the day = index.records[hash(date) % records.length]
 *   label of the week = sortedLabelKeys[hash(week) % keys.length]
 * That looks deterministic but isn't, because BOTH are positional: the pick is
 * an index into an array that is rebuilt from scratch on every server restart
 * and grows while the background label scan runs. Same day, different array,
 * different album. The client's own stability was a 5-minute in-memory TTL that
 * a force-close wipes — which is why the change showed up exactly then, even
 * though the cause was server-side.
 *
 * So the CHOICE is persisted, keyed by its period (a date string / ISO week),
 * and stored as a STABLE IDENTITY (an LMS album id, a label key) rather than a
 * position. A stored pick is re-validated on read: if the album or label has
 * genuinely gone (a rescan removed it), we re-pick and store that instead —
 * a pick that no longer exists is worse than a changed one.
 *
 * Plain JSON under DATA_DIR, matching lib/plays.js and lib/liveplaylists.js.
 */

const fs = require("fs");
const path = require("path");

function factory(opts) {
  const FILE = path.join(opts.dataDir, "home-picks.json");

  function load() {
    try {
      const j = JSON.parse(fs.readFileSync(FILE, "utf8"));
      return (j && typeof j === "object") ? j : {};
    } catch (e) {
      return {};   // absent/corrupt → start fresh, never throws
    }
  }

  function save(all) {
    try {
      fs.mkdirSync(path.dirname(FILE), { recursive: true });
      const tmp = FILE + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify(all, null, 2));
      fs.renameSync(tmp, FILE);   // atomic-ish: no half-written file on a crash
    } catch (e) {
      // Best-effort. A failed write just means this period may re-pick later.
    }
  }

  /**
   * The stable pick for one period.
   *   kind      — "aotd" | "lotw" (namespace within the file)
   *   periodKey — "2026-8-2" / "2026-W31": changing it means a new pick is due
   *   isValid   — (value) => bool: is the remembered pick still real?
   *   choose    — () => value: pick afresh (only called when needed)
   * Returns the value, or null when `choose` can't produce one.
   */
  function stable(kind, periodKey, isValid, choose) {
    const all = load();
    const cur = all[kind];
    if (cur && cur.key === periodKey && cur.value != null) {
      try { if (isValid(cur.value)) return cur.value; } catch (e) { /* fall through */ }
    }
    let picked = null;
    try { picked = choose(); } catch (e) { picked = null; }
    if (picked == null) return null;
    all[kind] = { key: periodKey, value: picked, at: Date.now() };
    save(all);
    return picked;
  }

  // Test/debug helpers.
  function peek(kind) { const a = load(); return a[kind] || null; }
  function clear(kind) {
    const all = load();
    if (kind) delete all[kind]; else for (const k of Object.keys(all)) delete all[k];
    save(all);
  }

  return { stable, peek, clear };
}

module.exports = factory;
