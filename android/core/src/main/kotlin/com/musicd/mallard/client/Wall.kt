package com.musicd.mallard.client

import org.json.JSONObject

/**
 * The page's UI Settings as a wall of albums is drawn to them (public/app/ui-settings.js, bug list item 51):
 * the grid's layout (Auto, 3 or 2 columns, or a list), the tile size (Auto's columns are the screen's own
 * divided by it) and the text sizes, each a multiplier. The page hands them to the app as JSON when it
 * opens the native Library wall ([ShellRoute.library]); what it doesn't say is the default.
 */
data class WallPrefs(val layout: String = "auto", val tile: Double = 1.0, val text: Double = 1.0, val title: Double = 1.0,
                     /** Home's greeting, its Random Album tile and its Album of the Day (item 236), each shown or not (Settings → Home Screen; item 57). */
                     val greeting: Boolean = true, val random: Boolean = true, val aotd: Boolean = true,
                     /** The side menu's size (UI Settings' Menu size; item 67): its insides zoomed by it, its width not. */
                     val menu: Double = 1.0) {
    /** The albums as rows (UI Settings' List), not tiles. */
    val list: Boolean get() = layout == "list"

    /**
     * How many columns a screen [widthDp] by [heightDp] has: the page's own breakpoints (style.css, .album-grid: 3 on
     * a phone, 5 or 7 on a tablet by its orientation, 9 from 1200 wide), fixed by "3" or "2", else divided by the tile
     * size, one at least.
     */
    fun cols(widthDp: Int, heightDp: Int): Int {
        if (layout == "3" || layout == "2") return layout.toInt()
        val base = when { widthDp >= 1200 -> 9; widthDp >= 768 -> if (heightDp >= widthDp) 5 else 7; else -> 3 }
        return maxOf(1, Math.round(base / tile).toInt())
    }

    companion object {
        private val LAYOUTS = setOf("auto", "3", "2", "list")
        private val TILES = setOf(0.5, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5)
        private val TEXTS = setOf(1.0, 1.1, 1.25, 1.5, 1.75, 2.0)

        /** The page's JSON ({"layout","tile","text","title"}, each as its select's value); null or broken: the defaults. */
        fun parse(json: String?): WallPrefs {
            val j = runCatching { JSONObject(json ?: return WallPrefs()) }.getOrNull() ?: return WallPrefs()
            fun num(key: String, allowed: Set<Double>): Double = j.optString(key, "").toDoubleOrNull()?.takeIf { it in allowed } ?: 1.0
            return WallPrefs(
                layout = j.optString("layout", "auto").takeIf { it in LAYOUTS } ?: "auto",
                tile = num("tile", TILES), text = num("text", TEXTS), title = num("title", TEXTS),
                greeting = j.optString("greeting", "on") != "off", random = j.optString("random", "on") != "off", aotd = j.optString("aotd", "on") != "off", menu = num("menu", TEXTS)
            )
        }
    }
}
