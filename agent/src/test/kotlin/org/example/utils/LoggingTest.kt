package org.example.utils

import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFalse

class LoggingTest {

    @Test
    fun `HTTP client loggers, which would write the API key, stay below debug`() {
        for (name in listOf(
            "org.apache.hc.client5.http.headers",
            "org.apache.hc.client5.http.wire",
            "io.ktor.client.HttpClient",
        )) {
            assertFalse(LoggerFactory.getLogger(name).isDebugEnabled, "$name logs at debug level")
        }
    }
}
