package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plugin menus as lib/apps.js answers them (its fake-LMS run's own output). */
class AppsTest {
    @Test fun `the list, apps and radio`() {
        val l = AppList.parse(JSONObject("""{"apps":[{"tag":"tidal","kind":"app","name":"TIDAL","image_key":null,"image_path":"/plugins/TIDAL/html/icon.png","go":"PFr"},
            {"tag":"x","kind":"app","name":"No root","go":""}],"radio":[{"tag":"tunein","kind":"radio","name":"TuneIn","image_key":null,"image_path":null,"go":"R24"}]}"""))
        assertEquals(listOf("tidal"), l.apps.map { it.tag })
        assertEquals("/plugins/TIDAL/html/icon.png", l.apps[0].imagePath)
        assertNull(l.apps[0].imageKey)
        assertEquals("TuneIn", l.radio[0].name)
    }

    @Test fun `a menu's rows and what a tap does`() {
        val m = AppMenu.parse(JSONObject("""{"title":"TIDAL","total":4,"offset":0,"layout":"list","notice":null,"rows":[
            {"kind":"search","title":"Search","subtitle":null,"search":"s1","prompt":"Search TIDAL"},
            {"kind":"node","title":"Mixes","subtitle":null,"go":"g1"},
            {"kind":"playable","title":"Kid A","subtitle":"Radiohead","image_key":"url-aHR0","url":"tidal://album:123","play":"p","add":"a","more":"m","tap_plays":false,"go":"g2"},
            {"kind":"playable","title":"Idioteque","subtitle":null,"play":"p2","tap_plays":true}]}"""))
        assertEquals(listOf("search", "open", "open", "play"), m.rows.map { it.tap })
        assertEquals("Search TIDAL", m.rows[0].prompt)
        assertEquals("tidal://album:123", m.rows[2].url)
        assertTrue(m.rows[2].hasPicture)
        val login = AppMenu.parse(JSONObject("""{"title":"","total":0,"offset":0,"layout":"list","notice":"Please sign in to TuneIn.","rows":[]}"""))
        assertEquals("Please sign in to TuneIn.", login.notice)
    }

    @Test fun `an action's answer`() {
        val done = AppAct.parse(JSONObject("""{"ok":true,"next_window":"nowPlaying","message":null}"""))
        assertEquals("nowPlaying", done.nextWindow); assertNull(done.menu); assertNull(done.message)
        val ctx = AppAct.parse(JSONObject("""{"menu":{"title":"","total":1,"rows":[{"kind":"node","title":"Artist","go":"x"}]}}"""))
        assertEquals("Artist", ctx.menu!!.rows[0].title)
    }

    @Test fun `routes carry the token and the term`() {
        assertEquals("appmenu?token=a%2Fb&title=My%20Mixes&term=&zone=aa%3Abb", ShellRoute.appMenu("a/b", "My Mixes", "aa:bb"))
        assertEquals("apps?kind=radio&zone=z", ShellRoute.apps("radio", "z"))
    }
}
