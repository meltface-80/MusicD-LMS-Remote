package com.musicd.mallard.client

import org.json.JSONArray
import org.json.JSONObject

/*
 * LMS's plugins as MusicD LMS Remote reads them from LMS's own plugin page (/api/lms/plugins, lib/lmsplugins.js): what is
 * installed, what LMS's repositories offer, updates, and whether a change is waiting for LMS to restart. The server says
 * what each plugin can do ([LmsPlugin.can]); Mallard and the web app (public/lms-plugins.js) only draw it, so the two
 * apps offer the same buttons on the same plugins.
 */

private fun JSONObject.text(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** One plugin: [state] enabled, disabled or available; [can] what may be done to it, in the order the buttons go. */
data class LmsPlugin(
    val name: String, val title: String, val version: String, val desc: String, val error: String?, val creator: String,
    val homepage: String?, val settings: String?, val repo: String?, val imageKey: String?, val imagePath: String?,
    val state: String, val builtIn: Boolean, val update: Boolean, val can: List<String>
) {
    /** Whether [q] (already lower-cased and trimmed) finds it: in its title, description, author or name. */
    fun matches(q: String): Boolean = q.isEmpty() || "$title $desc $creator $name".lowercase().contains(q)

    companion object {
        fun parse(j: JSONObject): LmsPlugin {
            val can = j.optJSONArray("can")
            return LmsPlugin(
                j.optString("name"), j.text("title") ?: j.optString("name"), j.optString("version"), j.optString("desc"), j.text("error"),
                j.optString("creator"), j.text("homepage"), j.text("settings"), j.text("repo"), j.text("image_key"), j.text("image_path"),
                j.optString("state"), j.optBoolean("builtIn"), j.optBoolean("update"),
                if (can == null) emptyList() else (0 until can.length()).map { can.optString(it) }.filter { it in ACTIONS })
        }

        /** The actions the server may name, and their buttons' words. */
        val ACTIONS = linkedMapOf("update" to "Update", "install" to "Install", "remove" to "Remove", "enable" to "Enable", "disable" to "Disable")
        /** The toast while one runs ("Installing Spotty…"). */
        fun doing(action: String): String = when (action) {
            "install" -> "Installing"; "update" -> "Updating"; "remove" -> "Removing"; "enable" -> "Enabling"; "disable" -> "Disabling"; else -> action
        }
    }
}

/**
 * The plugin page as the server reads it. [risk]: LMS's third-party warning hasn't been accepted, so it shows no plugin
 * yet; [restart]: a change waits for LMS to restart.
 */
data class PluginList(
    val risk: Boolean, val restart: Boolean, val enabled: List<LmsPlugin>, val disabled: List<LmsPlugin>, val available: List<LmsPlugin>,
    val updates: Int
) {
    /** The Installed tab's groups, updates first among the active ones (they are what needs doing), filtered by [query]. */
    fun installed(query: String): List<Pair<String, List<LmsPlugin>>> {
        val q = query.trim().lowercase()
        return listOf(
            "Active" to enabled.filter { it.matches(q) }.sortedByDescending { it.update },
            "Inactive" to disabled.filter { it.matches(q) }
        ).filter { it.second.isNotEmpty() }
    }

    /** The Available tab: one group per repository, in the server's order, filtered by [query]. */
    fun availableByRepo(query: String): List<Pair<String, List<LmsPlugin>>> {
        val q = query.trim().lowercase()
        return available.filter { it.matches(q) }.groupBy { it.repo ?: "Other" }.toList()
    }

    /** The Installed tab's name: with the number of updates when there are any. */
    fun installedTab(): String = if (updates > 0) "Installed · $updates update${if (updates == 1) "" else "s"}" else "Installed"

    companion object {
        fun parse(j: JSONObject) = PluginList(
            j.optBoolean("risk"), j.optBoolean("restart"),
            j.optJSONArray("enabled").objs().map(LmsPlugin::parse), j.optJSONArray("disabled").objs().map(LmsPlugin::parse),
            j.optJSONArray("available").objs().map(LmsPlugin::parse), j.optJSONArray("updates")?.length() ?: 0)
    }
}

/** LMS's plugin page, read. */
fun ServerClient.plugins(): PluginList = PluginList.parse(getJson("/api/lms/plugins", 30_000))

/** LMS's third-party warning accepted (for a year, as on LMS's page) — only ever on the person's own tap. */
fun ServerClient.acceptPluginRisk(): PluginList = PluginList.parse(post("/api/lms/plugins/accept-risk", JSONObject().put("accept", true), 30_000))

/** One thing done to one plugin, by name; LMS waits for a download before it answers, so this can take half a minute. */
fun ServerClient.changePlugin(name: String, action: String): PluginList =
    PluginList.parse(post("/api/lms/plugins", JSONObject().put("name", name).put("action", action), 60_000))

/** LMS restarted, to apply plugin changes. */
fun ServerClient.restartLms() { post("/api/lms/restart", JSONObject(), 20_000) }
