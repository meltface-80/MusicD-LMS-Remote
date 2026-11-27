// @ts-check
"use strict";
/*
 * GET /api/library/online: the streaming albums in the library, and why there may be none (v1.0.86).
 *
 * LMS's Online Music Library integration imports each service's FAVOURITE albums into the library (albums.extid
 * "qobuz:album:<id>"). With it off, or with its per-service switch off (LMS sets that to 0 for good if it once polled
 * with no account signed in), those albums exist only inside the plugin's own menus. Every pref here is read-only;
 * nothing is changed on the server. The words are composed HERE, once, so the web app and Mallard say the same thing.
 */

/**
 * The sentence for one service, and whether it describes a problem the owner can fix.
 * @param {string} name @param {number} imported @param {boolean|null} onlineOn @param {boolean|null} importEnabled
 * @returns {{ say: string, problem: boolean }}
 */
function sayFor(name, imported, onlineOn, importEnabled) {
  if (imported > 0) return { say: imported + " " + name + " album" + (imported === 1 ? "" : "s") + " in your library.", problem: false };
  if (onlineOn === false) return { problem: true, say: "LMS’s Online Music Library integration is off, so your " + name + " favourites stay inside the " + name + " plugin and never reach the library. Turn it on in LMS’s settings (Online Music Library), then Import now." };
  if (importEnabled === false) return { problem: true, say: "LMS has " + name + " import switched off — it does that by itself if it ever looked before your " + name + " account was signed in. Turn " + name + " back on in LMS’s Online Music Library settings, then Import now." };
  return { problem: false, say: "No " + name + " albums in your library yet. LMS imports the albums you’ve favourited in " + name + " (not every album you’ve played) — Import now fetches them." };
}

/**
 * Registers the route on [app].
 * @param {any} app the Express app
 * @param {{ state: any, index: { records: any[], builtAt?: number }, notConnected: (res: any) => any,
 *   services: () => Promise<any[]>, label: (tag: string) => string }} deps
 */
function route(app, { state, index, notConnected, services, label }) {
  app.get("/api/library/online", async (req, res) => {
    if (!state.connected) return notConnected(res);
    const counts = { qobuz: 0, tidal: 0, local: 0 };
    for (const r of index.records) counts[r.source === "qobuz" ? "qobuz" : r.source === "tidal" ? "tidal" : "local"]++;
    /** @param {string} name */
    const pref = async (name) => { try { const v = await state.lms.getPref(name); return v == null || v === "" ? null : String(v); } catch (e) { return null; } };
    const [olState, qobuz, tidal, purchases] = await Promise.all([
      pref("plugin.state:OnlineLibrary"), pref("plugin.onlinelibrary:enable_PLUGIN_QOBUZ"),
      pref("plugin.onlinelibrary:enable_PLUGIN_TIDAL"), pref("plugin.qobuz:dontImportPurchases")]);
    const list = await services().catch(() => []);
    /** @param {string} tag */
    const installed = (tag) => { const s = list.find(x => x.tag === tag); return s ? !!s.installed : null; };
    const onlineOn = olState == null ? null : olState === "enabled";
    /** @param {string} tag @param {number} imported @param {string|null} importOn */
    const service = (tag, imported, importOn) => {
      const importEnabled = importOn == null ? null : importOn === "1";
      return { tag, name: label(tag), installed: installed(tag), albums: imported, import_enabled: importEnabled, ...sayFor(label(tag), imported, onlineOn, importEnabled) };
    };
    res.json({
      online_library: onlineOn,
      // Only services the server has (or might have: installed unknown).
      services: [service("qobuz", counts.qobuz, qobuz), service("tidal", counts.tidal, tidal)].filter(x => x.installed !== false || x.albums > 0),
      import_purchases: purchases == null ? null : purchases !== "1",
      local_albums: counts.local,
      index_built_at: index.builtAt || null,
    });
  });
}

module.exports = { route, sayFor };
