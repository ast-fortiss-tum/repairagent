package org.example.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.example.config.Config
import org.example.utils.DatasetManager
import org.example.utils.EvaluationManager
import org.example.utils.ExecutionParser
import org.example.utils.Logger
import org.example.utils.ProjectManager
import java.nio.file.Files
import java.nio.file.Path

object ProjectRunTools {
    private fun resolveInside(agentDirectory: Path, relativePath: String): Path {
        val root = agentDirectory.toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Path escapes agent directory: $relativePath" }
        return resolved
    }

    @Serializable
    data class RunTestArgs(
        @property:LLMDescription("The relative path to the test file to run.")
        val path: String
    )

    @Serializable
    data class ModifyArgs(
        @property:LLMDescription("The full Java test file contents that should replace the current test file.")
        val test_text: String
    )

    class RunTest(
        private val agentDirectory: Path,
        private val datasetManager: DatasetManager,
        private val projectManager: ProjectManager,
        private val evaluationManager: EvaluationManager,
        private val logger: Logger,
        private val config: Config,
    ) : SimpleTool<RunTestArgs>(
        typeToken<RunTestArgs>(),
        name = "run_test",
        description = """
            This tool is designed for running the test file under repairment.
            It will run only one test method, which you was asked to fix.
            Tool will run the test file using maven configured in the project.
            Returns the execution message.
            This tool is useful when you need to check if the repairment was successful or not.
        """.trimIndent(),
    ) {
        private val mavenSkips = listOf<String>(
            "-Djacoco.skip",
            "-Dcheckstyle.skip",
            "-Dspotless.apply.skip",
            "-Drat.skip",
            "-Denforcer.skip",
            "-Danimal.sniffer.skip",
            "-Dmaven.javadoc.skip",
            "-Dmaven.gitcommitid.skip",
            "-Dfindbugs.skip",
            "-Dwarbucks.skip",
            "-Dmodernizer.skip",
            "-Dimpsort.skip",
            "-Dpmd.skip",
            "-Dxjc.skip",
            "-Dair.check.skip-all",
            "-Dlicense.skip",
            "-Dfindbugs.skip",
            "-Denforcer.skip",
            "-Dremoteresources.skip"
        )

        override suspend fun execute(args: RunTestArgs): String {
            val test_class = datasetManager.getCase().testName
            val parent_pom_xml_path = projectManager.getPomPath()
            val mavenCommand = buildList {
                add("mvn")
                add("test")
                add("-nsu")
                add("-pl ${parent_pom_xml_path}")
                add("--also-make")
                add("-Dsurefire.failIfNoSpecifiedTests=false")
                add("-DfailIfNoTests=false")
                add("-Dmaven.test.skip=false")
                add("-DskipTests=false")
                add("-Dtest=\"${test_class}\"")
                add("--batch-mode")
                mavenSkips.forEach { add(it) }
            }

            val result = CommandExecutor(
                timeoutSeconds = config.app.timeoutSeconds,
                javaHome = config.secrets.javaHome
            ).execute(
                command = mavenCommand.joinToString(" "),
                workingDirectory = agentDirectory.toString(),
            )

            val executionParser = ExecutionParser(result.executionMessage, result.exitCode)
            if (executionParser.status == ExecutionParser.STATUS.SUCCESS) {
                evaluationManager.addSuccess(datasetManager.getCaseId())
            }
            val out = executionParser.getResult()
            logger.log(out)
            return out
        }

    }

    class ModifyTool(
        private val agentDirectory: Path,
        private val datasetManager: DatasetManager,
    ) : SimpleTool<ModifyArgs>(
        typeToken<ModifyArgs>(),
        name = "modify_test",
        description = """
            Replace current test file with a provided version.
            Returns the execution message.
            This tool is useful when you need to modify the test file to make it pass.
            The text should be a complete, valid Java test file with the original class and test method names.
        """.trimIndent()
    ) {
        override suspend fun execute(args: ModifyArgs): String {
            withContext(Dispatchers.IO) {
                Files.writeString(resolveInside(agentDirectory, datasetManager.getCase().testPath), args.test_text)
            }
            return "Test file modified successfully"
        }

    }
}
