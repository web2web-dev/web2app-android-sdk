package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 0.7.2 — версия SDK живёт в двух местах: [SdkLogger.SDK_VERSION] (уезжает в каждую
 * пачку журнала) и `version` публикации в build.gradle.kts (её видит JitPack).
 * В 0.7.1 они разъехались (журнал называл себя 0.7.0) — сторож держит их вместе.
 * Юниты Gradle исполняются из каталога модуля, файл читается относительно него.
 */
class SdkVersionParityTest {

    @Test
    fun loggerVersionMatchesPublishedVersion() {
        val gradle = File("build.gradle.kts").readText()
        val published = Regex("""version\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
        assertNotNull("в build.gradle.kts не найдена version публикации", published)
        assertEquals(published, SdkLogger.SDK_VERSION)
    }
}
