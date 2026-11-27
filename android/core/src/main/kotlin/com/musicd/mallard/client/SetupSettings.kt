package com.musicd.mallard.client

import org.json.JSONObject

/*
 * Settings' small lists and words, typed: what each list offers and what is said once it is saved. What is kept on the
 * server is asked for and saved there; what is this phone's (the text and tile sizes) is kept by the app ([WallPrefs]).
 */

/** One of a list's choices: its value, and the words it shows. */
data class Choice(val value: String, val words: String) {
    companion object {
        /** What a list shows: the words of the choice made, else of its first; nothing while the list is empty. */
        fun words(choices: List<Choice>, value: String): String = choices.firstOrNull { it.value == value }?.words ?: choices.firstOrNull()?.words.orEmpty()
    }
}

object SetupSettings {
    /** The text sizes a phone is offered (+75% and +100% are a desktop's). */
    val TEXT = listOf(Choice("1", "Normal"), Choice("1.1", "+10%"), Choice("1.25", "+25%"), Choice("1.5", "+50%"))
    val LAYOUT = listOf(Choice("auto", "Auto"), Choice("3", "3 columns"), Choice("2", "2 columns"), Choice("list", "List"))
    val TILE = listOf(Choice("0.5", "−50%"), Choice("0.75", "−25%"), Choice("0.9", "−10%"), Choice("1", "Normal"), Choice("1.1", "+10%"), Choice("1.25", "+25%"), Choice("1.5", "+50%"))
    /** The hours the day's picks can be built at: 00:00 to 23:00. */
    val HOURS = (0 until 24).map { Choice(it.toString(), (if (it < 10) "0$it" else "$it") + ":00") }

    /** A multiplier as the page's list has it: 1.0 is "1", 1.25 is "1.25". */
    fun num(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    /** What [prefs] says for one of UI Settings' lists ([key]: "text", "title", "menu", "layout", "tile"). */
    fun uiValue(prefs: WallPrefs, key: String): String = when (key) {
        "text" -> num(prefs.text); "title" -> num(prefs.title); "menu" -> num(prefs.menu); "layout" -> prefs.layout; else -> num(prefs.tile)
    }

    /** The page's prefs as JSON ([json]: as it last handed them, or none) with [name] now [value], for the native screens to draw to. */
    fun prefsWith(json: String?, name: String, value: String): String =
        (runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: JSONObject()).put(name, value).toString()

    /** What Smart Picks' switch says once saved. */
    fun picksSaid(on: Boolean): String = if (on) "Smart Picks on — the first set builds at the scheduled hour" else "Smart Picks off — nothing runs in the background"
    fun hourSaid(hour: Int): String = "Smart Picks will build at " + (if (hour < 10) "0$hour" else "$hour") + ":00"
}
