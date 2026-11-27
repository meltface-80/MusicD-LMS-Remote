package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings as the server answers and the page says it (item 76): the version, the account's devices, the API keys. */
class SettingsTest {
    @Test fun `the version at the list's foot`() {
        assertEquals("Mandarin v1.0 (Build 17)", SettingsLists.version(JSONObject("""{"current":"1.0.17"}""")))
        assertEquals("Mandarin v2", SettingsLists.version(JSONObject("""{"current":"2"}""")))
        assertNull(SettingsLists.version(JSONObject("{}")))
        assertNull(SettingsLists.version(null))
    }

    @Test fun `the devices signed in, each said as the page says it`() {
        val now = 10_000_000_000L
        val a = AccountDevices.parse(JSONObject("""{"username":"lewis","devices":[
            {"id":"1","name":"Pixel","kind":"android","current":true,"last_seen":$now},
            {"id":"2","name":"Firefox","kind":"browser","current":false,"last_seen":${now - 3 * 3600_000}}]}"""))
        assertEquals("Signed in as lewis", a.who)
        assertEquals("Android app · this device", a.devices[0].sub(now))
        assertEquals("Browser · 3 hours ago", a.devices[1].sub(now))
        assertEquals("active now", SignedInDevice.ago(now - 60_000, now))
        assertEquals("12 min ago", SignedInDevice.ago(now - 12 * 60_000, now))
        assertEquals("1 hour ago", SignedInDevice.ago(now - 60 * 60_000, now))
        assertEquals("3 days ago", SignedInDevice.ago(now - 72 * 3600_000, now))
        assertEquals("Signed in as ?", AccountDevices.parse(JSONObject("""{"username":null}""")).who)
    }

    @Test fun `a new password is tried only once it can be`() {
        assertEquals("Enter your current password.", AccountDevices.passwordProblem("", "longenough", "longenough"))
        assertEquals("Use at least 8 characters for the new password.", AccountDevices.passwordProblem("old", "short", "short"))
        assertEquals("The two new passwords don't match.", AccountDevices.passwordProblem("old", "longenough", "longenougH"))
        assertNull(AccountDevices.passwordProblem("old", "longenough", "longenough"))
    }

    @Test fun `the lists in the page's order, and Settings' address`() {
        assertEquals(listOf("account", "folders", "streaming", "playback", "identify", "setup", "backup", "sqtool", "system"), SettingsLists.HOME.map { it.pane })
        assertEquals("Downloads", SettingsLists.SETUP[5].title)
        assertEquals("no Share Card page (item 336)", false, SettingsLists.SETUP.any { it.pane == "sharecard" })
        assertEquals("no API Keys page (item 494)", "Away from home", SettingsLists.SETUP.last().title)
        assertEquals("settings/home", ShellRoute.settings())
        assertEquals("settings/metadata", ShellRoute.settings("metadata"))
    }
}
