package org.example.utils

import org.example.config.ApplicationConfig
import org.example.config.Config
import org.example.config.ProjectConfig
import org.example.config.SecretConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DockerfileLayerBudgetTest {

    private val limit = 125

    private fun manager(): DockerProjectManager {
        val logPath = Files.createTempFile("layer-budget", ".log").toString()
        return DockerProjectManager(
            agentParent = Path.of("."),
            buildScript = Path.of("scripts/docker/docker-build.sh"),
            runScript = Path.of("scripts/docker/docker-run.sh"),
            config = Config(
                secrets = SecretConfig(),
                project = ProjectConfig(agentDir = ".", projectLink = "", projectName = "test"),
                app = ApplicationConfig()
            ),
            logger = Logger(logPath)
        )
    }

    private fun generate(mgr: DockerProjectManager, case: SweRepairCase): String {
        fun call1(name: String, arg: String): String =
            DockerProjectManager::class.java.getDeclaredMethod(name, String::class.java)
                .apply { isAccessible = true }.invoke(mgr, arg) as String
        val append = DockerProjectManager::class.java.getDeclaredMethod(
            "appendTestRepairImageSteps",
            String::class.java, String::class.java, String::class.java, List::class.java
        ).apply { isAccessible = true }

        var s = case.setupScripts.dockerFileContent
        s = call1("rewriteDockerfileForAuth", s)
        s = call1("collapseRunHeredocs", s)
        s = call1("collapseRunPrintfWrites", s)
        s = call1("ensureMavenLocalRepoDir", s)
        s = call1("ensureXvfbForGuiTests", s)
        s = call1("neutralizeDiagnosticGrepLoops", s)
        s = call1("ensureHermeticMavenBuilds", s)
        s = append.invoke(mgr, s, case.patch, case.testPatch, case.testPatchFilePaths()) as String
        s = call1("disableMavenWarningsAsErrors", s)
        val split = DockerProjectManager::class.java
            .getDeclaredMethod("splitOversizedPrintfBase64Runs", String::class.java, Int::class.java)
            .apply { isAccessible = true }
        s = split.invoke(mgr, s, 60_000) as String
        return call1("escapeLiteralDollarsInRunArgs", s)
    }

    @Test
    fun `every case generates a Dockerfile within the layer budget`() {
        val datasets = Path.of("dataset_swe").toFile()
            .walkTopDown().filter { it.isFile && it.extension == "json" }.map { it.path }.sorted().toList()
        assumeTrue(datasets.isNotEmpty(), "datasets not available")

        val mgr = manager()
        val worst = mutableListOf<Triple<Int, String, String>>()
        var checked = 0

        for (path in datasets) {
            val ds = SweDatasetManager(path)
            for (i in 0 until ds.getNumberOfCases()) {
                ds.setCase(i)
                val case = ds.getCase()
                val steps = generate(mgr, case).lineSequence()
                    .count { it.trimStart().startsWith("RUN ") || it.trimStart().startsWith("FROM ") ||
                             it.trimStart().startsWith("COPY ") || it.trimStart().startsWith("ADD ") }
                worst += Triple(steps, path.substringAfterLast('/').removeSuffix(".json"), case.instanceId)
                checked++
            }
        }

        worst.sortByDescending { it.first }
        val over = worst.filter { it.first > limit }
        assertTrue(
            over.isEmpty(),
            "cases exceeding the $limit-layer budget: " +
                    over.take(5).joinToString("; ") { "${it.second}/${it.third.take(30)}=${it.first}" } +
                    " (worst overall: ${worst.take(3).joinToString { "${it.second}=${it.first}" }})"
        )
        assertTrue(checked > 0, "expected at least one case")
    }
}
