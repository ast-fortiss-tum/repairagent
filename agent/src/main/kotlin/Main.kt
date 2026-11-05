package org.example

import org.example.config.ConfigLoader
import org.example.config.ExecutionMode
import org.example.utils.AgentUtils
import org.example.utils.EvaluationManager
import org.example.utils.Logger
import org.example.utils.verifyLlmAccess
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.exists

private val runDirectoryTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
private val runDisplayTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun main() {
    val config = ConfigLoader.loadConfig()
    val agentParent = Path(config.project.agentDir)

    agentParent.exists() || throw IllegalArgumentException(
        "Configured agent directory does not exist: $agentParent " +
                "(from PROJECT_AGENT_DIR or project.agent.dir; create it, or run from the repo root)"
    )

    val runStartedAt = LocalDateTime.now()
    val runLabel = when (config.app.executionMode) {
        ExecutionMode.TEST_REPAIR -> config.project.projectName
        ExecutionMode.DOCKER_SWE -> "docker-swe"
    }
    val runId = "${runStartedAt.format(runDirectoryTimestampFormatter)}_${sanitizeLogPart(runLabel)}"
    val runLogDir = agentParent.resolve("evaluation").resolve("logs").resolve(runId)
    val logbackRunDir = Path("logs").resolve(runId)

    Files.createDirectories(runLogDir)
    Files.createDirectories(logbackRunDir)
    System.setProperty("repairgent.log.file", logbackRunDir.resolve("app.log").toString())

    val agentUtils = AgentUtils(config.secrets, config.app)
    val context = RunContext(
        config = config,
        agentParent = agentParent,
        runLogDir = runLogDir,
        evaluationManager = EvaluationManager(),
        agentUtils = agentUtils,
        modelId = agentUtils.getLLModels().id,
        logger = Logger(runLogDir.resolve("run.log").toString())
    )

    context.logger.status(
        "start",
        "run=${runStartedAt.format(runDisplayTimestampFormatter)} " +
                "mode=${config.app.executionMode.name.lowercase()} model=${context.modelId}"
    )
    context.logger.status("logs", "evaluation/logs/$runId; logs/$runId")

    verifyLlmAccess(agentUtils, context.logger)

    when (config.app.executionMode) {
        ExecutionMode.TEST_REPAIR -> runTestRepairMode(context)
        ExecutionMode.DOCKER_SWE -> runDockerSweMode(context)
    }

    context.logger.status("done", "logs=evaluation/logs/$runId; logs/$runId")
}
