package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LMS's plugins as /api/lms/plugins answers (lib/lmsplugins.js publicList over its LMS-shaped fixture, verbatim). */
class PluginsTest {
    private val answer = """{"risk":false,"restart":true,"enabled":[{"name":"MaterialSkin","title":"Material Skin","version":"5.9.2","desc":"A modern skin","error":null,"creator":"Craig","homepage":null,"settings":"/plugins/MaterialSkin/settings.html","image_path":"/plugins/MaterialSkin/html/images/icon_50x50_p.png","state":"enabled","builtIn":false,"update":true,"can":["update","remove"]},{"name":"FullTextSearch","title":"Full text search","version":"9.0","desc":"Search everything","error":null,"creator":"","homepage":null,"settings":null,"state":"enabled","builtIn":true,"update":false,"can":["disable"]}],"disabled":[{"name":"Podcast","title":"Podcasts","version":"9.0","desc":"Shows","error":null,"creator":"","homepage":null,"settings":null,"state":"disabled","builtIn":true,"update":false,"can":["enable"]}],"available":[{"name":"Spotty","title":"Spotty Spotify","version":"4.10.1","desc":"Spotify for LMS","error":null,"creator":"Michael Herger","homepage":null,"settings":null,"repo":"LMS Community plugins","image_key":"url-aHR0cHM6Ly9leGFtcGxlLm9yZy9zLnBuZw","state":"available","builtIn":false,"update":false,"can":["install"]}],"updates":["MaterialSkin"]}"""
    private val list = PluginList.parse(JSONObject(answer))

    @Test fun `the page's state`() {
        assertFalse(list.risk)
        assertTrue(list.restart)
        assertEquals(1, list.updates)
        assertEquals("Installed · 1 update", list.installedTab())
        assertEquals("Installed", list.copy(updates = 0).installedTab())
        assertEquals("Installed · 2 updates", list.copy(updates = 2).installedTab())
    }

    @Test fun `each plugin, and what the server says it can do`() {
        val m = list.enabled[0]
        assertEquals(listOf("update", "remove"), m.can)
        assertEquals("/plugins/MaterialSkin/settings.html", m.settings)
        assertEquals("/plugins/MaterialSkin/html/images/icon_50x50_p.png", m.imagePath)
        assertNull(m.imageKey)
        assertNull(m.homepage)
        assertNull(m.error)
        assertTrue(list.enabled[1].builtIn)
        assertEquals(listOf("enable"), list.disabled[0].can)
        val s = list.available[0]
        assertEquals("LMS Community plugins", s.repo)
        assertEquals("url-aHR0cHM6Ly9leGFtcGxlLm9yZy9zLnBuZw", s.imageKey)
        assertEquals(listOf("install"), s.can)
    }

    @Test fun `an action the app has no button for is dropped, not drawn`() {
        val p = LmsPlugin.parse(JSONObject("""{"name":"X","can":["install","explode"]}"""))
        assertEquals(listOf("install"), p.can)
        assertEquals("X", p.title)
    }

    @Test fun `the tabs' groups, updates first, and the filter`() {
        val g = list.installed("")
        assertEquals(listOf("Active", "Inactive"), g.map { it.first })
        assertEquals("MaterialSkin", g[0].second[0].name)
        val fixed = list.copy(enabled = list.enabled.reversed())
        assertEquals("MaterialSkin", fixed.installed("")[0].second[0].name)
        assertEquals(listOf("Inactive"), list.installed(" SHOWS ").map { it.first })
        assertEquals(listOf("LMS Community plugins"), list.availableByRepo("").map { it.first })
        assertTrue(list.availableByRepo("nothing like it").isEmpty())
        assertEquals(1, list.availableByRepo("herger")[0].second.size)
    }

    @Test fun `the words while it works`() {
        assertEquals("Installing", LmsPlugin.doing("install"))
        assertEquals("Disabling", LmsPlugin.doing("disable"))
    }

    @Test fun `repositories are read, and an address is held to LMS's rule`() {
        val l = PluginList.parse(JSONObject("""{"repos":["https://dev.example/plugins.xml",""],"enabled":[],"disabled":[],"available":[]}"""))
        assertEquals(listOf("https://dev.example/plugins.xml"), l.repos)
        assertTrue(PluginRepos.valid(" https://dev.example/plugins.xml "))
        assertTrue(PluginRepos.valid("http://192.168.0.2/repo.xml?x=1"))
        assertTrue(!PluginRepos.valid("ftp://dev.example/plugins.xml"))
        assertTrue(!PluginRepos.valid("https://dev.example/plugins"))
        assertEquals("https://dev.example/repo.xml", PluginRepos.trim(" https://dev.example/repo.xml/ "))
    }
}
