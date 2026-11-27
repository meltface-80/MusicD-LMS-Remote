// @ts-check
"use strict";
/*
 * LMS's Settings → Plugins page as LMS renders it, for lib/lmsplugins.test.js and the end-to-end fake LMS. Each piece
 * is written out from the template it stands for (HTML/EN/settings/server/plugins_main.html's pluginDetails block,
 * plugin-risk.html, Plugins.pm's restart warning), whitespace and all, so the parser is tested against LMS's markup
 * and not against a tidier copy of it.
 */

/**
 * @typedef {{ name: string, title: string, version: string, desc?: string, icon?: string, fallbackIcon?: boolean,
 *   installs?: number, error?: string, settings?: string, email?: string, creator?: string, homepage?: string,
 *   installType?: "install"|"manual", changes?: string }} FixturePlugin
 */

const html = (/** @type {string} */ s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** One plugin, as pluginDetails draws it. @param {FixturePlugin} p @param {string} type */
function li(p, type) {
  const pluginName = type === "update" ? "update:" + p.name : p.name;
  const icon = p.icon || "html/images/misc.svg";
  const desc = p.error ? "<b>" + p.error + "</b>" : (type === "update" && p.changes) || p.desc || "";
  return `
	<li class="thumbwrap selectorMarker" id="plugin-${pluginName}">
		<div class="thumbwrap">
			<div class="pluginItem">
				<div>
					<div>
						<img src="${/^http/.test(icon) ? icon : "/" + icon.replace(/\.png$/, "_50x50_p.png")}" ${p.fallbackIcon ? 'class="pluginFallbackIcon"' : ""} onerror="this.onerror = null; this.classList.add('pluginFallbackIcon'); this.src = '/html/images/misc.svg'; if (this.srcset) this.srcset = '/html/images/misc.svg'">
						<label for="${pluginName}" class="headerLabel">
							<input name="${pluginName}" id="${pluginName}" ${type === "enabled" && !p.error ? "checked=checked" : ""} type="checkbox" value="${html(p.title)}" ${type === "unsafe" ? 'class="unsafePlugin"' : ""} />
							&nbsp;${p.title} (v${p.version}${p.installs ? ` - <img src="/html/images/download.svg">${p.installs}` : ""})
						</label>
					</div>
					${type !== "update" ? `<input name="${p.installType || "install"}:${p.name}" type="hidden" />` : ""}
				</div>
				<div>
					<label for="${pluginName}">
						<div class="pluginDesc">
							${desc.replace(/\n/g, "<br />")}
						</div>
					</label>
				</div>
				<div class="pluginFooter">
					${p.settings ? `<img src="/html/images/cog-outline.svg"> <a href="/${p.settings}">Settings</a>` : ""}
					${p.email ? `<img src="/html/images/email-outline.svg"> <a href="mailto:${p.email}">` : ""}${p.creator || p.email || ""}${p.email ? "</a>" : ""}
					${p.homepage ? `<img src="/html/images/web.svg"> <a href="${p.homepage}" target="plugin_desc">More information</a>` : ""}
				</div>
			</div>
		</div>
	</li>`;
}

/** @param {string} id @param {string} title @param {string} body */
const section = (id, title, body) => `
		<div class="settingSection">
			<div class="prefHead collapsableSection" id="${id}_Header"><img class="disclosure_repos"
				src="/html/images/spacer.gif"/>${title}</div>
		</div>
	<div class="settingSection pluginList" id="${id}">
		<ul class="thumbwrap">${body}
		</ul>
	</div>`;

/**
 * The whole page. @param {{ updates?: FixturePlugin[], active?: FixturePlugin[], inactive?: FixturePlugin[],
 *   avail?: { title: string, weight?: number, entries: FixturePlugin[] }[], auto?: boolean, useUnsupported?: boolean,
 *   repos?: string[], rand?: string, risk?: boolean, restart?: boolean }} o
 */
function pluginsPage(o) {
  const rand = o.rand || "0123456789abcdef0123456789abcdef";
  const head = `<html><head><title>Plugins</title></head><body><form name="settingsForm" method="post">
${o.restart ? `<div id="statusarea"><span id="restartWarning">Changes to plugins need LMS to restart. <a href="/settings/server/plugins.html?restart=1&rand=${rand}">Restart now</a></span></div>` : ""}`;
  const foot = "\n</form></body></html>";
  if (o.risk) {
    return head + `
		<h1>Third-party plugins</h1>
		<div class="stdedit agreementBox">
			<input type="checkbox" name="acceptPluginRisk" id="acceptPluginRisk" value="1" required="required" />
			<label for="acceptPluginRisk">I understand</label>
		</div>
	<input type="hidden" name="rand" value="${rand}" />` + foot;
  }
  let body = `\n	<div id="pluginListPanel" class="settingsSmallIndention ">`;
  if ((o.updates || []).length) body += section("updatePlugins", "Updates", (o.updates || []).map((p) => li(p, "update")).join(""));
  body += section("activePlugins", "Active plugins", (o.active || []).map((p) => li(p, "enabled")).join(""));
  body += section("inactivePlugins", "Inactive plugins", (o.inactive || []).map((p) => li(p, "disabled")).join(""));
  (o.avail || []).forEach((repo, i) => {
    body += section("otherPlugins" + i, repo.title, repo.entries.map((p) => li(p, (repo.weight || 1) === 1 ? "safe" : "unsafe")).join(""));
  });
  body += `\n	</div>
		<input type="checkbox" name="auto" id="auto" ${o.auto ? ' checked="1" ' : ""} />
		<label for="auto">Automatically update</label>
		<div>${[...(o.repos || []), ""].map((r) => `
			<br/><input type="text" class="stdedit" name="repos" id="repos" value="${html(r)}" size="60" />`).join("")}
		</div>
		<input type="checkbox" name="useUnsupported" id="useUnsupported" ${o.useUnsupported ? ' checked="1" ' : ""} />
		<label for="useUnsupported">Show unsupported</label>
	<input name="rand" type="hidden" value="${rand}" />`;
  return head + body + foot;
}

module.exports = { pluginsPage };
