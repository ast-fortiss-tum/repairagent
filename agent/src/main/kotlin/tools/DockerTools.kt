package org.example.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.example.utils.DockerCommandMetric
import org.example.utils.DockerProjectManager
import org.example.utils.DockerTestRunMetric
import org.example.utils.EvaluationManager
import org.example.utils.Logger
import org.example.utils.SweDatasetManager

object DockerFileSystemTools {
    @Serializable
    data class ExistsArgs(
        @property:LLMDescription("Relative path to check inside the Docker project directory.")
        val path: String
    )

    @Serializable
    data class ReadFileArgs(
        @property:LLMDescription("Relative path to the text file inside the Docker project directory.")
        val path: String
    )

    @Serializable
    data class ListFolderArgs(
        @property:LLMDescription("Relative path to the directory inside the Docker project directory.")
        val path: String,
        @property:LLMDescription("Maximum recursive depth. Use 0 for direct children only.")
        val depth: Int
    )

    class ExistsTool(
        private val dockerProjectManager: DockerProjectManager
    ) : SimpleTool<ExistsArgs>(
        typeToken<ExistsArgs>(),
        name = "docker_file_exists",
        description = "Checks whether a file or directory exists inside the Docker project workspace."
    ) {
        override suspend fun execute(args: ExistsArgs): String =
            if (withContext(Dispatchers.IO) { dockerProjectManager.exists(args.path) }) "Exists" else "Does not exist"
    }

    class ReadFileTool(
        private val dockerProjectManager: DockerProjectManager
    ) : SimpleTool<ReadFileArgs>(
        typeToken<ReadFileArgs>(),
        name = "docker_read_file",
        description = "Reads the complete contents of a text file inside the Docker project workspace."
    ) {
        override suspend fun execute(args: ReadFileArgs): String =
            withContext(Dispatchers.IO) { dockerProjectManager.readFile(args.path) }
    }

    class ListFolderTool(
        private val dockerProjectManager: DockerProjectManager
    ) : SimpleTool<ListFolderArgs>(
        typeToken<ListFolderArgs>(),
        name = "docker_list_folder",
        description = "Lists files and folders inside the Docker project workspace."
    ) {
        override suspend fun execute(args: ListFolderArgs): String =
            withContext(Dispatchers.IO) { dockerProjectManager.listFolder(args.path, args.depth) }
    }
}

object DockerProjectRunTools {
    @Serializable
    data class ModifyFileArgs(
        @property:LLMDescription("Relative path of the fail-to-pass test file to replace inside the Docker project workspace.")
        val path: String,
        @property:LLMDescription("Complete new file contents.")
        val content: String
    )

    @Serializable
    data class RunTestsArgs(
        @property:LLMDescription("Brief reason why the benchmark commands should be run now.")
        val reason: String
    )

    class ModifyFileTool(
        private val dockerProjectManager: DockerProjectManager,
        private val datasetManager: SweDatasetManager
    ) : SimpleTool<ModifyFileArgs>(
        typeToken<ModifyFileArgs>(),
        name = "modify_file",
        description = """
            Replaces one of the current task's fail-to-pass test files with complete new contents.
            Source files and unrelated files are not editable.
        """.trimIndent()
    ) {
        override suspend fun execute(args: ModifyFileArgs): String {
            val requestedPath = dockerProjectManager.normalizeProjectPath(args.path)
            val allowedPaths = withContext(Dispatchers.IO) {
                val case = datasetManager.getCase()
                dockerProjectManager.resolveReferencedFilePaths(
                    case.referencedTestIdentifiers(),
                    knownTestPaths = case.testPatchChangedFilePaths()
                )
            }
            if (requestedPath !in allowedPaths) {
                return buildString {
                    appendLine("Modification rejected: $requestedPath is not one of the current task's fail-to-pass test files.")
                    appendLine("Allowed files:")
                    allowedPaths.ifEmpty { listOf("(none resolved)") }.forEach { appendLine("- $it") }
                }.trimEnd()
            }
            withContext(Dispatchers.IO) {
                dockerProjectManager.writeFile(requestedPath, args.content)
            }
            return "File modified successfully: $requestedPath"
        }
    }

    class RunTests(
        private val dockerProjectManager: DockerProjectManager,
        private val datasetManager: SweDatasetManager,
        private val evaluationManager: EvaluationManager,
        private val logger: Logger
    ) : SimpleTool<RunTestsArgs>(
        typeToken<RunTestsArgs>(),
        name = "run_tests",
        description = """
            Runs the task's fail-to-pass benchmark commands inside Docker.
            Returns a compact execution report and marks the attempt successful only when all commands pass.
        """.trimIndent()
    ) {
        override suspend fun execute(args: RunTestsArgs): String = withContext(Dispatchers.IO) {
            val repairCase = datasetManager.getCase()
            val commands = buildList {
                repairCase.failToPassCommands.forEachIndexed { index, command ->
                    add(CommandToRun("F2P", index + 1, repairCase.failToPassCommands.size, command))
                }
            }

            if (commands.isEmpty()) {
                return@withContext "No benchmark execution commands are present for this task."
            }

            val report = StringBuilder()
            var allPassed = true
            val commandMetrics = mutableListOf<DockerCommandMetric>()
            val runStartedAt = System.currentTimeMillis()
            report.appendLine("reason=${args.reason}")
            report.appendLine("commands=${commands.size}")

            for (commandToRun in commands) {
                logger.status(
                    "tests",
                    "${commandToRun.kind} ${commandToRun.index}/${commandToRun.total} ${shortCommand(commandToRun.command)}"
                )
                val commandStartedAt = System.currentTimeMillis()
                val result = dockerProjectManager.executeInProject(dockerProjectManager.withGuiDisplay(commandToRun.command))
                val durationMillis = System.currentTimeMillis() - commandStartedAt
                val passed = result.exitCode == 0
                allPassed = allPassed && passed
                val marker = if (passed) "✅" else "❌"
                val commandHeader = "$marker [${commandToRun.kind} ${commandToRun.index}/${commandToRun.total}] exit=${result.exitCode} ${commandToRun.command}"
                commandMetrics += DockerCommandMetric(
                    kind = commandToRun.kind,
                    index = commandToRun.index,
                    total = commandToRun.total,
                    command = commandToRun.command,
                    exitCode = result.exitCode,
                    passed = passed,
                    durationMillis = durationMillis,
                    outputChars = result.executionMessage.length
                )

                logger.log(
                    buildString {
                        appendLine(commandHeader)
                        append(result.executionMessage)
                    }
                )
                report.appendLine(commandHeader)
                report.appendLine("durationMillis=$durationMillis")
                report.appendLine(truncate(result.executionMessage, MAX_COMMAND_OUTPUT_IN_TOOL_RESULT))
                report.appendLine()
            }

            val runMetric = DockerTestRunMetric(
                reason = args.reason,
                totalCommands = commandMetrics.size,
                passedCommands = commandMetrics.count { it.passed },
                failedCommands = commandMetrics.count { !it.passed },
                failToPassPassed = commandMetrics.count { it.kind == "F2P" && it.passed },
                failToPassTotal = repairCase.failToPassCommands.size,
                allPassed = allPassed,
                durationMillis = System.currentTimeMillis() - runStartedAt,
                commands = commandMetrics
            )
            evaluationManager.recordDockerTestRun(runMetric)
            if (allPassed) {
                evaluationManager.addSuccess(datasetManager.getCaseId())
            }
            logger.log(
                buildString {
                    appendLine("Status: ${if (allPassed) "SUCCESS" else "TEST_FAILURE"}")
                    appendLine("Exit code: ${if (allPassed) 0 else 1}")
                    appendLine("F2P: ${runMetric.failToPassPassed}/${runMetric.failToPassTotal}")
                    appendLine("Commands: ${runMetric.passedCommands}/${runMetric.totalCommands} passed")
                }
            )
            report.appendLine("overall=${if (allPassed) "success" else "failure"}")
            report.appendLine("f2p=${runMetric.failToPassPassed}/${runMetric.failToPassTotal}")
            truncate(report.toString(), MAX_TOOL_RESULT)
        }

        private data class CommandToRun(
            val kind: String,
            val index: Int,
            val total: Int,
            val command: String
        )

        private fun shortCommand(command: String, maxLength: Int = 90): String =
            if (command.length <= maxLength) command else "${command.take(maxLength)}..."

        private fun truncate(value: String, maxLength: Int): String =
            if (value.length <= maxLength) value else value.take(maxLength) + "\n... truncated ..."

        private companion object {
            const val MAX_COMMAND_OUTPUT_IN_TOOL_RESULT = 8_000
            const val MAX_TOOL_RESULT = 24_000
        }
    }
}
