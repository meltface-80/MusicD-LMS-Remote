"use strict";

/* Generic plugin browsing. The menus below are XMLBrowser's own shapes
 * (Slim/Control/XMLBrowser.pm: base.actions templates, itemsParams, item params
 * only on playable rows, search rows carrying __TAGGEDINPUT__, textarea hoisted
 * into window), and the merge rule is Material's browseBuildCommand. */

const makeApps = require("./apps");
const { buildAction, commandWords, withTerm, allowedCommand, rowImage, shapeMenu, makeTokenStore, TAGGED } = makeApps._internal;

let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log("  [PASS]", label); }
  else { fail++; console.log("  [FAIL]", label, extra !== undefined ? "— " + JSON.stringify(extra) : ""); }
}
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// A plugin's menu as XMLBrowser answers it under menu:1.
const BASE = {
  actions: {
    go:   { cmd: ["tidal", "items"], params: { menu: "tidal" }, itemsParams: "params" },
    play: { cmd: ["tidal", "playlist", "play"], params: { menu: "tidal" }, itemsParams: "params", nextWindow: "nowPlaying" },
    add:  { cmd: ["tidal", "playlist", "add"], params: { menu: "tidal" }, itemsParams: "params" },
    "add-hold": { cmd: ["tidal", "playlist", "insert"], params: { menu: "tidal" }, itemsParams: "params" },
    more: { cmd: ["tidal", "items"], params: { menu: "tidal" }, itemsParams: "params", window: { isContextMenu: 1 } },
  }
};
const MENU = {
  count: 6, offset: 0, base: BASE,
  window: { windowStyle: "icon_list", text: "TIDAL" },
  item_loop: [
    { text: "Search", type: "search", input: { len: 1, title: "Search TIDAL" },
      actions: { go: { cmd: ["tidal", "items"], params: { menu: "tidal", item_id: "0", search: TAGGED } } } },
    { text: "My Mixes", type: "link", addAction: "go", actions: { go: { cmd: ["tidal", "items"], params: { menu: "tidal", item_id: "1" } } },
      "icon-id": "/plugins/TIDAL/html/mixes.png" },
    { text: "Kid A\nRadiohead", type: "playlist", params: { item_id: "2", isContextMenu: 1 }, icon: "https://resources.tidal.com/images/x/320x320.jpg",
      presetParams: { favorites_url: "tidal://album:123" } },
    { text: "Everything In Its Right Place\nRadiohead", type: "audio", params: { item_id: "3" }, goAction: "play", style: "itemplay",
      icon: "/imageproxy/https%3A%2F%2Fresources.tidal.com%2Fimages%2Fy%2F320x320.jpg/image.jpg" },
    { text: "Account", style: "itemNoAction" },
    { text: "Tidal Connect", weblink: "https://tidal.com/connect" },
  ]
};

console.log("merge rule");
{
  const it = MENU.item_loop[2];
  const play = buildAction(BASE, it, "play");
  ok("action params first, then the row's itemsParams", eq(play.params, { menu: "tidal", item_id: "2", isContextMenu: 1 }), play.params);
  ok("the action's nextWindow is kept", play.nextWindow === "nowPlaying");
  const first = buildAction({ actions: { play: { cmd: ["x", "playlist", "play"], params: { item_id: "A" }, itemsParams: "params" } } },
    { params: { item_id: "B", touchToPlay: "1", empty: "" } }, "play");
  ok("first writer wins: the action's own param over the row's", first.params.item_id === "A");
  ok("touchToPlay and empty values dropped", !("touchToPlay" in first.params) && !("empty" in first.params));
  const noItems = buildAction({ actions: { go: { cmd: ["x", "items"], params: { menu: "x" } } } }, { params: { item_id: "9" } }, "go");
  ok("row params used only when the action names them (itemsParams)", eq(noItems.params, { menu: "x" }), noItems.params);
  ok("a row's own go wins over base's", buildAction(BASE, MENU.item_loop[1], "go").params.item_id === "1");
  ok("'none' is no action", buildAction({ actions: { play: "none" } }, { params: { item_id: "1" } }, "play") === null);
  ok("go falls back to do", buildAction({}, { actions: { do: { cmd: ["x", "items"], params: {} } } }, "go").cmd[0] === "x");
  const parent = buildAction({ parentParams: { item_id: "P" }, actions: { go: { cmd: ["x", "items"], params: {} } } }, {}, "go");
  ok("base.parentParams appended", parent.params.item_id === "P");
}

console.log("commands");
{
  const go = buildAction(BASE, MENU.item_loop[1], "go");
  ok("a browse gets paging, then its params", eq(commandWords(go, 0, 50), ["tidal", "items", 0, 50, "menu:tidal", "item_id:1"]), commandWords(go, 0, 50));
  ok("a browse with no menu param asks for the menu format", commandWords({ cmd: ["x", "items"], params: {} }, 0, 10).includes("menu:1"));
  ok("count clamped to 500", commandWords(go, 0, 99999)[3] === 500);
  const play = buildAction(BASE, MENU.item_loop[2], "play");
  ok("a play has no paging", eq(commandWords(play, 0, 50), ["tidal", "playlist", "play", "menu:tidal", "item_id:2", "isContextMenu:1"]));
  const s = withTerm(buildAction(BASE, MENU.item_loop[0], "go"), "kid a");
  ok("the search term replaces __TAGGEDINPUT__", s.params.search === "kid a" && !JSON.stringify(s).includes(TAGGED));
}

console.log("allowlist");
{
  const tags = new Set(["tidal", "qobuz"]);
  ok("an advertised app, any verb of its own", allowedCommand(["tidal", "items"], tags) && allowedCommand(["podcasts", "items"], new Set(["podcasts"])) && allowedCommand(["tidal", "subscribe"], tags));
  ok("a standard menu with a known verb", allowedCommand(["trackinfo", "items"], tags) && allowedCommand(["jivefavorites", "add"], tags));
  ok("anything else refused", !allowedCommand(["rescan"], tags) && !allowedCommand(["pref", "x"], tags) && !allowedCommand(["playlist", "clear"], tags));
  ok("a standard menu with an unknown verb refused", !allowedCommand(["favorites", "rename"], tags));
  ok("an unadvertised plugin refused", !allowedCommand(["spotty", "items"], tags));
}

console.log("pictures");
{
  ok("absolute → url- key", rowImage({ icon: "https://a/b.jpg" }).image_key === "url-" + Buffer.from("https://a/b.jpg").toString("base64url"));
  ok("imageproxy unwrapped to its url", rowImage({ icon: "/imageproxy/https%3A%2F%2Fa%2Fb.jpg/image.jpg" }).image_key === "url-" + Buffer.from("https://a/b.jpg").toString("base64url"));
  ok("server path → image_path, rooted", rowImage({ "icon-id": "plugins/Qobuz/html/images/icon.png" }).image_path === "/plugins/Qobuz/html/images/icon.png");
  ok("a cover id → image_key", rowImage({ "icon-id": "a3f9c210" }).image_key === "a3f9c210" && rowImage({ "icon-id": "1234" }).image_key === "1234");
  ok("a negative cover id (LMS's placeholder) → nothing", eq(rowImage({ "icon-id": "-1234" }), {}));
  ok("none → nothing", eq(rowImage({}), {}));
}

console.log("rows");
{
  const seen = [];
  const mint = (act) => { seen.push(act); return "t" + (seen.length - 1); };
  const m = shapeMenu(MENU, mint);
  const [search, node, album, track, text, link] = m.rows;
  ok("title, total, offset", m.title === "TIDAL" && m.total === 6 && m.offset === 0);
  ok("search row asks for a term", search.kind === "search" && search.prompt === "Search TIDAL" && !!search.search);
  ok("a folder opens", node.kind === "node" && !!node.go && node.image_path === "/plugins/TIDAL/html/mixes.png");
  ok("an album: play, add, insert, more and open", album.kind === "playable" && album.play && album.add && album.insert && album.more && album.go && !album.tap_plays);
  ok("its two lines", album.title === "Kid A" && album.subtitle === "Radiohead");
  ok("its durable url passed through", album.url === "tidal://album:123");
  ok("a touch-to-play track plays on tap, no open", track.kind === "playable" && track.tap_plays && !track.go);
  ok("no-action row is text", text.kind === "text" && !text.go);
  ok("a weblink is a link", link.kind === "link" && link.weblink === "https://tidal.com/connect");
  ok("the client gets tokens, never commands", !JSON.stringify(m).includes("playlist") && !JSON.stringify(m).includes("item_id"));
  const login = shapeMenu({ count: 0, item_loop: [], window: { textarea: "Please sign in to TIDAL in LMS's settings." } }, mint);
  ok("a hoisted textarea is the notice", login.notice === "Please sign in to TIDAL in LMS's settings." && login.rows.length === 0);
  ok("a network error is the notice", shapeMenu({ count: 0, networkerror: "timeout" }, mint).notice === "timeout");
  const radio = shapeMenu({ item_loop: [{ text: "Random album", radio: 1, actions: { do: { cmd: ["dstm", "items"], params: {} } } }, { text: "Off", radio: 0, actions: { do: { cmd: ["dstm", "items"], params: {} } } }] }, mint);
  ok("radio choices, the chosen one marked", radio.rows[0].kind === "choice" && radio.rows[0].selected && !radio.rows[1].selected && !!radio.rows[0].do);
  ok("a grid when most rows are pictured albums", shapeMenu({ item_loop: Array(5).fill(MENU.item_loop[2]), base: BASE }, mint).layout === "grid");
}

console.log("tokens");
{
  let t = 0;
  const store = makeTokenStore({ ttlMs: 1000, cap: 3, now: () => t });
  const a = store.put({ cmd: ["x", "items"], params: {} });
  ok("a token answers its action", store.get(a).cmd[0] === "x");
  t = 2000;
  ok("an expired token answers nothing", store.get(a) === null);
  ok("an unknown token answers nothing", store.get("nope") === null && store.get(undefined) === null);
  t = 0; for (let i = 0; i < 10; i++) store.put({ cmd: ["x", "items"], params: {} });
  ok("the store is capped", store.size <= 3, store.size);
}

console.log("engine");
(async () => {
  const sent = [];
  const answers = {
    myapps: { item_loop: [{ text: "TIDAL", "icon-id": "plugins/TIDAL/html/icon.png", actions: { go: { cmd: ["tidal", "items"], params: { menu: "tidal" } } } }] },
    radios: { item_loop: [{ text: "TuneIn", actions: { go: { cmd: ["tunein", "items"], params: { menu: "radio" } } } }] },
    tidal: MENU,
  };
  const request = async (pid, words) => {
    sent.push([pid, words]);
    if (words[0] === "tidal" && words[1] === "playlist") return { count: 0 };
    if (words[0] === "spotty") { const e = new Error("socket hang up"); throw e; }
    return answers[words[0]] || {};
  };
  const log = { debug() {}, warn() {}, trace() {} };
  const apps = makeApps({ request, log });
  const l = await apps.list("aa:bb");
  ok("apps and radio listed, each with a root token", l.apps[0].tag === "tidal" && l.apps[0].image_path === "/plugins/TIDAL/html/icon.png" && !!l.apps[0].go && l.radio[0].tag === "tunein");
  ok("myapps asked with the player (condition applies)", sent[0][0] === "aa:bb" && sent[0][1][0] === "myapps");
  const root = await apps.browse(l.apps[0].go, "aa:bb", 0, 50);
  ok("the root browses with the player, paged", sent.some(s => s[0] === "aa:bb" && s[1][0] === "tidal" && s[1][2] === 0 && s[1][3] === 50) && root.rows.length === 6);
  const r = await apps.act(root.rows[2].play, "aa:bb");
  ok("play runs the merged command and says where to go", r.ok && r.next_window === "nowPlaying" && sent.some(s => s[1].join(" ") === "tidal playlist play menu:tidal item_id:2 isContextMenu:1"));
  const more = await apps.act(root.rows[2].more, "aa:bb");
  ok("more answers a menu", Array.isArray(more.menu && more.menu.rows));
  await apps.search(root.rows[0].search, "kid a", "aa:bb", 0, 20);
  ok("search sends the term", sent.some(s => s[1].includes("search:kid a")));
  let threw = null;
  try { await apps.search(root.rows[1].go, "x", "aa:bb"); } catch (e) { threw = e; }
  ok("a search on a row that takes none is refused", threw && threw.status === 400);
  threw = null;
  try { await apps.browse("forged", "aa:bb"); } catch (e) { threw = e; }
  ok("an unknown token is 410", threw && threw.status === 410 && threw.expired);
  apps.reset();
  threw = null;
  try { await apps.browse(l.apps[0].go, "aa:bb"); } catch (e) { threw = e; }
  ok("a reset forgets every token", threw && threw.status === 410);

  console.log(`\n${pass}/${pass + fail} apps tests passed.`);
  if (fail) process.exit(1);
})();
