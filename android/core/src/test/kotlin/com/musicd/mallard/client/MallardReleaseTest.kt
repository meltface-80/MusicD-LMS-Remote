package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MallardReleaseTest {
    private val sha = "ab".repeat(32)
    private fun manifest(version: String = "0.2.0-t31", code: Int = 2031, url: String = MallardRelease.DOWNLOADS + "mallard-test/mallard-test.apk", hash: String = sha) =
        JSONObject().put("version", version).put("code", code).put("url", url).put("sha256", hash).put("notes", "- Added - Share card")

    @Test fun `a manifest as CI writes it`() {
        val r = MallardRelease.parse(manifest())!!
        assertEquals("0.2.0-t31", r.version)
        assertEquals(2031, r.code)
        assertEquals(sha, r.sha256)
        assertEquals("- Added - Share card", r.notes)
    }

    @Test fun `an APK anywhere but this repository's Mallard releases is refused`() {
        assertNull("another host", MallardRelease.parse(manifest(url = "https://example.com/mallard.apk")))
        assertNull("the server's own release", MallardRelease.parse(manifest(url = MallardRelease.DOWNLOADS + "v1.0.86/x.apk")))
        assertNull("plain http", MallardRelease.parse(manifest(url = MallardRelease.DOWNLOADS.replace("https", "http") + "mallard-test/a.apk")))
    }

    @Test fun `no name, no code or no real hash, no release`() {
        assertNull(MallardRelease.parse(manifest(version = "")))
        assertNull(MallardRelease.parse(manifest(code = 0)))
        assertNull(MallardRelease.parse(manifest(hash = "")))
        assertNull(MallardRelease.parse(manifest(hash = "abc")))
        assertNull(MallardRelease.parse(null))
        assertEquals("a hash in capitals is the same hash", sha, MallardRelease.parse(manifest(hash = sha.uppercase()))!!.sha256)
    }

    @Test fun `newer is a higher versionCode, as Android judges it`() {
        val t31 = MallardRelease.parse(manifest())!!
        assertTrue(t31.newerThan(2030))
        assertFalse(t31.newerThan(2031))
        assertFalse("the release (x999) is never offered a test build of its own version", t31.newerThan(2999))
        val release = MallardRelease.parse(manifest("0.2.0", 2999, MallardRelease.DOWNLOADS + "mallard-latest/mallard.apk"))!!
        assertTrue("the release is offered over every test build before it", release.newerThan(2031))
        assertEquals(release, MallardRelease.newest(listOf(t31, null, release)))
        assertNull(MallardRelease.newest(listOf(null, null)))
    }

    @Test fun `a release build follows Mallard's releases, a test build those and the test builds, never the server's`() {
        assertEquals(listOf("mallard-latest"), MallardRelease.tagsFor("0.2.0"))
        assertEquals(listOf("mallard-test", "mallard-latest"), MallardRelease.tagsFor("0.2.0-t31"))
        assertFalse(MallardRelease.isTest("0.2.0-rc1"))
        assertTrue(MallardRelease.tagsFor("0.2.0-t5").none { it.startsWith("v") || it == "latest" })
        assertEquals("https://github.com/meltface-80/MusicD-LMS-Remote/releases/download/mallard-test/mallard.json", MallardRelease.manifestUrl("mallard-test"))
    }

    @Test fun `the banner says what is on offer and what is installed`() {
        assertEquals("Mallard v0.2.0-t31 is available (you have v0.2.0-t25)", MallardRelease.offer(MallardRelease.parse(manifest())!!, "0.2.0-t25"))
    }
}
