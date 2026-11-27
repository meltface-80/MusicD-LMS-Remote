// @ts-check
"use strict";
/*
 * Evidence that a feature has ALREADY been in use on this data volume (moved out of index.js with the Shelf).
 *
 * Read once, by features.applyDefaults (lib/features.js), to decide a flag nobody has set. Each returns true / false /
 * NULL, and null means "cannot tell" — an unreadable store must never be recorded as "not used", because that would
 * switch an existing install's features off permanently and repairing the store would not undo it (the setting is
 * explicit by then).
 */
const fs = require("node:fs");
const path = require("node:path");

/**
 * @param {{ labels: { status: () => any }, dataDir: string }} deps
 * @returns {Record<string, () => (boolean|null)>}
 */
function evidence({ labels, dataDir }) {
  return {
    labels() {
      try {
        const st = labels.status();
        if (st && Number.isFinite(st.count)) return st.count > 0;
        return null;
      } catch (e) { return null; }
    },
    smartPicks() {
      try {
        const f = path.join(dataDir, "smart-picks.json");
        if (!fs.existsSync(f)) return false;          // no store at all = never used
        const j = JSON.parse(fs.readFileSync(f, "utf8"));
        if (!j || typeof j !== "object") return null; // present but unreadable
        return Object.keys(j.days || {}).length > 0 || Object.keys(j.attempts || {}).length > 0;
      } catch (e) { return null; }
    },
    // The Shelf (v1.0.87) is new: nobody has used it, so it starts off.
    shelf: () => false,
  };
}

module.exports = { evidence };
