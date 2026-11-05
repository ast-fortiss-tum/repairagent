package org.example

import kotlinx.coroutines.runBlocking
import org.example.config.Config
import org.example.utils.AgentUtils
import org.example.utils.EvaluationManager
import org.example.utils.Logger
import org.example.utils.LlmAccessException
import org.example.utils.fatalGatewayError
import java.nio.file.Path
import java.util.Locale

internal data class RunContext(
    val config: Config,
    val agentParent: Path,
    val runLogDir: Path,
    val evaluationManager: EvaluationManager,
    val agentUtils: AgentUtils,
    val modelId: String,
    val logger: Logger
)

internal data class AttemptCost(val delta: Double, val total: Double)

internal fun logCost(
    agentUtils: AgentUtils,
    logger: Logger,
    previousPrice: Double,
    caseNumber: Int,
    attempt: Int
): AttemptCost = runBlocking {
    val currentPrice = agentUtils.getSpendDollars()
    val priceDelta = currentPrice - previousPrice
    logger.log("PRICE: ${formatUsd(priceDelta)}")
    logger.log("Price delta: ${formatUsd(priceDelta)} USD; total spend: ${formatUsd(currentPrice)} USD")
    logger.status(
        "cost",
        "case=$caseNumber attempt=$attempt delta=${formatUsd(priceDelta)} USD total=${formatUsd(currentPrice)} USD"
    )
    AttemptCost(priceDelta, currentPrice)
}

internal fun attemptLogPath(
    runLogDir: Path,
    caseId: Int,
    attempt: Int,
    testName: String
): Path {
    val casePart = (caseId + 1).toString().padStart(4, '0')
    return runLogDir.resolve("case_${casePart}_attempt_${attempt}_${sanitizeLogPart(testName)}.log")
}

internal fun sanitizeLogPart(value: String, maxLength: Int = 120): String {
    val sanitized = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }
    return sanitized.take(maxLength)
}

internal fun compactMessage(message: String, maxLength: Int = 180): String {
    val singleLine = message.replace(Regex("\\s+"), " ").trim()
    return if (singleLine.length <= maxLength) singleLine else "${singleLine.take(maxLength)}..."
}

internal fun relativeTo(root: Path, path: Path): String =
    runCatching { root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString() }
        .getOrDefault(path.toString())

private fun formatUsd(value: Double): String = String.format(Locale.US, "%.6f", value)

internal fun stopRunOnFatalGatewayError(
    e: Exception,
    logger: Logger,
    caseId: Int,
    attempt: Int,
    unfinishedIds: List<Int>
) {
    val fatal = fatalGatewayError(e) ?: return
    val message = "the provider refused the request (${fatal::class.simpleName}) at case id=$caseId " +
            "attempt=$attempt. This attempt was not saved and the run stops here. Fix the API key " +
            "or the quota, then re-run the unfinished cases with CASE_IDS=${formatIdRanges(unfinishedIds)}"
    logger.error(message, e)
    logger.clearLogFile()
    logger.status("aborted", message)
    throw LlmAccessException(message, e)
}

internal fun formatIdRanges(ids: List<Int>): String {
    val sorted = ids.distinct().sorted()
    val parts = mutableListOf<String>()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        parts += if (j == i) "${sorted[i]}" else "${sorted[i]}-${sorted[j]}"
        i = j + 1
    }
    return parts.joinToString(",")
}
