package org.example

import kotlinx.coroutines.runBlocking
import org.example.utils.DatasetManager
import org.example.utils.ProjectManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

internal fun runTestRepairMode(context: RunContext) {
    val config = context.config
    val agentParent = context.agentParent
    val evaluationManager = context.evaluationManager
    val agentUtils = context.agentUtils
    val logger = context.logger
    val maxAttempts = config.app.maxAttempts
    val maxTests = config.app.maxTests
    val projectLink = config.project.projectLink
    val projectName = config.project.projectName
    val agentDirectory = agentParent.resolve(projectName)

    val datasetManager = DatasetManager(projectLink, projectName)
    val projectManager = ProjectManager(
        datasetManager.projectName,
        datasetManager.projectLink,
        agentDirectory.toString(),
        agentParent.toString(),
        config
    )
    val totalCases = datasetManager.getNumberOfCases()

    val allowlist = config.app.caseIds
    val outOfRange = allowlist?.filter { it !in 0 until totalCases }.orEmpty()
    require(outOfRange.isEmpty()) {
        "CASE_IDS out of range for $projectName (0..${totalCases - 1}): $outOfRange"
    }
    val caseIdsToRun = allowlist ?: (0 until minOf(totalCases, maxTests)).toList()
    val testsToRun = caseIdsToRun.size

    logger.status(
        "plan",
        "cases=$testsToRun/$totalCases attempts=$maxAttempts project=$projectName" +
                if (allowlist != null) " ids=${compactIds(caseIdsToRun)}" else ""
    )

    val rejectionLog = context.runLogDir.resolve("rejected_attempts.tsv")
    Files.writeString(rejectionLog, "case_id\tattempt\tkind\ttest\tmessage\n")
    val maxRetries = config.app.maxAttemptRetries
    var rejectedAttempts = 0
    var erroredAttempts = 0
    var erroredOutAttempts = 0

    var agent = Agent(datasetManager, projectManager, evaluationManager, logger, agentUtils, agentDirectory, config)

    var previousPrice = runBlocking { agentUtils.getSpendDollars() }
    for ((position, id) in caseIdsToRun.withIndex()) {
        datasetManager.setCase(id)
        val repairCase = datasetManager.getCase()
        evaluationManager.startCase()
        logger.status("case", "${position + 1}/$testsToRun id=$id ${repairCase.testName}")
        logger.log("Case details: id=$id, testPath=${repairCase.testPath}, before=${repairCase.bCommit}, after=${repairCase.aCommit}")

        for (attempt in 1..maxAttempts) {
            logger.switchLogFile(attemptLogPath(context.runLogDir, id, attempt, repairCase.testName).toString())
            logger.status("attempt", "$attempt/$maxAttempts setup")
            val original = projectManager.setup(repairCase)

            var noError = false
            var retries = 0
            val successesBeforeAttempt = evaluationManager.currentCaseSuccessCount()
            while (!noError) {
                try {
                    logger.startAttemptProgress()
                    agent.run(datasetManager)
                    noError = true
                } catch (e: Exception) {

                    if (e.message?.contains("model_not_found") == true) {
                        throw IllegalStateException(
                            "The provider does not serve model '${context.modelId}'. Set LLM_MODEL to a supported model.",
                            e
                        )
                    }

                    stopRunOnFatalGatewayError(e, logger, id, attempt, caseIdsToRun.drop(position))
                    evaluationManager.addError()
                    agent = Agent(
                        datasetManager,
                        projectManager,
                        evaluationManager,
                        logger,
                        agentUtils,
                        agentDirectory,
                        config
                    )
                    val message = compactMessage(e.message ?: e::class.simpleName ?: "Unknown error")
                    erroredAttempts++
                    logger.error("case=$id attempt=$attempt failed: $message", e)
                    if (e.message?.contains("no chunk size") == true || e.message?.contains("code 400") == true) {

                        rejectedAttempts++
                        logger.warn("case=$id attempt=$attempt stopped because the request was rejected")
                        Files.writeString(
                            rejectionLog,
                            "$id\t$attempt\trejected\t${repairCase.testName}\t$message\n",
                            StandardOpenOption.APPEND
                        )
                        break
                    }

                    if (++retries >= maxRetries) {
                        erroredOutAttempts++
                        logger.warn(
                            "case=$id attempt=$attempt giving up after $retries retries: $message"
                        )
                        Files.writeString(
                            rejectionLog,
                            "$id\t$attempt\tretry-exhausted\t${repairCase.testName}\t$message\n",
                            StandardOpenOption.APPEND
                        )
                        break
                    }
                    projectManager.setup(repairCase)
                    runBlocking {
                        previousPrice = agentUtils.getSpendDollars()
                    }
                }
            }

            val cost = logCost(agentUtils, logger, previousPrice, id, attempt)
            previousPrice = cost.total

            evaluationManager.saveResults(
                id = id,
                attempt = attempt,
                llm = context.modelId,
                originalTest = original,
                agentTest = File("$agentDirectory/${repairCase.testPath}").readText(),
                testName = repairCase.testName,
                agentParent = agentParent.toString(),
                projectName = datasetManager.projectName
            )
            val attemptSucceeded = evaluationManager.currentCaseSuccessCount() > successesBeforeAttempt
            logger.attemptResult(attemptSucceeded, id, attempt)
            logger.status("saved", "case=$id attempt=$attempt")
            logger.attemptEndMarker(attemptSucceeded, id, attempt)
            logger.clearLogFile()
        }

        evaluationManager.endCase()
        logger.status("summary", evaluationManager.summary(attempts = maxAttempts))
    }

    val plannedAttempts = testsToRun * maxAttempts
    logger.status(
        "rejections",
        "rejected=$rejectedAttempts exhausted=$erroredOutAttempts of $plannedAttempts " +
                "(errors seen=$erroredAttempts, retry ceiling=$maxRetries) file=${rejectionLog.fileName}"
    )
    if (rejectedAttempts > 0) {
        logger.warn(
            "$rejectedAttempts of $plannedAttempts attempts ended on a rejected request and were " +
                    "scored against the unrepaired file; see $rejectionLog"
        )
    }
}

private fun compactIds(ids: List<Int>): String {
    if (ids.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var start = ids.first()
    var previous = start
    for (id in ids.drop(1)) {
        if (id == previous + 1) { previous = id; continue }
        parts += if (start == previous) "$start" else "$start-$previous"
        start = id; previous = id
    }
    parts += if (start == previous) "$start" else "$start-$previous"
    return parts.joinToString(",")
}
