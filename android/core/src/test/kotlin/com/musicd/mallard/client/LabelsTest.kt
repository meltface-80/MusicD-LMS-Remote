package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Labels screen's list and merge, as /api/filters/labels answers and the web app says it. */
class LabelsTest {
    private val list = LabelList.parse(JSONObject("""{"labels":[
        {"key":"warp","title":"Warp Records","subtitle":"12 albums","albumCount":12,"image_key":null,"logo_url":"/api/labels/logo-image/w.png","mergedFrom":[{"key":"warp-uk","display":"Warp UK"}]},
        {"key":"sub","title":"Sub Pop","subtitle":"1 album","albumCount":1,"logo_url":null,"mergedFrom":[]}],"scanning":false,"progress":1,"count":2}"""))

    @Test fun `the list, its logos and merges, and the minimum`() {
        assertEquals(2, list.labels.size)
        assertEquals("/api/labels/logo-image/w.png", list.labels[0].logoUrl)
        assertEquals(listOf("warp-uk" to "Warp UK"), list.labels[0].mergedFrom)
        assertEquals(listOf("Warp Records"), list.atLeast(2).map { it.title })
    }

    @Test fun `what an empty list says`() {
        assertEquals("Scanning for record labels… 40% complete.", LabelList(emptyList(), true, 0.4).empty())
        assertEquals("Building library index…", LabelList(emptyList(), true, 0.0).empty())
    }

    @Test fun `the merge bar and what Merge sends`() {
        assertEquals("Tap labels to select", LabelMerge.said(emptyList()))
        assertEquals("Warp Records — select more to merge", LabelMerge.said(list.labels.take(1)))
        assertEquals("Merge 2 into Warp Records", LabelMerge.said(list.labels))
        val items = LabelMerge.body(list.labels).getJSONArray("items")
        assertEquals(listOf("warp" to "Warp Records", "sub" to "Sub Pop"), (0 until items.length()).map { items.getJSONObject(it).let { o -> o.getString("key") to o.getString("display") } })
    }
}
