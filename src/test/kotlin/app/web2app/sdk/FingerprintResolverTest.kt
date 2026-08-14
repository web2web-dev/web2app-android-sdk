package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEB-1213 — JVM-чистое ядро опознания по отпечатку устройства (паритет iOS
 * 0.7.0): парс ответа `POST /public/handoff/resolve-by-fingerprint`,
 * нормализация экрана в логические dp «min x max» и сборка тела запроса.
 *
 * Парсер — regex по фиксированному контракту (не org.json: тот в JVM-юнитах
 * Android заглушка, ловушка L-4) — паттерн [AttributionResolver.parseGuidResponse].
 */
class FingerprintResolverTest {

    // ---- parseResolveResponse ----

    @Test
    fun parsesGuidAndMatchMethodFromWrappedResponse() {
        val body =
            """{"success":true,"data":{"guid":"fp-guid-1","matchMethod":"exact"}}"""
        val match = FingerprintResolver.parseResolveResponse(body)
        assertEquals("fp-guid-1", match?.guid)
        assertEquals("exact", match?.matchMethod)
    }

    @Test
    fun parsesGuidWithoutMatchMethod() {
        val match = FingerprintResolver.parseResolveResponse(
            """{"success":true,"data":{"guid":"fp-guid-2"}}""",
        )
        assertEquals("fp-guid-2", match?.guid)
        assertNull(match?.matchMethod)
    }

    @Test
    fun returnsNullOnUnifiedNotFoundBody() {
        // Унифицированный негатив бэка: нет совпадения / неоднозначно / протухло /
        // выключено у проекта — все причины приходят одинаковым 404.
        assertNull(
            FingerprintResolver.parseResolveResponse(
                """{"message":"Not Found","error":"Not Found","statusCode":404}""",
            ),
        )
    }

    @Test
    fun returnsNullOnGarbageEmptyAndNull() {
        assertNull(FingerprintResolver.parseResolveResponse(null))
        assertNull(FingerprintResolver.parseResolveResponse(""))
        assertNull(FingerprintResolver.parseResolveResponse("<html>502 Bad Gateway</html>"))
        assertNull(FingerprintResolver.parseResolveResponse("{"))
    }

    @Test
    fun returnsNullOnEmptyGuidValue() {
        assertNull(
            FingerprintResolver.parseResolveResponse(
                """{"success":true,"data":{"guid":"","matchMethod":"exact"}}""",
            ),
        )
    }

    @Test
    fun doesNotConfuseGuidWithOtherFields() {
        assertNull(
            FingerprintResolver.parseResolveResponse(
                """{"success":true,"data":{"deviceGuid":"wrong-1","guid_hash":"wrong-2"}}""",
            ),
        )
    }

    @Test
    fun handlesEscapedCharactersLikeReferenceParser() {
        val match = FingerprintResolver.parseResolveResponse(
            """{"success":true,"data":{"guid":"a\/b\"c","matchMethod":"screen\/tz"}}""",
        )
        assertEquals("a/b\"c", match?.guid)
        assertEquals("screen/tz", match?.matchMethod)
    }

    // ---- normalizedScreen ----

    @Test
    fun normalizesPixelsToLogicalDp() {
        // Pixel-класс: 1080x2400 px при density 3.0 → 360x800 dp.
        assertEquals("360x800", FingerprintResolver.normalizedScreen(1080, 2400, 3f))
    }

    @Test
    fun landscapeAndPortraitGiveTheSameScreen() {
        // Ориентация не должна рвать матч: всегда «min x max».
        assertEquals(
            FingerprintResolver.normalizedScreen(1080, 2400, 3f),
            FingerprintResolver.normalizedScreen(2400, 1080, 3f),
        )
    }

    @Test
    fun roundsFractionalDpToNearestInt() {
        // density 2.625 (Pixel 5): 1080/2.625=411.43→411, 2400/2.625=914.29→914.
        assertEquals("411x914", FingerprintResolver.normalizedScreen(1080, 2400, 2.625f))
    }

    @Test
    fun zeroOrNegativeDensityFallsBackToRawPixels() {
        // Битые метрики (заглушки/экзотика) — не делим на ноль, отдаём px.
        assertEquals("1080x2400", FingerprintResolver.normalizedScreen(1080, 2400, 0f))
        assertEquals("1080x2400", FingerprintResolver.normalizedScreen(2400, 1080, -1f))
    }

    @Test
    fun squareScreenKeepsBothSides() {
        assertEquals("400x400", FingerprintResolver.normalizedScreen(1200, 1200, 3f))
    }

    // ---- requestBody ----

    @Test
    fun requestBodyMatchesBackendContract() {
        val signals = FingerprintResolver.Signals(
            platform = "android",
            osVersion = "14",
            deviceModel = "Pixel 8",
            screen = "360x800",
            timezone = "Europe/Belgrade",
            language = "en-US",
        )
        assertEquals(
            """{"projectId":"proj-1","platform":"android","osVersion":"14",""" +
                """"deviceModel":"Pixel 8","screen":"360x800",""" +
                """"timezone":"Europe/Belgrade","language":"en-US"}""",
            FingerprintResolver.requestBody("proj-1", signals),
        )
    }

    @Test
    fun requestBodyOmitsNullOptionalFields() {
        val signals = FingerprintResolver.Signals(
            platform = "android",
            osVersion = "14",
            deviceModel = null,
            screen = "360x800",
            timezone = "UTC",
            language = null,
        )
        val body = FingerprintResolver.requestBody("p", signals)
        assertEquals(
            """{"projectId":"p","platform":"android","osVersion":"14","screen":"360x800","timezone":"UTC"}""",
            body,
        )
        assertFalse(body.contains("deviceModel"))
        assertFalse(body.contains("language"))
    }

    @Test
    fun requestBodyEscapesValues() {
        val signals = FingerprintResolver.Signals(
            platform = "android",
            osVersion = "14",
            deviceModel = "Weird \"Model\"",
            screen = "360x800",
            timezone = "UTC",
            language = null,
        )
        val body = FingerprintResolver.requestBody("p", signals)
        assertTrue(body.contains(""""deviceModel":"Weird \"Model\""""))
    }
}
