"use strict";

/* Generic LMS plugin ("app") browsing — every plugin, not just Qobuz.
 *
 * Every streaming / radio / podcast plugin in LMS (Qobuz, Tidal, Spotty, Deezer,
 * BBC Sounds, Bandcamp, YouTube, Podcasts, TuneIn, Radio Paradise, Mixcloud…)
 * serves its menus through the same machinery, Slim::Control::XMLBrowser:
 * `<tag> items <start> <count> menu:1 item_id:…` answers `{count, offset, base,
 * item_loop, window}`, and each row's runnable commands are built by merging
 * `base.actions` templates with the row's own params. Material Skin draws those
 * menus generically; this module lets a native client (Mallard) do the same
 * through this server, with no per-plugin code.
 *
 * DESIGN (the same as the existing Qobuz menu-action replay, generalised):
 *   * The CLIENT NEVER SUBMITS AN LMS COMMAND. Each row's actions are built here
 *     and stored server-side behind opaque tokens; the client echoes a token
 *     back. The server has no sign-in, so a raw command endpoint would make it a
 *     remote control for anything LMS can do.
 *   * Tokens expire (TOKEN_TTL_MS, under LMS's own 3600 s menu-session cache):
 *     LMS item ids are positions in a cached feed tree that drift and expire, so
 *     nothing here is a durable bookmark. `presetParams.favorites_url` is the
 *     only durable handle a row carries, and it is passed through as `url`.
 *   * Commands are only run for app tags LMS has ADVERTISED, plus a small fixed
 *     list of standard LMS menus (allowedCommand). Calling a verb LMS doesn't
 *     know makes it CLOSE THE SOCKET rather than answer, so guessing is never
 *     done here.
 *
 * The merge rule is Material's (html/js/browse-functions.js browseBuildCommand),
 * not the older lms.js menuAction(): ACTION params first, then the row's
 * commonParams, then the row's `[itemsParams]` — FIRST WRITER WINS, empty values
 * skipped, touchToPlay/touchToPlaySingle dropped — then base.parentParams. The
 * row's params are used ONLY when the action names them via itemsParams.
 */

const crypto = require("crypto");

const TOKEN_TTL_MS = 30 * 60 * 1000;
const TOKEN_CAP = 20000;
const DEFAULT_COUNT = 100;
const MAX_COUNT = 500;
const TAGGED = "__TAGGEDINPUT__";

/* Standard LMS menus a plugin's rows may point into (context menus, favourites,
 * radio, the apps list itself). Anything else must be an advertised app tag. */
const STANDARD = new Set([
  "trackinfo", "albuminfo", "artistinfo", "genreinfo", "yearinfo", "playlistinfo",
  "favorites", "jivefavorites", "radios", "myapps", "globalsearch", "selectRemoteLibrary",
  "browseonlineartist", "podcasts"
]);
/* What a command's second word may be. `items` browses; `playlist` plays/adds;
 * `add`/`delete` are favourites edits from a context menu. */
const VERBS = new Set(["items", "playlist", "add", "delete"]);

// ---------------------------------------------------------------------------
// The action merge (pure)

/** The action template [name] for a row: the row's own, else (for go) its `do`,
 *  else the response's base, else (for go) base `do`. "none" means none. */
function pickAction(base, item, name) {
  const ia = (item && item.actions) || {};
  const ba = (base && base.actions) || {};
  let a = ia[name];
  if (a === undefined && name === "go") a = ia.do;
  if (a === undefined) a = ba[name];
  if (a === undefined && name === "go") a = ba.do;
  if (!a || a === "none" || !Array.isArray(a.cmd)) return null;
  return a;
}

/** One action of a row as a runnable { cmd:[...words], params:{k:v}, nextWindow }
 *  under Material's merge rule; null when the row has no such action. */
function buildAction(base, item, name) {
  const a = pickAction(base, item, name);
  if (!a) return null;
  const params = {};
  const put = (obj) => {
    if (!obj || typeof obj !== "object") return;
    for (const [k, v] of Object.entries(obj)) {
      if (k === "touchToPlay" || k === "touchToPlaySingle") continue;
      if (v === undefined || v === null || v === "") continue;
      if (!(k in params)) params[k] = v;
    }
  };
  put(a.params);
  put(item && item.commonParams);
  if (a.itemsParams && item) put(item[a.itemsParams]);
  put(base && base.parentParams);
  return {
    cmd: a.cmd.map(String),
    params,
    nextWindow: a.nextWindow || (item && item.nextWindow) || null,
  };
}

/** Whether an action browses (answers a menu) rather than doing something. */
const isBrowse = (act) => !!act && act.cmd.length >= 2 && act.cmd[act.cmd.length - 1] === "items";

/** Whether a row's go action is a search/entry (a `__TAGGEDINPUT__` param). */
function hasTaggedInput(act) {
  return !!act && Object.values(act.params).some(v => String(v).includes(TAGGED));
}

/** The command words LMS takes: [...cmd, start, count?, "k:v"…]. Browses get
 *  paging and always ask for the menu format. */
function commandWords(act, start, count) {
  const words = [...act.cmd];
  const params = { ...act.params };
  if (isBrowse(act)) {
    words.push(Math.max(0, start | 0), Math.min(MAX_COUNT, Math.max(1, count | 0 || DEFAULT_COUNT)));
    if (!("menu" in params)) params.menu = "1";
  }
  for (const [k, v] of Object.entries(params)) words.push(k + ":" + v);
  return words;
}

/** An action with every `__TAGGEDINPUT__` replaced by the term typed. */
function withTerm(act, term) {
  const params = {};
  for (const [k, v] of Object.entries(act.params)) params[k] = String(v).split(TAGGED).join(String(term));
  return { ...act, params };
}

/** Defence in depth: only app tags LMS advertised (any verb of the plugin's own:
 *  Podcasts subscribes with one), or standard LMS menus with a known second word.
 *  Actions come from LMS's own answers, never the client — this guards against a
 *  menu pointing somewhere unexpected, not against the client. */
function allowedCommand(cmd, appTags) {
  if (!Array.isArray(cmd) || cmd.length < 2) return false;
  const tag = String(cmd[0]);
  if (appTags.has(tag)) return true;
  return STANDARD.has(tag) && VERBS.has(String(cmd[1]));
}

// ---------------------------------------------------------------------------
// Rows (pure)

/** A row's picture: an image_key /api/image can serve (a cover id, or "url-…"
 *  for an absolute picture LMS's imageproxy resizes), or an image_path on the
 *  LMS host the server proxies (/plugins, /html, /imageproxy, /music). */
function rowImage(item) {
  let s = String((item && (item.icon || item["icon-id"] || item.image)) || "").trim();
  if (!s) return {};
  const m = s.match(/^\/?imageproxy\/([^/]+)\/image/i);
  if (m) { try { const u = decodeURIComponent(m[1]); if (/^https?:\/\//i.test(u)) s = u; } catch (e) { /* keep */ } }
  if (/^https?:\/\//i.test(s)) return { image_key: "url-" + Buffer.from(s, "utf8").toString("base64url") };
  if (/^-?\d+$/.test(s) || /^[0-9a-f]{8}$/i.test(s)) return /^-/.test(s) ? {} : { image_key: s };   // a cover id (negative: LMS's placeholder)
  return { image_path: "/" + s.replace(/^\/+/, "") };
}

/** Whether a row can be played/added: XMLBrowser gives playable rows params
 *  with an item_id; a feed may give its own play/add action. */
function playableRow(item) {
  const ia = (item && item.actions) || {};
  return !!(item && item.params && item.params.item_id !== undefined) || !!(ia.play || ia.add);
}

/**
 * One LMS row as the client draws it. [mint] turns an action into a token.
 * kind: node (opens), playable, search (asks for a term), choice (radio/checkbox
 * with a `do`), link (a web page), text (nothing to do).
 */
function shapeRow(base, item, mint) {
  const text = String(item.text || item.name || item.title || "");
  const [title, ...rest] = text.split("\n");
  const row = { kind: "text", title: title || "", subtitle: rest.join(" · ") || null, ...rowImage(item) };
  if (item.textkey) row.textkey = String(item.textkey);
  const url = item.presetParams && item.presetParams.favorites_url;
  if (url) row.url = String(url);
  if (item.weblink) { row.kind = "link"; row.weblink = String(item.weblink); return row; }
  if (item.style === "itemNoAction") return row;

  const type = String(item.type || "");
  const go = buildAction(base, item, "go");
  if (type === "radio" || type === "checkbox" || item.radio !== undefined || item.checkbox !== undefined) {
    const doAct = buildAction(base, item, "do") || go;
    row.kind = "choice";
    row.choice = type === "checkbox" || item.checkbox !== undefined ? "checkbox" : "radio";
    row.selected = !!(Number(item.radio) || Number(item.checkbox) || item.selected);
    if (doAct) row.do = mint(doAct);
    return row;
  }
  if (go && (type === "search" || item.input || hasTaggedInput(go))) {
    row.kind = "search";
    row.search = mint(go);
    if (item.input && item.input.title) row.prompt = String(item.input.title);
    return row;
  }
  if (playableRow(item)) {
    row.kind = "playable";
    for (const [name, key] of [["play", "play"], ["add", "add"], ["add-hold", "insert"], ["more", "more"]]) {
      const act = buildAction(base, item, name);
      if (act) row[key] = mint(act);
    }
    // A touch-to-play leaf plays on tap; a playlist/album opens and offers Play from its menu.
    row.tap_plays = item.goAction === "play" || item.goAction === "playControl" || item.style === "itemplay";
    if (go && isBrowse(go) && !row.tap_plays) row.go = mint(go);
    if (!row.play && !row.add && !row.go) row.kind = "text";
    return row;
  }
  if (go) { row.kind = "node"; row.go = mint(go); return row; }
  const more = buildAction(base, item, "more");
  if (more) row.more = mint(more);
  return row;
}

/** A whole LMS menu answer as the client draws it. */
function shapeMenu(r, mint) {
  const items = (r && r.item_loop) || [];
  const base = (r && r.base) || {};
  const rows = items.filter(it => it && Object.keys(it).length).map(it => shapeRow(base, it, mint));
  const win = (r && r.window) || {};
  const notice = (win.textarea && String(win.textarea)) || (r && r.networkerror && String(r.networkerror)) || null;
  const pictured = rows.filter(x => (x.image_key || x.image_path) && (x.kind === "playable" || x.kind === "node")).length;
  const layout = win.windowStyle === "text_list" ? "list"
    : rows.length >= 4 && pictured / rows.length >= 0.6 && rows.some(x => x.kind === "playable") ? "grid" : "list";
  return {
    title: String(win.text || (r && r.title) || ""),
    total: Number(r && r.count) || 0,
    offset: Number(r && r.offset) || 0,
    layout, notice, rows,
  };
}

// ---------------------------------------------------------------------------
// Tokens

function makeTokenStore({ ttlMs = TOKEN_TTL_MS, cap = TOKEN_CAP, now = () => Date.now() } = {}) {
  const map = new Map();
  function sweep() {
    const cut = now() - ttlMs;
    for (const [k, v] of map) if (v.at < cut) map.delete(k);
    while (map.size > cap) map.delete(map.keys().next().value);
  }
  return {
    put(act) {
      const token = crypto.randomBytes(12).toString("base64url");
      map.set(token, { act, at: now() });
      if (map.size > cap || map.size % 500 === 0) sweep();
      return token;
    },
    get(token) {
      const e = map.get(String(token || ""));
      if (!e) return null;
      if (e.at < now() - ttlMs) { map.delete(token); return null; }
      return e.act;
    },
    clear() { map.clear(); },
    get size() { return map.size; },
  };
}

// ---------------------------------------------------------------------------
// The engine around LMS

/**
 * makeApps({ request, log }) — request(playerId, words) is lms.js's JSON-RPC
 * call. Each call that runs a command names the player: every `<tag> items` and
 * `<tag> playlist` dispatch is needs-client in LMS.
 */
function makeApps({ request, log }) {
  const tokens = makeTokenStore();
  let appTags = new Set();
  let listCache = null;   // { at, player, value }

  const mint = (act) => tokens.put(act);

  /** The apps and radio LMS offers, with a root token each. Cached 5 minutes. */
  async function list(playerId, force) {
    if (!force && listCache && listCache.player === playerId && Date.now() - listCache.at < 5 * 60 * 1000) return listCache.value;
    const read = async (words, pid) => { try { return await request(pid, words); } catch (e) { log.debug("apps: list", words[0], "failed:", e.message); return null; } };
    // With a player, `myapps items` applies each plugin's condition() (a plugin
    // that can't work for this player is left out); without one, `apps` lists all.
    let r = playerId ? await read(["myapps", "items", 0, 200, "menu:1"], playerId) : null;
    if (!r || !(r.item_loop || []).length) r = await read(["apps", 0, 200, "menu:1"], playerId || "");
    const radios = await read(["radios", 0, 200, "menu:radio"], playerId || "");
    const tags = new Set();
    const shape = (resp, kind) => ((resp && resp.item_loop) || []).map(it => {
      const go = buildAction(resp.base, it, "go");
      if (!go || !isBrowse(go)) return null;
      tags.add(go.cmd[0]);
      return { tag: go.cmd[0], kind, name: String(it.text || it.name || go.cmd[0]).split("\n")[0], ...rowImage(it), go };
    }).filter(Boolean);
    const apps = shape(r, "app"), radio = shape(radios, "radio");
    appTags = tags;
    const value = { apps, radio };
    listCache = { at: Date.now(), player: playerId, value };
    return value;
  }

  const publicEntry = (e) => ({ tag: e.tag, kind: e.kind, name: e.name, image_key: e.image_key || null, image_path: e.image_path || null, go: mint(e.go) });

  async function listPublic(playerId, force) {
    const { apps, radio } = await list(playerId, force);
    return { apps: apps.map(publicEntry), radio: radio.map(publicEntry) };
  }

  /** Run [act] (a browse answers a menu; anything else runs and answers what came of it). */
  async function run(act, playerId, start, count) {
    if (!appTags.size) await list(playerId);
    if (!allowedCommand(act.cmd, appTags)) {
      log.warn("apps: refused command", act.cmd.slice(0, 2).join(" "));
      const e = new Error("That action isn't available"); e.status = 403; throw e;
    }
    const words = commandWords(act, start, count);
    log.trace && log.trace("apps:", JSON.stringify(words).slice(0, 400));
    const r = await request(playerId || "", words);
    if (isBrowse(act)) return { menu: shapeMenu(r, mint) };
    // A command's answer: its one text row, if it gave one, is said as a toast.
    const loop = (r && r.item_loop) || [];
    const message = loop.length === 1 && loop[0] && loop[0].text ? String(loop[0].text) : (r && r.text ? String(r.text) : null);
    return { ok: true, next_window: act.nextWindow || null, message };
  }

  function lookup(token) {
    const act = tokens.get(token);
    if (!act) { const e = new Error("That menu has expired — open it again"); e.status = 410; e.expired = true; throw e; }
    return act;
  }

  return {
    list: listPublic,
    async browse(token, playerId, start, count) { return (await run(lookup(token), playerId, start, count)).menu; },
    async act(token, playerId) { return run(lookup(token), playerId, 0, DEFAULT_COUNT); },
    async search(token, term, playerId, start, count) {
      const act = lookup(token);
      if (!hasTaggedInput(act)) { const e = new Error("That row doesn't take a search"); e.status = 400; throw e; }
      return (await run(withTerm(act, term), playerId, start, count)).menu;
    },
    /** A reconnect or a server change: every token and the apps list go. */
    reset() { tokens.clear(); listCache = null; appTags = new Set(); },
    get tokenCount() { return tokens.size; },
  };
}

module.exports = makeApps;
module.exports._internal = { pickAction, buildAction, isBrowse, hasTaggedInput, commandWords, withTerm, allowedCommand, rowImage, playableRow, shapeRow, shapeMenu, makeTokenStore, TAGGED };
