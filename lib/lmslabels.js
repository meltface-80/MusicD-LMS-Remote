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
 *
 * GENTLY (owner report: LMS stuttering and restarting). Each ask makes the plugin call Qobuz and parse the album inside
 * LMS's one process, which also feeds the players. So: one album at a time with a pause between; never while LMS is
 * scanning (an online-library import is already the heaviest thing it does); an album LMS had no label for is written
 * down and not asked again for 30 days, so a rebuild doesn't re-ask the thousands that have none; and a run of failures
 * (the plugin erroring, LMS not answering) ends the pass rather than pressing on.
 */
const fs = require("fs");
const path = require("path");

const GAP_MS = 1500;
const RETRY_AFTER_MS = 30 * 24 * 3600 * 1000;
const MAX_FAILS = 5;

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
 *   retryMs?: number, gapMs?: number, busy?: () => boolean, triedFile?: string, now?: () => number }} deps
 *   busy: LMS is scanning (the pass stops); triedFile: where the albums LMS had no label for are remembered.
 */
function makeLmsLabels(deps) {
  const wait = deps.wait || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const retryMs = deps.retryMs ?? 2500;
  const gapMs = deps.gapMs ?? GAP_MS;
  const now = deps.now || Date.now;

  /** album key → when LMS last had no label for it. @returns {Record<string, number>} */
  function readTried() {
    if (!deps.triedFile) return {};
    try { const j = JSON.parse(fs.readFileSync(deps.triedFile, "utf8")); return j && typeof j === "object" ? j : {}; } catch (e) { return {}; }
  }
  /** @param {Record<string, number>} tried */
  function writeTried(tried) {
    if (!deps.triedFile) return;
    try { fs.mkdirSync(path.dirname(deps.triedFile), { recursive: true }); fs.writeFileSync(deps.triedFile, JSON.stringify(tried)); }
    catch (e) { if (deps.log) deps.log.debug("albuminfo: couldn't keep the tried list:", /** @type {Error} */ (e).message); }
  }

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
   * left to the rest of the scan. One at a time, [gapMs] apart; those asked in the last 30 days without a label are
   * skipped; the pass stops while LMS scans or after a run of failures. Each is handed to [found] as it comes (the scan
   * saves it at once, so the lists fill while it runs).
   * @param {any[]} albums @param {(al: any) => string} keyOf @param {(done: number, total: number) => void} [progress]
   * @param {(al: any, label: string) => (void|Promise<void>)} [found]
   */
  async function labelsFor(albums, keyOf, progress, found) {
    /** @type {Map<string, string>} */
    const out = new Map();
    const tried = readTried();
    const since = now() - RETRY_AFTER_MS;
    for (const k of Object.keys(tried)) if (!(tried[k] > since)) delete tried[k];
    const todo = albums.filter((al) => al && al.qobuz_id && al.id != null && !(tried[keyOf(al)] > since));
    let done = 0, fails = 0, misses = 0;
    /** @type {string|null} */
    let stopped = null;
    for (const al of todo) {
      if (deps.busy && deps.busy()) { stopped = "LMS is scanning"; break; }
      if (done) await wait(gapMs);
      try {
        const l = await labelFor(al.id);
        fails = 0;
        if (l) { out.set(keyOf(al), l); if (found) await found(al, l); }
        else { tried[keyOf(al)] = now(); if (++misses % 50 === 0) writeTried(tried); }
      } catch (e) {
        if (deps.log) deps.log.debug("albuminfo", al.id, /** @type {Error} */ (e).message);
        if (++fails >= MAX_FAILS) { stopped = MAX_FAILS + " failures in a row"; break; }
      }
      done++;
      if (progress) progress(done, todo.length);
    }
    writeTried(tried);
    if (deps.log && todo.length) {
      deps.log.info(`LMS album info: ${out.size} of ${done} Qobuz albums asked carry a label` + (stopped ? ` (stopped: ${stopped}; the rest wait for the next scan)` : ""));
    }
    return out;
  }

  return { labelFor, labelsFor };
}

/**
 * The scan's hook (lib/labels.js lmsLabels) for whichever LMS this server talks to now: null without one.
 * @param {() => any} lms @param {() => boolean} scanning @param {string} triedFile @param {any} log
 */
function lmsLabelsHook(lms, scanning, triedFile, log) {
  /** @param {[any[], (al: any) => string, ((d: number, t: number) => void)?, ((al: any, l: string) => any)?]} a */
  return (...a) => {
    const l = lms();
    if (!l) return null;
    return makeLmsLabels({ request: (p, w) => l.request(p, w), busy: scanning, triedFile, log }).labelsFor(...a);
  };
}

module.exports = { makeLmsLabels, lmsLabelsHook, LABEL_LINE };
