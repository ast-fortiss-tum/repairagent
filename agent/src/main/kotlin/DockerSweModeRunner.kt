package org.example

import kotlinx.coroutines.runBlocking
import org.example.utils.DockerChangedFile
import org.example.utils.DockerOperationException
import org.example.utils.DockerProjectManager
import org.example.utils.SweDatasetManager
import java.nio.file.Files
import java.nio.file.Path

private data class DockerAttemptArtifacts(
    val patch: String,
    val changedFiles: List<DockerChangedFile>,
    val error: String?
)

private data class DockerSolutionArtifacts(
    val files: List<DockerChangedFile>,
    val error: String?
)

internal fun runDockerSweMode(context: RunContext) {

    val config = context.config
    val agentParent = context.agentParent
    val evaluationManager = context.evaluationManager
    val agentUtils = context.agentUtils
    val logger = context.logger
    val maxAttempts = config.app.maxAttempts
    val maxTests = config.app.maxTests
    val datasetManager = SweDatasetManager(config.app.sweDatasetPath)
    val dockerProjectManager = DockerProjectManager(
        agentParent = agentParent,
        buildScript = Path.of(config.app.dockerBuildScript),
        runScript = Path.of(config.app.dockerRunScript),
        config = config,
        logger = logger
    )
    val totalCases = datasetManager.getNumberOfCases()
    val datasetName = config.app.sweDatasetName

    val requestedIds = config.app.caseIds
    val caseIdsToRun: List<Int> = if (requestedIds != null) {
        val (valid, skipped) = requestedIds.partition { it in 0 until totalCases }
        if (skipped.isNotEmpty()) {
            logger.status(
                "warning",
                "ignoring out-of-range case ids=${skipped.joinToString(",")} (dataset has $totalCases cases)"
            )
        }
        valid
    } else {
        (0 until minOf(totalCases, maxTests)).toList()
    }

    val plan = if (requestedIds != null) {
        "cases=${caseIdsToRun.size} ids=[${caseIdsToRun.joinToString(",")}] attempts=$maxAttempts dataset=$datasetName"
    } else {
        "cases=${caseIdsToRun.size}/$totalCases attempts=$maxAttempts dataset=$datasetName"
    }
    logger.status("plan", plan)

    var previousPrice = runBlocking { agentUtils.getSpendDollars() }
    try {
        for ((index, id) in caseIdsToRun.withIndex()) {

            datasetManager.setCase(id)
            val repairCase = datasetManager.getCase()
            evaluationManager.startCase()
            logger.status("case", "${index + 1}/${caseIdsToRun.size} id=$id ${repairCase.instanceId}")
            logger.log(
                "Case details: id=$id, repo=${repairCase.repo}, base=${repairCase.baseCommit}, url=${repairCase.url}, " +
                        "failCommands=${repairCase.failToPassCommands.size}"
            )
            val resultProjectName = sweProjectName(repairCase.repo)
            val caseFileReferences = repairCase.artifactFileReferences()
            val caseTestPaths = repairCase.testPatchChangedFilePaths()

            val imageTag = try {
                dockerProjectManager.prepareImage(repairCase)
            } catch (e: Exception) {
                evaluationManager.addError()
                logger.error("case=${id + 1} docker build failed: ${compactMessage(e.message ?: "Unknown error")}", e)
                writeSweBuildFailureLog(
                    agentParent = agentParent,
                    projectName = resultProjectName,
                    caseId = id,
                    llm = context.modelId,
                    taskName = repairCase.instanceId,
                    error = e,
                    logger = logger
                )
                evaluationManager.saveSweBuildFailure(
                    id = id,
                    llm = context.modelId,
                    taskName = repairCase.instanceId,
                    repo = repairCase.repo,
                    baseCommit = repairCase.baseCommit,
                    url = repairCase.url,
                    expectedPatch = repairCase.patch,
                    failToPassCommands = repairCase.failToPassCommands,
                    attempts = maxAttempts,
                    error = e.message ?: e::class.simpleName ?: "Unknown error",
                    agentParent = agentParent.toString(),
                    datasetName = resultProjectName
                )
                evaluationManager.endCase()
                logger.status("summary", evaluationManager.summary(attempts = maxAttempts))
                continue
            }

            cleanCaseArtifacts(
                agentParent = agentParent,
                projectName = resultProjectName,
                caseId = id,
                maxAttempts = maxAttempts,
                llm = context.modelId,
                taskName = repairCase.instanceId
            )

            val expectedSolutionArtifacts = collectExpectedDockerSolutionArtifacts(
                dockerProjectManager = dockerProjectManager,
                imageTag = imageTag,
                expectedSolutionPaths = repairCase.testPatchFilePaths()
            )
            expectedSolutionArtifacts.error?.let {
                logger.status("warning", "case=${id + 1} original solution code not collected")
                logger.log(it)
            }

            for (attempt in 1..maxAttempts) {

                val attemptLog = caseAttemptLogPath(
                    agentParent,
                    resultProjectName,
                    id,
                    attempt,
                    context.modelId,
                    repairCase.instanceId
                )
                logger.switchLogFile(attemptLog.toString())
                logger.status("attempt", "$attempt/$maxAttempts docker")
                evaluationManager.resetAttemptMetrics()
                val successesBeforeAttempt = evaluationManager.currentCaseSuccessCount()
                var patch = "Patch was not collected."
                var attemptFiles = emptyList<DockerChangedFile>()
                var artifactCollectionError: String? = null

                try {
                    dockerProjectManager.startAttemptContainer(imageTag)
                    logger.startAttemptProgress()
                    DockerSweAgent(
                        datasetManager = datasetManager,
                        dockerProjectManager = dockerProjectManager,
                        evaluationManager = evaluationManager,
                        logger = logger,
                        agentUtils = agentUtils
                    ).run(datasetManager)
                    val artifacts = collectDockerAttemptArtifacts(dockerProjectManager, caseFileReferences, caseTestPaths)
                    patch = artifacts.patch
                    attemptFiles = artifacts.changedFiles
                    artifactCollectionError = artifacts.error
                } catch (e: Exception) {

                    stopRunOnFatalGatewayError(e, logger, id, attempt, caseIdsToRun.drop(index))
                    evaluationManager.addError()
                    logger.error(
                        "case=${id + 1} attempt=$attempt failed: ${compactMessage(e.message ?: "Unknown error")}",
                        e
                    )
                    val artifacts = collectDockerAttemptArtifacts(dockerProjectManager, caseFileReferences, caseTestPaths)
                    patch = artifacts.patch
                    attemptFiles = artifacts.changedFiles
                    artifactCollectionError = artifacts.error
                } finally {
                    dockerProjectManager.stopCurrentContainer()
                }

                val cost = logCost(agentUtils, logger, previousPrice, id + 1, attempt)
                previousPrice = cost.total
                val attemptSucceeded = evaluationManager.currentCaseSuccessCount() > successesBeforeAttempt
                evaluationManager.saveSweResults(
                    id = id,
                    attempt = attempt,
                    llm = context.modelId,
                    taskName = repairCase.instanceId,
                    repo = repairCase.repo,
                    baseCommit = repairCase.baseCommit,
                    url = repairCase.url,
                    expectedPatch = repairCase.patch,
                    caseFileReferences = caseFileReferences,
                    expectedFiles = expectedSolutionArtifacts.files,
                    expectedSolutionCollectionError = expectedSolutionArtifacts.error,
                    patch = patch,
                    attemptFiles = attemptFiles,
                    testRuns = evaluationManager.currentDockerTestRuns(),
                    success = attemptSucceeded,
                    price = cost.total,
                    priceDelta = cost.delta,
                    failToPassCommands = repairCase.failToPassCommands,
                    attemptLogPath = relativeTo(agentParent, attemptLog),
                    artifactCollectionError = artifactCollectionError,
                    agentParent = agentParent.toString(),
                    datasetName = resultProjectName
                )
                logger.attemptResult(attemptSucceeded, id + 1, attempt)
                logger.status("saved", "case=${id + 1} attempt=$attempt")
                logger.attemptEndMarker(attemptSucceeded, id + 1, attempt)
                logger.clearLogFile()
            }

            evaluationManager.endCase()
            logger.status("summary", evaluationManager.summary(attempts = maxAttempts))
        }
    } finally {

        dockerProjectManager.stopCurrentContainer()
    }
}

private fun collectDockerAttemptArtifacts(
    dockerProjectManager: DockerProjectManager,
    caseFileReferences: List<String>,
    caseTestPaths: List<String>
): DockerAttemptArtifacts {
    val errors = mutableListOf<String>()
    val patch = runCatching { dockerProjectManager.collectDiff() }
        .getOrElse {
            val message = "Failed to collect patch: ${it.message}"
            errors += message
            message
        }
    val changedFiles = runCatching { dockerProjectManager.collectReferencedFiles(caseFileReferences, caseTestPaths) }
        .getOrElse {
            errors += "Failed to collect case files: ${it.message}"
            emptyList()
        }
    return DockerAttemptArtifacts(
        patch = patch,
        changedFiles = changedFiles,
        error = errors.joinToString("; ").ifBlank { null }
    )
}

private fun collectExpectedDockerSolutionArtifacts(
    dockerProjectManager: DockerProjectManager,
    imageTag: String,
    expectedSolutionPaths: List<String>
): DockerSolutionArtifacts {
    return runCatching {
        dockerProjectManager.collectExpectedSolutionFiles(
            imageTag = imageTag,
            paths = expectedSolutionPaths
        )
    }
        .fold(
            onSuccess = { DockerSolutionArtifacts(files = it, error = null) },
            onFailure = {
                DockerSolutionArtifacts(
                    files = emptyList(),
                    error = "Failed to collect original solution code: ${it.message}"
                )
            }
        )
}

private fun cleanCaseArtifacts(
    agentParent: Path,
    projectName: String,
    caseId: Int,
    maxAttempts: Int,
    llm: String,
    taskName: String
) {
    for (attempt in 1..maxAttempts) {
        Files.deleteIfExists(caseAttemptLogPath(agentParent, projectName, caseId, attempt, llm, taskName))
    }
    Files.deleteIfExists(buildFailureLogPath(agentParent, projectName, caseId, llm, taskName))
    caseResultDir(agentParent, projectName, caseId, taskName, llm).toFile().deleteRecursively()
}

private fun writeSweBuildFailureLog(
    agentParent: Path,
    projectName: String,
    caseId: Int,
    llm: String,
    taskName: String,
    error: Exception,
    logger: org.example.utils.Logger
) {
    val message = error.message ?: error::class.simpleName ?: "Unknown error"
    val dockerOutput = (error as? DockerOperationException)?.output
    val buildLog = buildFailureLogPath(agentParent, projectName, caseId, llm, taskName)
    logger.switchLogFile(buildLog.toString())
    logger.status("docker", "case=${caseId + 1} image build failed")
    logger.error("case=${caseId + 1} docker build failed: ${compactMessage(message)}", error)
    dockerOutput?.takeIf { it.isNotBlank() }?.let {
        logger.log("Docker build output:\n$it")
    }
    logger.log(
        buildString {
            appendLine("Status: DOCKER_BUILD_FAILURE")
            appendLine("Exit code: 1")
            appendLine("Error message: $message")
        }
    )
    logger.clearLogFile()
}

private fun caseAttemptLogPath(
    agentParent: Path,
    projectName: String,
    caseId: Int,
    attempt: Int,
    llm: String,
    taskName: String
): Path =
    agentParent
        .resolve("evaluation")
        .resolve("logs")
        .resolve("${artifactPart(projectName)}_${caseId}_${attempt}_${artifactPart(llm)}_${artifactPart(taskName)}.log")

private fun buildFailureLogPath(
    agentParent: Path,
    projectName: String,
    caseId: Int,
    llm: String,
    taskName: String
): Path =
    agentParent
        .resolve("evaluation")
        .resolve("logs")
        .resolve("build_failures")
        .resolve("${artifactPart(projectName)}_${caseId}_${artifactPart(llm)}_${artifactPart(taskName)}.log")

private fun caseResultDir(
    agentParent: Path,
    projectName: String,
    caseId: Int,
    taskName: String,
    llm: String
): Path =
    agentParent
        .resolve("evaluation")
        .resolve("results")
        .resolve(artifactPart(projectName))
        .resolve("${caseId}_${artifactPart(taskName)}")
        .resolve(artifactPart(llm))

private fun sweProjectName(repo: String): String =
    repo
        .substringAfterLast('/')
        .replace('_', '-')
        .replace(Regex("[^A-Za-z0-9.-]+"), "-")
        .trim('.', '-')
        .ifBlank { "swe" }

private fun artifactPart(value: String, maxLength: Int = 180): String =
    value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .take(maxLength)
        .ifBlank { "unknown" }
