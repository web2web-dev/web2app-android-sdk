package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Атом L-2 — парс ответа `GET /public/handoff/resolve?code=<token>`.
 *
 * Живой прод отдаёт guid ВНУТРИ обёртки `{"success":true,"data":{...}}`, а старый парсер
 * читал `guid` с верхнего уровня — из-за этого identify() всегда падал в failure.
 * Парсер regex-овый (не org.json): в JVM-юнитах Android `JSONObject` — заглушка,
 * возвращающая пустые значения, тест на ней был бы бессмысленным (ловушка L-4).
 */
class AttributionParseTest {

    @Test
    fun parsesGuidFromWrappedProdResponse() {
        // Дословное тело живого прод-ответа (api.testfunnelsdev.click, 2026-08-12).
        val body =
            """{"success":true,"data":{"guid":"sdk-probe-guid-2026-08-12","projectId":"b6bb21dd-078a-47f7-98ac-75b0e23c76b5"}}"""
        assertEquals("sdk-probe-guid-2026-08-12", AttributionResolver.parseGuidResponse(body))
    }

    @Test
    fun parsesGuidFromFlatBody() {
        // Совместимость с плоским телом (другие/старые окружения).
        assertEquals("g-123", AttributionResolver.parseGuidResponse("""{"guid":"g-123"}"""))
    }

    @Test
    fun returnsNullOnNotFoundErrorBody() {
        // Унифицированный негатив бэка: не найден / просрочен / уже использован.
        val body = """{"message":"Handoff code not found","error":"Not Found","statusCode":404}"""
        assertNull(AttributionResolver.parseGuidResponse(body))
    }

    @Test
    fun returnsNullOnGarbageEmptyAndNull() {
        assertNull(AttributionResolver.parseGuidResponse(null))
        assertNull(AttributionResolver.parseGuidResponse(""))
        assertNull(AttributionResolver.parseGuidResponse("<html>502 Bad Gateway</html>"))
        assertNull(AttributionResolver.parseGuidResponse("{"))
    }

    @Test
    fun returnsNullOnEmptyGuidValue() {
        assertNull(AttributionResolver.parseGuidResponse("""{"success":true,"data":{"guid":""}}"""))
    }

    @Test
    fun doesNotConfuseGuidWithOtherFields() {
        // Ключи, лишь содержащие "guid" как подстроку, парсер брать не должен.
        val body = """{"success":true,"data":{"deviceGuid":"wrong-1","guid_hash":"wrong-2"}}"""
        assertNull(AttributionResolver.parseGuidResponse(body))
    }

    @Test
    fun handlesEscapedCharactersLikeReferenceParser() {
        // Экранирование разбирается так же, как в WebPaywallLauncher.parsePaywallUrlResponse.
        val body = """{"success":true,"data":{"guid":"a\/b\"c"}}"""
        assertEquals("a/b\"c", AttributionResolver.parseGuidResponse(body))
    }
}
