package dev.ktmnav.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseReleaseTest {

    @Test
    fun picksApkAssetAndBuildNumber() {
        val json = JSONObject(
            """
            {"tag_name":"build-12","name":"Debug build 12","body":"notes",
             "assets":[
               {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
               {"name":"ktm390-nav.apk","browser_download_url":"https://x/app.apk","size":19000000}
             ]}
            """.trimIndent(),
        )
        val r = Updater.parseRelease(json)!!
        assertEquals(12, r.buildNumber)
        assertEquals("https://x/app.apk", r.apkUrl)
        assertEquals(19_000_000L, r.sizeBytes)
    }

    @Test
    fun ignoresUnexpectedTagsOrMissingApk() {
        assertNull(Updater.parseRelease(JSONObject("""{"tag_name":"v1.0","assets":[]}""")))
        assertNull(Updater.parseRelease(JSONObject("""{"tag_name":"build-3","assets":[]}""")))
    }
}
