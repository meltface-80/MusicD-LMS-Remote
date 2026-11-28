// @ts-check
"use strict";
/*
 * lmsplugins.js — LMS's plugins, listed, installed, updated, removed, enabled and disabled from the apps (owner
 * request: "allow adding plugins"), for Mallard and the web app alike.
 *
 * LMS HAS NO COMMAND FOR THIS. Installing, updating and removing a plugin happen only through its own Settings →
 * Plugins page (Slim/Web/Settings/Server/Plugins.pm), and Material's CLI helpers can only apply updates (a plugin
 * Material installs isn't on LMS's install list, so LMS uninstalls it again). So this drives LMS's page, at LMS's own
 * address, as a browser would:
 *   - READ: GET settings/server/plugins.html and parse it. The page is server-rendered with a fixed shape
 *     (plugins_main.html): groups #updatePlugins, #activePlugins, #inactivePlugins and #otherPlugins<N> (one per
 *     repository, its title in #otherPlugins<N>_Header); each plugin an <li id="plugin-NAME"> with its icon, title and
 *     (vVERSION), description and links, and a hidden "install:NAME" (a repository plugin) or "manual:NAME" (one built
 *     into LMS). The form carries `rand`, a secret LMS makes fresh at each start, without which nothing is saved.
 *   - WRITE: POST the same page with saveSettings=1, rand, ONLY the one plugin's fields — LMS acts on the fields
 *     present (install:/manual: with the plugin's own checkbox for on, without it for off; update:NAME for an update) —
 *     and the three settings every save rewrites, echoed from the page so none is lost: auto, useUnsupported, repos.
 *   - RISK: until it is accepted (for a year), LMS answers with its "plugins are third-party code" page instead and
 *     drops the save; acceptRisk() posts acceptPluginRisk=1, and only when the person has said yes in the app.
 *   - REPOSITORIES: the page's `repos` inputs are the list of extra repository addresses, and a save makes LMS's list
 *     exactly the ones posted (Plugins.pm: each new one addRepo, each missing one removeRepo), keeping only those
 *     matching ^https?://.*\.xml with trailing non-word characters cut. So adding or removing one is a save of the
 *     whole list with that one changed (reposForm), and LMS then fetches the new repository's XML itself.
 * Every change takes effect when LMS restarts (index.js: restartserver).
 */

/**
 * @typedef {{ name: string, title: string, version: string, desc: string, error: string|null, icon: string|null, creator: string,
 *   homepage: string|null, settings: string|null, kind: "install"|"manual"|null, repo?: string }} Plugin
 * @typedef {{ rand: string|null, risk: boolean, restart: boolean, auto: boolean, useUnsupported: boolean, repos: string[],
 *   updates: Plugin[], enabled: Plugin[], disabled: Plugin[], available: Plugin[] }} PluginPage
 */

const { rowImage } = require("./apps")._internal;

const decode = (/** @type {string} */ s) => String(s || "")
  .replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, "")
  .replace(/&nbsp;/g, " ").replace(/&quot;/g, '"').replace(/&#39;|&#x27;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&")
  .replace(/[ \t]+/g, " ").trim();
const attr = (/** @type {string} */ tag, /** @type {string} */ name) => {
  const m = new RegExp("\\b" + name + "\\s*=\\s*\"([^\"]*)\"", "i").exec(tag);
  return m ? decode(m[1]) : null;
};

/** One <li id="plugin-…"> as a plugin. @param {string} li @returns {Plugin|null} */
function parsePlugin(li) {
  const id = /id="plugin-(?:update:)?([^"]+)"/.exec(li);
  if (!id) return null;
  const name = decode(id[1]);
  const img = /<img\b[^>]*>/i.exec(li);
  // The page's own stand-in (a category glyph) is no picture of the plugin: the apps draw their own.
  const icon = img && !/class="pluginFallbackIcon"/.test(img[0]) ? attr(img[0], "src") : null;
  const label = /<label[^>]*class="headerLabel"[^>]*>([\s\S]*?)<\/label>/i.exec(li);
  const head = label ? decode(label[1]) : "";
  const box = label ? /<input\b[^>]*>/i.exec(label[1]) : null;
  const tv = /^(.*?)\s*\(v([^ )-]+)/.exec(head);
  const desc = /<div class="pluginDesc">([\s\S]*?)<\/div>/i.exec(li);
  const error = desc && /^\s*<b>([\s\S]*?)<\/b>/i.exec(desc[1]);
  const kind = /name="(install|manual):[^"]+"/.exec(li);
  const footer = /<div class="pluginFooter">([\s\S]*?)<\/div>/i.exec(li);
  const links = footer ? [...footer[1].matchAll(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)] : [];
  const settings = links.find((l) => !/^mailto:|^https?:/i.test(l[1]));
  const homepage = links.find((l) => /^https?:/i.test(l[1]));
  const mail = links.find((l) => /^mailto:/i.test(l[1]));
  return {
    name, title: (box && attr(box[0], "value")) || (tv ? tv[1] : head || name), version: tv ? tv[2] : "",
    desc: desc ? decode(desc[1]) : "", error: error ? decode(error[1]) : null, icon,
    creator: mail ? decode(mail[2]) : footer ? decode(footer[1].replace(/<a\b[\s\S]*?<\/a>/gi, "")) : "",
    homepage: homepage ? decode(homepage[1]) : null, settings: settings ? decode(settings[1]) : null,
    kind: kind ? /** @type {"install"|"manual"} */ (kind[1]) : null,
  };
}

/** The plugins in one group (a <div … id="GROUP"> … up to the next group). @param {string} html @param {string} id */
function group(html, id) {
  const at = html.indexOf('id="' + id + '"');
  if (at < 0) return [];
  const rest = html.slice(at);
  const next = rest.slice(1).search(/id="(?:updatePlugins|activePlugins|inactivePlugins|otherPlugins\d+(?:_Header)?)"/);
  const part = next < 0 ? rest : rest.slice(0, next + 1);
  return part.split(/<li\b/).slice(1).map((li) => parsePlugin("<li" + li)).filter(/** @returns {p is Plugin} */ (p) => !!p);
}

/** LMS's plugin page, read. @param {string} html @returns {PluginPage} */
function parsePage(html) {
  const s = String(html || "");
  const rand = /name="rand"[^>]*value="([^"]*)"|value="([^"]*)"[^>]*name="rand"/.exec(s);
  const risk = /name="acceptPluginRisk"/.test(s);
  // A change waiting for LMS to restart: the page's warning (Plugins.pm getRestartMessage) says so.
  const restart = /id="(?:restartWarning|popupWarning)"/.test(s);
  const box = (/** @type {string} */ n) => { const m = new RegExp('<input[^>]*name="' + n + '"[^>]*>', "i").exec(s); return !!m && /checked/i.test(m[0]); };
  const repos = [...s.matchAll(/<input[^>]*name="repos"[^>]*>/gi)].map((m) => attr(m[0], "value") || "").filter(Boolean);
  /** @type {Plugin[]} */
  const available = [];
  for (const m of s.matchAll(/id="(otherPlugins(\d+))_Header"[^>]*>([\s\S]*?)<\/div>/g)) {
    const repo = decode(m[3]);
    for (const p of group(s, m[1])) available.push({ ...p, repo });
  }
  const enabled = group(s, "activePlugins"), disabled = group(s, "inactivePlugins");
  // A plugin installed is not also "available" (the page lists it in its repository too).
  const have = new Set([...enabled, ...disabled].map((p) => p.name));
  return {
    rand: rand ? decode(rand[1] || rand[2]) : null, risk, restart,
    auto: box("auto"), useUnsupported: box("useUnsupported"), repos,
    updates: group(s, "updatePlugins"), enabled, disabled, available: available.filter((p) => !have.has(p.name)),
  };
}

/**
 * The form that does one thing to one plugin, as LMS's page would post it, with the three settings every save
 * rewrites echoed. Throws for an action the plugin's kind doesn't have (a repository plugin can't be disabled, only
 * removed; a built-in one can't be removed). @param {PluginPage} page @param {string} name @param {string} action
 * @returns {URLSearchParams}
 */
function changeForm(page, name, action) {
  if (!page.rand) throw new Error("LMS's plugin page didn't give its form key");
  const all = [...page.enabled, ...page.disabled, ...page.available];
  const p = all.find((x) => x.name === name);
  const f = new URLSearchParams();
  f.set("saveSettings", "1");
  f.set("rand", page.rand);
  if (page.auto) f.set("auto", "on");
  if (page.useUnsupported) f.set("useUnsupported", "on");
  for (const r of page.repos) f.append("repos", r);
  if (action === "update") {
    if (!page.updates.some((u) => u.name === name)) throw new Error("No update for " + name);
    f.set("update:" + name, "1");
    return f;
  }
  if (!p) throw new Error("LMS doesn't list a plugin called " + name);
  const kind = p.kind || "install";
  if (action === "install" || action === "enable") {
    if (action === "install" && kind !== "install") throw new Error(p.title + " is part of LMS: enable it instead");
    f.set(kind + ":" + name, ""); f.set(name, p.title || "1");
    return f;
  }
  if (action === "remove" || action === "disable") {
    if (action === "disable" && kind === "install") throw new Error(p.title + " can't be switched off, only removed");
    if (action === "remove" && kind === "manual") throw new Error(p.title + " is part of LMS: disable it instead");
    f.set(kind + ":" + name, "");
    return f;
  }
  throw new Error("Unknown action " + action);
}

/** LMS's rule for a repository address (Plugins.pm), and its trim. */
const REPO_RE = /^https?:\/\/\S*\.xml/i;
const repoTrim = (/** @type {string} */ u) => String(u || "").trim().replace(/\W*$/, "");

/**
 * The form that makes LMS's repository list [repos] (every-save settings echoed; no plugin touched).
 * @param {PluginPage} page @param {string[]} repos @returns {URLSearchParams}
 */
function reposForm(page, repos) {
  if (!page.rand) throw new Error("LMS's plugin page didn't give its form key");
  const f = new URLSearchParams();
  f.set("saveSettings", "1");
  f.set("rand", page.rand);
  if (page.auto) f.set("auto", "on");
  if (page.useUnsupported) f.set("useUnsupported", "on");
  for (const r of repos) f.append("repos", r);
  return f;
}

/**
 * The page's plugins as the apps draw them: each icon as the Apps engine gives one (an image_key for a picture on the
 * web, an image_path on LMS that this server proxies), and what can be done to each, so neither app decides that.
 * @param {PluginPage} page
 */
function publicList(page) {
  const updates = new Set(page.updates.map((u) => u.name));
  const shape = (/** @type {Plugin} */ p, /** @type {string} */ state) => {
    const { icon, kind, ...rest } = p;
    const builtIn = kind === "manual";
    /** @type {string[]} */
    const can = state === "available" ? ["install"]
      : state === "enabled" ? [builtIn ? "disable" : "remove"] : ["enable"];   // a removed plugin waiting for the restart can come back
    if (updates.has(p.name)) can.unshift("update");
    return { ...rest, ...rowImage({ icon: icon || "" }), state, builtIn, update: updates.has(p.name), can };
  };
  return {
    risk: page.risk, restart: page.restart, repos: page.repos,
    enabled: page.enabled.map((p) => shape(p, "enabled")),
    disabled: page.disabled.map((p) => shape(p, "disabled")),
    available: page.available.map((p) => shape(p, "available")),
    updates: page.updates.map((p) => p.name),
  };
}

/**
 * The network half: LMS's page read and posted, with LMS's own login when it has one.
 * @param {{ cfg: () => ({ host: string, port: number|string, username?: string, password?: string } | null),
 *   fetch?: typeof globalThis.fetch, wait?: (ms: number) => Promise<void> }} deps
 */
function makePlugins(deps) {
  const fetchImpl = deps.fetch || globalThis.fetch;
  const wait = deps.wait || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const url = () => { const c = deps.cfg(); if (!c) throw new Error("Not connected to LMS"); return { c, u: `http://${c.host}:${c.port}/settings/server/plugins.html` }; };
  const headers = (/** @type {any} */ c) => {
    /** @type {Record<string, string>} */
    const h = { "X-Requested-With": "XMLHttpRequest" };   // LMS's CSRF check accepts it (Slim/Web/HTTP/CSRF.pm)
    if (c.username) h.authorization = "Basic " + Buffer.from(`${c.username}:${c.password || ""}`).toString("base64");
    return h;
  };
  async function read() {
    const { c, u } = url();
    const r = await fetchImpl(u, { headers: headers(c), signal: AbortSignal.timeout(20000) });
    if (r.status === 401) throw Object.assign(new Error("LMS asks for a login: add it in Settings → LMS server"), { status: 401 });
    if (r.status === 403) throw Object.assign(new Error("LMS refused its settings page to this server (Settings → Security → Protect settings)"), { status: 403 });
    if (!r.ok) throw new Error("LMS's plugin page answered HTTP " + r.status);
    return parsePage(await r.text());
  }
  /** @param {URLSearchParams} form */
  async function post(form) {
    const { c, u } = url();
    // LMS waits up to 30 s for downloads to finish before it answers (Plugins.pm MAX_DOWNLOAD_WAIT).
    const r = await fetchImpl(u, { method: "POST", headers: { ...headers(c), "Content-Type": "application/x-www-form-urlencoded" }, body: form.toString(), signal: AbortSignal.timeout(45000) });
    if (!r.ok) throw new Error("LMS didn't take the change (HTTP " + r.status + ")");
    return parsePage(await r.text());
  }
  return {
    read,
    async list() { return publicList(await read()); },
    /** The person said yes to LMS's third-party-plugins warning in the app: accepted for a year, as on LMS's page. */
    async acceptRisk() {
      const page = await read();
      if (!page.risk) return publicList(page);
      const f = new URLSearchParams({ acceptPluginRisk: "1", rand: page.rand || "" });
      return publicList(await post(f));
    },
    /** @param {string} name @param {string} action */
    async change(name, action) {
      const page = await read();
      if (page.risk) throw Object.assign(new Error("Accept LMS's plugin warning first"), { status: 409, risk: true });
      return publicList(await post(changeForm(page, name, action)));
    },
    /**
     * A repository address added to LMS's list, or removed from it. LMS fetches a new one's XML in the background, so
     * the page is read again a moment later for its plugins (a slow or broken one simply offers nothing yet).
     * @param {{ add?: string, remove?: string }} what
     */
    async repo(what) {
      const page = await read();
      if (page.risk) throw Object.assign(new Error("Accept LMS's plugin warning first"), { status: 409, risk: true });
      let list = page.repos.slice();
      if (what.add != null) {
        const u = repoTrim(what.add);
        if (!REPO_RE.test(u)) throw Object.assign(new Error("A repository is the address of its .xml file (http:// or https://, ending .xml)"), { status: 400 });
        if (list.includes(u)) return publicList(page);
        list.push(u);
      } else if (what.remove != null) {
        const u = repoTrim(what.remove);
        if (!list.includes(u)) throw Object.assign(new Error("LMS doesn't list that repository"), { status: 400 });
        list = list.filter((r) => r !== u);
      } else throw Object.assign(new Error("add or remove required"), { status: 400 });
      const after = await post(reposForm(page, list));
      if (what.add == null) return publicList(after);
      await wait(2500);
      return publicList(await read());
    },
  };
}

module.exports = { makePlugins, parsePage, changeForm, reposForm, publicList, REPO_RE };
