// @ts-check
"use strict";
/*
 * lmslabels.js — record labels for the albums LMS holds as Qobuz albums, read from LMS itself (owner request: "Labels
 * is accessed via LMS album info … make labels work for Qobuz").
 *
 * LMS's Qobuz plugin adds an "On Qobuz" section to every Qobuz album's album info (Plugin.pm albumInfoMenu, an
 * AlbumInfo provider), and in it "Music Label: <name>" from the Qobuz API album it already holds — what Material shows.
 * The label scan (lib/labels.js) used to find a Qobuz album's label by scraping qobuz.com's public pages, one album per
 * ~1.4 s: hours for a library of thousands, during which the Labels and the Shelf's Labels tab stayed empty. This asks
 * LMS instead, at LMS's speed:
 *   albuminfo items 0 100 album_id:<id>                    → the sections; the one named "… Qobuz" holds the label
 *   albuminfo items 0 100 album_id:<id> item_id:<section>  → its rows; "Music Label: Nuclear Blast"
 * The plugin fetches the album from Qobuz the first time it is asked (asynchronously, so that first answer can lack
 * the label): an album with an "On Qobuz" section but no label line yet is asked once more after a moment.
 */

/** "Music Label" in the plugin's languages (strings.txt PLUGIN_QOBUZ_LABEL), then the colon LMS puts after it. */
const LABEL_LINE = /^\s*(?:Music Label|Musiklabel|Muzieklabel|Label)\s*:\s*(.+?)\s*$/i;

/** A row's words, whichever shape LMS answered in (loop_loop name / item_loop text). @param {any} it */
const words = (it) => String((it && (it.name || it.text || it.title)) || "");

/** The rows of an answer. @param {any} r @returns {any[]} */
const rowsOf = (r) => (r && (r.loop_loop || r.item_loop)) || [];

/** The label a row list names, or null. @param {any[]} rows */
function labelIn(rows) {
  for (const it of rows) { const m = LABEL_LINE.exec(words(it)); if (m && m[1]) return m[1]; }
  return null;
}

/**
 * @param {{ request: (player: string, words: any[]) => Promise<any>, log?: any, wait?: (ms: number) => Promise<void>,
 *   retryMs?: number }} deps
 */
function makeLmsLabels(deps) {
  const wait = deps.wait || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const retryMs = deps.retryMs ?? 2500;

  /** One album's label from LMS's album info, or null. @param {number|string} albumId */
  async function labelFor(albumId) {
    const base = ["albuminfo", "items", 0, 100, "album_id:" + albumId];
    for (let attempt = 0; attempt < 2; attempt++) {
      const top = rowsOf(await deps.request("", base));
      const direct = labelIn(top);
      if (direct) return direct;
      const section = top.find((it) => /qobuz/i.test(words(it)) && (it.id !== undefined || it.params));
      if (!section) return null;   // LMS offers no Qobuz section for it: nothing to wait for
      const id = section.id !== undefined ? section.id : section.params && section.params.item_id;
      const label = labelIn(rowsOf(await deps.request("", [...base, "item_id:" + id])));
      if (label) return label;
      if (attempt === 0) await wait(retryMs);   // the plugin fetched the album just now: ask again
    }
    return null;
  }

  /**
   * Labels for [albums] that LMS holds as Qobuz albums (a qobuz_id and an LMS album id), keyed by [keyOf]; others are
   * left to the rest of the scan. Two at a time, so LMS (and Qobuz behind it) is never flooded; a failure skips one album.
   * Each is handed to [found] as it comes (the scan saves it at once, so the lists fill while it runs).
   * @param {any[]} albums @param {(al: any) => string} keyOf @param {(done: number, total: number) => void} [progress]
   * @param {(al: any, label: string) => (void|Promise<void>)} [found]
   */
  async function labelsFor(albums, keyOf, progress, found) {
    /** @type {Map<string, string>} */
    const out = new Map();
    const todo = albums.filter((al) => al && al.qobuz_id && al.id != null);
    let next = 0, done = 0;
    const worker = async () => {
      for (;;) {
        const al = todo[next++];
        if (!al) return;
        try { const l = await labelFor(al.id); if (l) { out.set(keyOf(al), l); if (found) await found(al, l); } }
        catch (e) { if (deps.log) deps.log.debug("albuminfo", al.id, /** @type {Error} */ (e).message); }
        done++;
        if (progress) progress(done, todo.length);
      }
    };
    await Promise.all([worker(), worker()]);
    if (deps.log && todo.length) deps.log.info(`LMS album info: ${out.size} of ${todo.length} Qobuz albums carry a label`);
    return out;
  }

  return { labelFor, labelsFor };
}

module.exports = { makeLmsLabels, LABEL_LINE };
