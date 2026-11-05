package org.example.utils

import kotlinx.coroutines.runBlocking

class LlmAccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private val fatalProviderErrors = listOf(
    Regex("""\b401\b"""),
    Regex("""\b403\b"""),
    Regex("""(?i)\bunauthori[sz]ed\b"""),
    Regex("""(?i)invalid[_ ]api[_ ]key"""),
    Regex("""(?i)incorrect api key"""),
    Regex("""(?i)insufficient_quota"""),
    Regex("""(?i)permission[_ ]denied"""),
    Regex("""(?i)credit balance is too low"""),
)

fun fatalGatewayError(e: Throwable): Throwable? =
    generateSequence(e) { it.cause }
        .take(32)
        .firstOrNull { cause ->
            val text = cause.message ?: return@firstOrNull false
            fatalProviderErrors.any { it.containsMatchIn(text) }
        }

fun verifyLlmAccess(agentUtils: AgentUtils, logger: Logger) {
    try {
        runBlocking { agentUtils.ping() }
    } catch (e: Exception) {
        val fatal = fatalGatewayError(e) ?: throw e
        throw LlmAccessException(
            "The provider refused the request for model ${agentUtils.getLLModels().id}: " +
                    "${fatal.message?.take(200)}. Check ${agentUtils.apiKeyVariable()} and the account's quota.",
            e
        )
    }
    logger.status("llm", "provider reachable, model=${agentUtils.getLLModels().id}")
}
