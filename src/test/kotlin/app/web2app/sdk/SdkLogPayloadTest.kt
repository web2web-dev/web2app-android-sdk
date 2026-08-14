package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEB-1209 — сборка тел удалённого журнала SDK (`POST /public/sdk-logs`).
 *
 * Контракт тела — прод-бэк `SdkLogBatchDto`, менять нельзя:
 * `{"projectId","platform":"android","sdkVersion","guid"?,"device":{...},"entries":[...]}`.
 * Сборка — чистая JVM-строка (не org.json: тот в JVM-юнитах Android заглушка,
 * ловушка L-4), поэтому проверяем дословно.
 */
class SdkLogPayloadTest {

    // ---- entryJson ----

    @Test
    fun entryWithoutMessageAndContextHasOnlyRequiredFields() {
        assertEquals(
            """{"ts":1700000000000,"level":"info","event":"configure"}""",
            SdkLogPayload.entryJson(1_700_000_000_000, "info", "configure", "", emptyMap()),
        )
    }

    @Test
    fun entryIncludesMessageAndContextWhenPresent() {
        assertEquals(
            """{"ts":1,"level":"warn","event":"identify.referrer_empty","message":"нет токена","context":{"http":"404"}}""",
            SdkLogPayload.entryJson(
                1, "warn", "identify.referrer_empty", "нет токена", mapOf("http" to "404"),
            ),
        )
    }

    @Test
    fun entryTruncatesEventTo128AndMessageTo2000() {
        val entry = SdkLogPayload.entryJson(1, "info", "e".repeat(300), "m".repeat(3000), emptyMap())
        assertTrue(entry.contains("\"event\":\"" + "e".repeat(128) + "\""))
        assertFalse(entry.contains("e".repeat(129)))
        assertTrue(entry.contains("\"message\":\"" + "m".repeat(2000) + "\""))
        assertFalse(entry.contains("m".repeat(2001)))
    }

    @Test
    fun entryEscapesQuotesBackslashesAndNewlines() {
        val entry = SdkLogPayload.entryJson(1, "error", "resolve.failed", "a\"b\\c\nd", emptyMap())
        assertEquals(
            "{\"ts\":1,\"level\":\"error\",\"event\":\"resolve.failed\",\"message\":\"a\\\"b\\\\c\\nd\"}",
            entry,
        )
    }

    // ---- batchJson ----

    @Test
    fun batchMatchesServerContractWithGuid() {
        val entry = SdkLogPayload.entryJson(5, "info", "configure", "", emptyMap())
        val batch = SdkLogPayload.batchJson(
            projectId = "proj-1",
            sdkVersion = "0.7.0",
            guid = "guid-1",
            device = linkedMapOf(
                "model" to "Pixel 8",
                "os" to "Android 14",
                "appVersion" to "1.2.3",
                "bundleId" to "com.example.app",
            ),
            entries = listOf(entry),
        )
        assertEquals(
            """{"projectId":"proj-1","platform":"android","sdkVersion":"0.7.0","guid":"guid-1",""" +
                """"device":{"model":"Pixel 8","os":"Android 14","appVersion":"1.2.3","bundleId":"com.example.app"},""" +
                """"entries":[{"ts":5,"level":"info","event":"configure"}]}""",
            batch,
        )
    }

    @Test
    fun batchOmitsGuidWhenNotResolvedYet() {
        val batch = SdkLogPayload.batchJson("p", "0.7.0", null, emptyMap(), emptyList())
        assertEquals(
            """{"projectId":"p","platform":"android","sdkVersion":"0.7.0","device":{},"entries":[]}""",
            batch,
        )
        assertFalse(batch.contains("guid"))
    }

    @Test
    fun batchJoinsMultipleEntriesInOrder() {
        val e1 = SdkLogPayload.entryJson(1, "info", "a", "", emptyMap())
        val e2 = SdkLogPayload.entryJson(2, "info", "b", "", emptyMap())
        val batch = SdkLogPayload.batchJson("p", "0.7.0", null, emptyMap(), listOf(e1, e2))
        assertTrue(batch.contains(""""entries":[$e1,$e2]}"""))
    }

    // ---- objectJson / escape ----

    @Test
    fun objectJsonEscapesKeysAndValues() {
        assertEquals(
            "{\"a\\\"b\":\"c\\\\d\"}",
            SdkLogPayload.objectJson(mapOf("a\"b" to "c\\d")),
        )
    }

    @Test
    fun escapeHandlesTabsCarriageReturnsAndControlChars() {
        assertEquals("a\\tb\\rc", SdkLogPayload.escape("a\tb\rc"))
        assertEquals("x\\u0001y", SdkLogPayload.escape("x\u0001y"))
    }
}
