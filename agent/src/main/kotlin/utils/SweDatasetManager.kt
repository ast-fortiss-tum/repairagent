package org.example.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val sweStringArrayJson = Json

@Serializable
data class SweSetupScripts(
    val dockerFileContent: String
)

@Serializable
data class SweRepairCase(
    @SerialName("instance_id")
    val instanceId: String,
    val repo: String,
    @SerialName("base_commit")
    val baseCommit: String,
    @SerialName("problem_statement")
    val problemStatement: String,
    val patch: String = "",
    @SerialName("test_patch")
    val testPatch: String = "",
    @SerialName("hints_text")
    val hintsText: String = "",
    @SerialName("FAIL_TO_PASS")
    val failToPass: String = "[]",
    @SerialName("fail_to_pass_test_execution_commands")
    val failToPassCommands: List<String> = emptyList(),
    @SerialName("setup_scripts")
    val setupScripts: SweSetupScripts,
    val url: String
) {
    fun parsedFailToPassTests(): List<String> = parseStringArray(failToPass)

    fun referencedTestIdentifiers(): List<String> =
        parsedFailToPassTests()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    fun artifactFileReferences(): List<String> =
        (expectedPatchFilePaths() + referencedTestIdentifiers())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    fun expectedPatchFilePaths(): List<String> = patchFilePaths(patch)

    fun testPatchFilePaths(): List<String> = patchFilePaths(testPatch)

    fun testPatchChangedFilePaths(): List<String> =
        splitDiffBlocks(testPatch)
            .filterNot { it.contains("new file mode") || it.contains("--- /dev/null") }
            .flatMap { patchFilePaths(it) }
            .distinct()

    private fun splitDiffBlocks(rawPatch: String): List<String> {
        if (rawPatch.isBlank()) return emptyList()
        val blocks = mutableListOf<StringBuilder>()
        for (line in rawPatch.lineSequence()) {
            if (line.startsWith("diff --git ")) blocks += StringBuilder()
            blocks.lastOrNull()?.appendLine(line)
        }
        return blocks.map { it.toString() }
    }

    private fun patchFilePaths(rawPatch: String): List<String> =
        rawPatch
            .lineSequence()
            .mapNotNull { line ->
                when {
                    line.startsWith("diff --git ") -> line.substringAfter(" b/", missingDelimiterValue = "")
                    line.startsWith("+++ b/") -> line.removePrefix("+++ b/")
                    line.startsWith("rename to ") -> line.removePrefix("rename to ")
                    else -> ""
                }.takeIf { it.isNotBlank() && it != "/dev/null" }
            }
            .distinct()
            .toList()

    fun safeId(): String = instanceId
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }

    private fun parseStringArray(value: String): List<String> =
        runCatching { sweStringArrayJson.decodeFromString<List<String>>(value) }
            .getOrDefault(emptyList())
}

private val F2P_ANALYSIS_SKIP_FLAGS = listOf(
    "-Dcheckstyle.skip=true",
    "-Dspoon.skip=true",
    "-Dxwiki.spoon.skip=true",
    "-Denforcer.skip=true",
    "-Dmaven.compiler.failOnWarning=false",
)

class SweDatasetManager(datasetPath: String) {
    private val dataset: List<SweRepairCase>
    private var taskId = 0

    init {
        val datasetData = File(datasetPath).readText()

        dataset = sweJson.decodeFromString<List<SweRepairCase>>(datasetData).map { case ->
            case.copy(
                failToPassCommands = case.failToPassCommands.map { command ->
                    (listOf(command.trimEnd()) + F2P_ANALYSIS_SKIP_FLAGS).joinToString(" ")
                }
            )
        }
    }

    fun getCase(): SweRepairCase = dataset[taskId]

    fun setCase(id: Int) {
        taskId = id
    }

    fun getCaseId(): Int = taskId

    fun getNumberOfCases(): Int = dataset.size

    private companion object {
        val sweJson = Json {
            ignoreUnknownKeys = true
        }
    }
}
