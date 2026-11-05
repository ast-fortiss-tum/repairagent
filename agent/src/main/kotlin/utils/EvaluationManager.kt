package org.example.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path
import kotlin.math.min

@Serializable
data class DockerCommandMetric(
    val kind: String,
    val index: Int,
    val total: Int,
    val command: String,
    val exitCode: Int,
    val passed: Boolean,
    val durationMillis: Long,
    val outputChars: Int
)

@Serializable
data class DockerTestRunMetric(
    val reason: String,
    val totalCommands: Int,
    val passedCommands: Int,
    val failedCommands: Int,
    val failToPassPassed: Int,
    val failToPassTotal: Int,
    val allPassed: Boolean,
    val durationMillis: Long,
    val commands: List<DockerCommandMetric>
)

@Serializable
data class DockerSavedFile(
    val path: String,
    val status: String,
    val savedAs: String? = null,
    val chars: Int? = null,
    val error: String? = null
)

@Serializable
data class DockerAttemptMetrics(
    val id: Int,
    val attempt: Int,
    val llm: String,
    val datasetName: String,
    val taskName: String,
    val repo: String,
    val baseCommit: String,
    val url: String,
    val success: Boolean,
    val price: Double,
    val priceDelta: Double,
    val expectedPatchAvailable: Boolean,
    val exactPatchMatch: Boolean?,
    val failToPassCommands: List<String>,
    val caseFileReferences: List<String>,
    val testRunCount: Int,
    val successfulTestRuns: Int,
    val failToPassPassed: Int,
    val failToPassTotal: Int,
    val attemptLog: String,
    val originalPatchFile: String,
    val originalFile: String,
    val originalFilesDir: String,
    val originalFiles: List<DockerSavedFile>,
    val agentPatchFile: String? = null,
    val agentFile: String,
    val attemptFilesDir: String,
    val attemptFiles: List<DockerSavedFile>,
    val expectedSolutionCollectionError: String? = null,
    val artifactCollectionError: String? = null,
    val testRuns: List<DockerTestRunMetric>
)

@Serializable
data class DockerBuildFailureMetrics(
    val id: Int,
    val llm: String,
    val datasetName: String,
    val taskName: String,
    val repo: String,
    val baseCommit: String,
    val url: String,
    val success: Boolean = false,
    val failToPassCommands: List<String>,
    val originalPatchFile: String,
    val originalFile: String? = null,
    val configuredAttempts: Int,
    val error: String
)

class EvaluationManager {
    var started = 0
    var success = 0
    var errors = 0
    val ratioCount = mutableMapOf<Int, Int>()
    private var good = 0
    private val dockerTestRuns = mutableListOf<DockerTestRunMetric>()

    fun addError() {
        errors++
    }

    fun startCase() {
        started++
        good = 0
    }

    fun endCase() {
        ratioCount[good] = (ratioCount[good] ?: 0) + 1
        success += min(good, 1)

    }

    @Suppress("UNUSED_PARAMETER")
    fun addSuccess(caseId: Int? = null) {
        good++
    }

    fun currentCaseSuccessCount(): Int = good

    @Synchronized
    fun resetAttemptMetrics() {
        dockerTestRuns.clear()
    }

    @Synchronized
    fun recordDockerTestRun(testRun: DockerTestRunMetric) {
        dockerTestRuns += testRun
    }

    @Synchronized
    fun currentDockerTestRuns(): List<DockerTestRunMetric> = dockerTestRuns.toList()

    fun saveResults(
        id: Int,
        projectName: String,
        llm: String,
        attempt: Int,
        originalTest: String,
        agentTest: String,
        testName: String,
        agentParent: String
    ) {
        File("$agentParent/evaluation/results/$projectName/${id}_$testName/$llm/original.txt").parentFile?.mkdirs()

        File("$agentParent/evaluation/results/$projectName/${id}_$testName/$llm/original.txt").writeText(originalTest)
        File("$agentParent/evaluation/results/$projectName/${id}_$testName/$llm/agent_$attempt.txt").writeText(agentTest)
    }

    fun saveSweResults(
        id: Int,
        attempt: Int,
        llm: String,
        taskName: String,
        repo: String,
        baseCommit: String,
        url: String,
        expectedPatch: String,
        caseFileReferences: List<String>,
        expectedFiles: List<DockerChangedFile>,
        expectedSolutionCollectionError: String?,
        patch: String,
        attemptFiles: List<DockerChangedFile>,
        testRuns: List<DockerTestRunMetric>,
        success: Boolean,
        price: Double,
        priceDelta: Double,
        failToPassCommands: List<String>,
        attemptLogPath: String,
        artifactCollectionError: String?,
        agentParent: String,
        datasetName: String
    ) {
        val safeTaskName = taskName
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "unknown" }
        val resultDir = File("$agentParent/evaluation/results/$datasetName/${id}_$safeTaskName/$llm")
        resultDir.mkdirs()
        val originalPatchFile = File(resultDir, "original.patch")
        val originalFile = File(resultDir, "original.txt")
        val originalFilesDir = File(resultDir, "original")
        val agentFile = File(resultDir, "agent_$attempt.txt")
        val attemptFilesDir = File(resultDir, "attempt_$attempt")
        originalPatchFile.writeText(expectedPatch)
        originalFile.writeText(renderSolutionCode(expectedFiles))
        agentFile.writeText(renderSolutionCode(attemptFiles))

        val savedOriginalFiles = saveCollectedFiles(originalFilesDir, expectedFiles)
        val savedAttemptFiles = saveCollectedFiles(attemptFilesDir, attemptFiles)
        val latestRun = testRuns.lastOrNull()
        val metrics = DockerAttemptMetrics(
            id = id,
            attempt = attempt,
            llm = llm,
            datasetName = datasetName,
            taskName = taskName,
            repo = repo,
            baseCommit = baseCommit,
            url = url,
            success = success,
            price = price,
            priceDelta = priceDelta,
            expectedPatchAvailable = expectedPatch.isNotBlank(),
            exactPatchMatch = expectedPatch
                .takeIf { it.isNotBlank() }
                ?.let { normalizePatch(it) == normalizePatch(patch) },
            failToPassCommands = failToPassCommands,
            caseFileReferences = caseFileReferences,
            testRunCount = testRuns.size,
            successfulTestRuns = testRuns.count { it.allPassed },
            failToPassPassed = latestRun?.failToPassPassed ?: 0,
            failToPassTotal = failToPassCommands.size,
            attemptLog = attemptLogPath,
            originalPatchFile = originalPatchFile.name,
            originalFile = originalFile.name,
            originalFilesDir = originalFilesDir.name,
            originalFiles = savedOriginalFiles,
            agentFile = agentFile.name,
            attemptFilesDir = attemptFilesDir.name,
            attemptFiles = savedAttemptFiles,
            expectedSolutionCollectionError = expectedSolutionCollectionError,
            artifactCollectionError = artifactCollectionError,
            testRuns = testRuns
        )

        File(resultDir, "metrics_$attempt.json").writeText(resultsJson.encodeToString(metrics))
        File(resultDir, "original_files.json").writeText(resultsJson.encodeToString(savedOriginalFiles))
        File(resultDir, "attempt_${attempt}_files.json").writeText(resultsJson.encodeToString(savedAttemptFiles))
    }

    fun saveSweBuildFailure(
        id: Int,
        llm: String,
        taskName: String,
        repo: String,
        baseCommit: String,
        url: String,
        expectedPatch: String,
        failToPassCommands: List<String>,
        attempts: Int,
        error: String,
        agentParent: String,
        datasetName: String
    ) {
        val safeTaskName = taskName
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "unknown" }
        val resultDir = File("$agentParent/evaluation/results/$datasetName/${id}_$safeTaskName/$llm")
        resultDir.mkdirs()
        val metrics = DockerBuildFailureMetrics(
            id = id,
            llm = llm,
            datasetName = datasetName,
            taskName = taskName,
            repo = repo,
            baseCommit = baseCommit,
            url = url,
            failToPassCommands = failToPassCommands,
            originalPatchFile = "original.patch",
            configuredAttempts = attempts,
            error = error
        )
        File(resultDir, "original.patch").writeText(expectedPatch)
        File(resultDir, "build_failure.txt").writeText(error)
        File(resultDir, "build_failure.json").writeText(resultsJson.encodeToString(metrics))
    }

    fun summary(attempts: Int): String {
        val ratios = ratioCount
            .toSortedMap()
            .entries
            .joinToString(", ") { "${it.value}x${it.key}/$attempts" }
            .ifBlank { "none" }
        return "cases=$started success=$success errors=$errors ratios=$ratios"
    }

    private fun saveCollectedFiles(
        changedFilesDir: File,
        changedFiles: List<DockerChangedFile>
    ): List<DockerSavedFile> {
        if (changedFiles.isEmpty()) {
            return emptyList()
        }
        changedFilesDir.deleteRecursively()
        changedFilesDir.mkdirs()
        val usedNames = mutableSetOf<String>()
        return changedFiles.map { changedFile ->
            if (changedFile.content == null) {
                DockerSavedFile(
                    path = changedFile.path,
                    status = changedFile.status,
                    error = changedFile.error ?: "File has no collected content"
                )
            } else {
                val destination = flatChangedFileDestination(changedFilesDir, changedFile.path, usedNames)
                destination.writeText(changedFile.content)
                DockerSavedFile(
                    path = changedFile.path,
                    status = changedFile.status,
                    savedAs = destination.name,
                    chars = changedFile.content.length,
                    error = changedFile.error
                )
            }
        }
    }

    private fun renderSolutionCode(changedFiles: List<DockerChangedFile>): String =
        changedFiles
            .sortedBy { it.path }
            .mapNotNull { it.content }
            .joinToString(separator = "\n\n")

    private fun flatChangedFileDestination(root: File, relativePath: String, usedNames: MutableSet<String>): File {
        val baseName = flatArtifactFileName(relativePath)
        val uniqueName = uniqueFileName(baseName, usedNames)
        return root.resolve(uniqueName)
    }

    private fun flatArtifactFileName(value: String): String {
        val normalized = runCatching {
            Path.of(value.trim().removePrefix("./")).normalize().toString()
        }.getOrDefault(value)
            .replace('\\', '/')
            .removePrefix("./")
            .replace("../", "__parent__/")

        return normalized
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString(PATH_SEPARATOR_IN_FILE_NAME) { safeFileNamePart(it) }
            .trim('_')
            .take(MAX_FLAT_FILE_NAME_LENGTH)
            .ifBlank { "file" }
    }

    private fun uniqueFileName(baseName: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(baseName)) {
            return baseName
        }

        val extensionIndex = baseName.lastIndexOf('.').takeIf { it > 0 }
        val stem = extensionIndex?.let { baseName.substring(0, it) } ?: baseName
        val extension = extensionIndex?.let { baseName.substring(it) } ?: ""
        var index = 2
        while (true) {
            val suffix = "$PATH_SEPARATOR_IN_FILE_NAME$index"
            val maxStemLength = (MAX_FLAT_FILE_NAME_LENGTH - extension.length - suffix.length).coerceAtLeast(1)
            val candidate = (stem.take(maxStemLength) + suffix + extension)
                .ifBlank { "file$suffix" }
            if (usedNames.add(candidate)) {
                return candidate
            }
            index++
        }
    }

    private fun safeFileNamePart(value: String): String =
        value
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "part" }

    private fun normalizePatch(value: String): String =
        value
            .replace("\r\n", "\n")
            .trim()

    private companion object {
        const val PATH_SEPARATOR_IN_FILE_NAME = "__"
        const val MAX_FLAT_FILE_NAME_LENGTH = 220

        val resultsJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
