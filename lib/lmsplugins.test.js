// @ts-check
"use strict";
const { makePlugins, parsePage, changeForm, publicList } = require("./lmsplugins");
const { pluginsPage } = require("./lmsplugins.fixture");
let pass = 0, fail = 0;
function ok(label, cond) { if (cond) { pass++; console.log("  [PASS]", label); } else { fail++; console.log("  [FAIL]", label); } }
const throws = (/** @type {() => any} */ fn, /** @type {RegExp} */ re) => { try { fn(); return false; } catch (e) { return re.test(e.message); } };

const material = { name: "MaterialSkin", title: "Material Skin", version: "5.9.2", desc: "A modern skin &lt;for&gt; LMS\nLine two",
  icon: "plugins/MaterialSkin/html/images/icon.png", settings: "plugins/MaterialSkin/settings.html", email: "craig@example.org", creator: "Craig",
  homepage: "https://github.com/CDrummond/lms-material", installType: /** @type {"install"} */ ("install") };
const fullText = { name: "FullTextSearch", title: "Full text search", version: "9.0", desc: "Search all the things", installType: /** @type {"manual"} */ ("manual") };
const broken = { name: "Broken", title: "Broken & Co", version: "1.0", desc: "x", error: "Failed to load", installType: /** @type {"install"} */ ("install") };
const podcast = { name: "Podcast", title: "Podcasts", version: "9.0", desc: "Shows", installType: /** @type {"manual"} */ ("manual"), fallbackIcon: true };
const spotty = { name: "Spotty", title: "Spotty Spotify", version: "4.10.1", desc: "Spotify for LMS", installs: 9001,
  icon: "https://raw.githubusercontent.com/michaelherger/spotty/icon.png", creator: "Michael Herger" };
const page = parsePage(pluginsPage({
  active: [material, fullText, broken], inactive: [podcast],
  updates: [{ ...material, version: "5.9.3", changes: "Fixes" }],
  avail: [{ title: "LMS Community plugins", entries: [spotty, { ...material, version: "5.9.3" }] }, { title: "Unsupported", weight: 10, entries: [{ name: "Odd", title: "Odd one", version: "0.1", desc: "?" }] }],
  repos: ["https://example.org/repo.xml"], auto: false, useUnsupported: true, rand: "feedface",
}));

ok("the form key is read", page.rand === "feedface");
ok("the three every-save settings are read", page.auto === false && page.useUnsupported === true && page.repos.length === 1 && page.repos[0] === "https://example.org/repo.xml");
ok("no risk page, no restart pending", !page.risk && !page.restart);
ok("the groups are read", page.enabled.length === 3 && page.disabled.length === 1 && page.updates.length === 1);
const m = page.enabled.find((p) => p.name === "MaterialSkin");
ok("title and version from the header label", !!m && m.title === "Material Skin" && m.version === "5.9.2");
ok("the description, entities decoded, line breaks kept", !!m && m.desc === "A modern skin <for> LMS\nLine two");
ok("settings, author and homepage from the footer", !!m && m.settings === "/plugins/MaterialSkin/settings.html" && m.creator === "Craig" && m.homepage === "https://github.com/CDrummond/lms-material");
ok("a repository plugin is an install, a built-in one manual", !!m && m.kind === "install" && page.enabled.find((p) => p.name === "FullTextSearch")?.kind === "manual");
ok("the icon is the resized path on LMS", !!m && m.icon === "/plugins/MaterialSkin/html/images/icon_50x50_p.png");
ok("the page's stand-in icon is no icon", page.disabled[0].icon === null);
const b = page.enabled.find((p) => p.name === "Broken");
ok("a load error is read, and an & in a title survives", !!b && b.error === "Failed to load" && b.title === "Broken & Co");
ok("an update is read by its own name, not update:NAME", page.updates[0].name === "MaterialSkin" && page.updates[0].version === "5.9.3" && page.updates[0].desc === "Fixes");
ok("repositories become available plugins with their repo's name", page.available.some((p) => p.name === "Spotty" && p.repo === "LMS Community plugins" && p.version === "4.10.1"));
ok("a version followed by an install count reads clean", page.available.find((p) => p.name === "Spotty")?.version === "4.10.1");
ok("every repository is read, the unsupported one too", page.available.some((p) => p.name === "Odd" && p.repo === "Unsupported"));
ok("an installed plugin isn't also offered from its repository", !page.available.some((p) => p.name === "MaterialSkin"));
ok("a plugin with no email keeps its author", page.available.find((p) => p.name === "Spotty")?.creator === "Michael Herger");

// Forms: only the one plugin's fields, the every-save settings echoed.
const install = changeForm(page, "Spotty", "install");
ok("install: install:NAME + NAME on, saveSettings and rand", install.get("saveSettings") === "1" && install.get("rand") === "feedface" && install.has("install:Spotty") && !!install.get("Spotty"));
ok("install: every-save settings echoed (auto off is ABSENT, as a browser sends it)", !install.has("auto") && install.get("useUnsupported") === "on" && install.getAll("repos").join() === "https://example.org/repo.xml");
ok("install: no other plugin's field is sent", [...install.keys()].filter((k) => /:/.test(k)).join() === "install:Spotty");
const remove = changeForm(page, "MaterialSkin", "remove");
ok("remove: install:NAME without the checkbox", remove.has("install:MaterialSkin") && !remove.has("MaterialSkin"));
const off = changeForm(page, "FullTextSearch", "disable");
ok("disable a built-in: manual:NAME without the checkbox", off.has("manual:FullTextSearch") && !off.has("FullTextSearch"));
const on = changeForm(page, "Podcast", "enable");
ok("enable a built-in: manual:NAME + NAME", on.has("manual:Podcast") && !!on.get("Podcast"));
const up = changeForm(page, "MaterialSkin", "update");
ok("update: update:NAME and nothing else of the plugin's", up.get("update:MaterialSkin") === "1" && !up.has("install:MaterialSkin"));
ok("a built-in plugin can't be removed", throws(() => changeForm(page, "FullTextSearch", "remove"), /part of LMS/));
ok("a repository plugin can't be merely disabled", throws(() => changeForm(page, "MaterialSkin", "disable"), /only removed/));
ok("an update that isn't offered is refused", throws(() => changeForm(page, "Spotty", "update"), /No update/));
ok("an unknown plugin is refused, not posted", throws(() => changeForm(page, "Nope", "install"), /doesn't list/));
ok("an unknown action is refused", throws(() => changeForm(page, "Spotty", "explode"), /Unknown action/));
ok("no form key, no form", throws(() => changeForm({ ...page, rand: null }, "Spotty", "install"), /form key/));

// What the apps get.
const pub = publicList(page);
const pm = pub.enabled.find((p) => p.name === "MaterialSkin");
ok("the apps are told what each plugin can do", !!pm && pm.can.join() === "update,remove" && pub.enabled.find((p) => p.name === "FullTextSearch")?.can.join() === "disable" && pub.disabled[0].can.join() === "enable" && pub.available.find((p) => p.name === "Spotty")?.can.join() === "install");
ok("an LMS icon is a proxied path, a web one an image key", !!pm && pm.image_path === "/plugins/MaterialSkin/html/images/icon_50x50_p.png" && /^url-/.test(String(pub.available.find((p) => p.name === "Spotty")?.image_key)));
ok("the apps never see the form key", !JSON.stringify(pub).includes("feedface"));

const riskPage = parsePage(pluginsPage({ risk: true, rand: "abc" }));
ok("the third-party warning is recognised, with its key", riskPage.risk && riskPage.rand === "abc" && !riskPage.enabled.length);
ok("a pending restart is recognised", parsePage(pluginsPage({ restart: true, active: [material] })).restart);

// The network half, against a fake LMS.
(async () => {
  /** @type {{ method: string, url: string, headers: any, body: string }[]} */
  const seen = [];
  let risk = true, installed = false;
  /** @type {any} */
  const fakeFetch = async (/** @type {string} */ url, /** @type {any} */ init) => {
    const body = String(init.body || "");
    seen.push({ method: init.method || "GET", url, headers: init.headers, body });
    const f = new URLSearchParams(body);
    if (f.get("acceptPluginRisk") === "1" && f.get("rand") === "k1") risk = false;
    if (f.get("saveSettings") === "1" && f.get("rand") === "k1" && f.has("install:Spotty") && f.get("Spotty")) installed = true;
    const text = pluginsPage(risk ? { risk: true, rand: "k1" } : { rand: "k1", restart: installed, active: installed ? [material, spotty] : [material], avail: installed ? [] : [{ title: "Community", entries: [spotty] }] });
    return { ok: true, status: 200, text: async () => text };
  };
  const plugins = makePlugins({ cfg: () => ({ host: "lms", port: 9000, username: "me", password: "pw" }), fetch: fakeFetch });
  const first = await plugins.list();
  ok("the list says when LMS's warning stands between us and the plugins", first.risk === true);
  let refused = null;
  try { await plugins.change("Spotty", "install"); } catch (e) { refused = e; }
  ok("a change before the warning is accepted is refused, and posts nothing", !!refused && refused.status === 409 && refused.risk === true && !seen.some((s) => s.method === "POST"));
  const accepted = await plugins.acceptRisk();
  ok("accepting posts acceptPluginRisk with the page's key", seen.some((s) => s.method === "POST" && /acceptPluginRisk=1/.test(s.body) && /rand=k1/.test(s.body)) && accepted.risk === false);
  const after = await plugins.change("Spotty", "install");
  ok("an install is posted and the answer read back", installed && after.enabled.some((p) => p.name === "Spotty") && after.restart === true);
  ok("LMS's address and page, with its login and the AJAX header", seen.every((s) => s.url === "http://lms:9000/settings/server/plugins.html" && s.headers.authorization === "Basic " + Buffer.from("me:pw").toString("base64") && s.headers["X-Requested-With"] === "XMLHttpRequest"));
  const denied = makePlugins({ cfg: () => ({ host: "lms", port: 9000 }), fetch: /** @type {any} */ (async () => ({ ok: false, status: 401, text: async () => "" })) });
  let e401 = null;
  try { await denied.list(); } catch (e) { e401 = e; }
  ok("LMS asking for a login says so", !!e401 && e401.status === 401 && /login/.test(e401.message));
  const none = makePlugins({ cfg: () => null, fetch: fakeFetch });
  let e0 = null;
  try { await none.list(); } catch (e) { e0 = e; }
  ok("no LMS, no request", !!e0 && /Not connected/.test(e0.message));
  console.log(`\n${pass}/${pass + fail} lmsplugins tests passed.`);
  if (fail) process.exit(1);
})();
