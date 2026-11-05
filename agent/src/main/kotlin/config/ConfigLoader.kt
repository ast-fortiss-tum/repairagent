package org.example.config

import java.io.File
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.readText

object ConfigLoader {

    private data class LoadedProperties(
        val properties: Properties,
        val baseDir: Path
    )

    private fun loadEnvFile(envPath: Path) {
        if (envPath.exists()) {
            val envContent = envPath.readText()
            envContent.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")

                        if (System.getenv(key) == null) {
                            System.setProperty(key, value)
                        }
                    }
                }
        }
    }

    fun loadSecrets(envFilePath: String? = null): SecretConfig {

        envFilePath?.let { path ->
            loadEnvFile(Path.of(path))
        }

        val javaHome = System.getProperty("JAVA_HOME")
            ?: System.getenv("JAVA_HOME")
            ?: throw IllegalStateException("JAVA_HOME must be set in environment or .env file")

        val githubToken = (System.getProperty("GITHUB_TOKEN")
            ?: System.getenv("GITHUB_TOKEN"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return SecretConfig(
            openaiApiKey = sysOrEnv("OPENAI_API_KEY"),
            anthropicApiKey = sysOrEnv("ANTHROPIC_API_KEY"),
            googleApiKey = sysOrEnv("GOOGLE_API_KEY") ?: sysOrEnv("GEMINI_API_KEY"),
            javaHome = javaHome,
            githubToken = githubToken
        )
    }

    private fun sysOrEnv(key: String): String? =
        (System.getProperty(key) ?: System.getenv(key))?.trim()?.takeIf { it.isNotEmpty() }

    fun loadProjectConfig(propertiesPath: String? = null): ProjectConfig {
        val loaded = loadProperties(propertiesPath)
        val props = loaded.properties

        val agentDir = sysOrEnv("PROJECT_AGENT_DIR")
            ?: props.getProperty("project.agent.dir")
            ?: throw IllegalStateException("project.agent.dir must be set in application.properties")

        val projectLink = sysOrEnv("PROJECT_LINK")
            ?: props.getProperty("project.link")
            ?: throw IllegalStateException("project.link must be set in application.properties")

        val projectName = sysOrEnv("PROJECT_NAME")
            ?: props.getProperty("project.name")
            ?: throw IllegalStateException("project.name must be set in application.properties")

        return ProjectConfig(
            agentDir = resolvePath(agentDir, loaded.baseDir),
            projectLink = projectLink,
            projectName = projectName
        )
    }

    fun loadApplicationConfig(propertiesPath: String? = null): ApplicationConfig {
        val loaded = loadProperties(propertiesPath)
        val props = loaded.properties

        val (datasetName, datasetPath) = resolveSweDataset(props, loaded.baseDir)

        return ApplicationConfig(
            maxAttempts = (sysOrEnv("APP_MAX_ATTEMPTS") ?: props.getProperty("app.max.attempts"))
                ?.toIntOrNull() ?: 5,
            maxTests = (sysOrEnv("APP_MAX_TESTS") ?: props.getProperty("app.max.tests"))
                ?.toIntOrNull() ?: 100,
            timeoutSeconds = props.getProperty("app.timeout.seconds")?.toLongOrNull() ?: 300,
            dockerBuildTimeoutSeconds = props.getProperty("app.docker.build.timeout.seconds")?.toLongOrNull() ?: 3600,
            dockerBuildMaxAttempts = props.getProperty("app.docker.build.max.attempts")?.toIntOrNull() ?: 5,
            llmModel = sysOrEnv("LLM_MODEL") ?: props.getProperty("llm.model") ?: "gpt-4o",
            llmInputUsdPerMillion = (sysOrEnv("LLM_INPUT_USD_PER_MILLION")
                ?: props.getProperty("llm.input.usd.per.million"))?.toDoubleOrNull(),
            llmOutputUsdPerMillion = (sysOrEnv("LLM_OUTPUT_USD_PER_MILLION")
                ?: props.getProperty("llm.output.usd.per.million"))?.toDoubleOrNull(),
            executionMode = ExecutionMode.fromProperty(
                sysOrEnv("APP_EXECUTION_MODE") ?: props.getProperty("app.execution.mode")
            ),
            sweDatasetPath = datasetPath,
            sweDatasetName = datasetName,
            dockerBuildScript = resolvePath(
                props.getProperty("swe.docker.build.script") ?: "scripts/docker/docker-build.sh",
                loaded.baseDir
            ),
            dockerRunScript = resolvePath(
                props.getProperty("swe.docker.run.script") ?: "scripts/docker/docker-run.sh",
                loaded.baseDir
            ),

            maxAttemptRetries = (sysOrEnv("APP_MAX_ATTEMPT_RETRIES")
                ?: props.getProperty("app.max.attempt.retries"))?.toIntOrNull() ?: 3,
            caseIds = parseCaseIds(
                sysOrEnv("CASE_IDS") ?: sysOrEnv("SWE_CASE_IDS") ?: props.getProperty("swe.case.ids")
            )
        )
    }

    private fun parseCaseIds(raw: String?): List<Int>? {
        val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val ids = sortedSetOf<Int>()
        for (token in text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }) {
            val range = Regex("""^(\d+)-(\d+)$""").matchEntire(token)
            if (range != null) {
                val from = range.groupValues[1].toInt()
                val to = range.groupValues[2].toInt()
                (minOf(from, to)..maxOf(from, to)).forEach(ids::add)
            } else {
                ids.add(
                    token.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid CASE_IDS token: '$token' (expected an int or int-int range)")
                )
            }
        }
        return ids.toList()
    }

    private fun resolveSweDataset(props: Properties, baseDir: Path): Pair<String, String> {
        val datasetDir = props.getProperty("swe.dataset.dir")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "dataset_swe"
        val defaultName = props.getProperty("swe.dataset.default")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "gson"

        val rawSelector = (System.getProperty("SWE_DATASET")
            ?: System.getenv("SWE_DATASET")
            ?: defaultName)
            .trim()

        if (rawSelector.isEmpty()) {
            throw IllegalStateException(
                "No SWE dataset selected. Set SWE_DATASET=<name> in .env or env, " +
                        "or swe.dataset.default in application.properties."
            )
        }

        val datasetRoot = baseDir.resolve(datasetDir)

        val looksLikePath = rawSelector.endsWith(".json") ||
                rawSelector.contains('/') ||
                rawSelector.contains(File.separatorChar)

        val normalized: Path? = if (looksLikePath) {
            val explicit = Path.of(rawSelector)
            (if (explicit.isAbsolute) explicit else baseDir.resolve(explicit)).normalize()
        } else {
            findDatasetByName(datasetRoot, rawSelector)?.normalize()
        }

        if (normalized == null || !normalized.toFile().exists()) {
            val available = listAvailableDatasets(datasetRoot)
            val hint = if (available.isEmpty()) {
                "No datasets found under '$datasetDir'. Drop a <name>.json file there."
            } else {
                "Available datasets under '$datasetDir': ${available.joinToString(", ")}."
            }
            throw IllegalStateException(
                "SWE dataset '$rawSelector' not found under '$datasetDir'. $hint"
            )
        }

        val name = normalized.fileName.toString().removeSuffix(".json")
        return name to normalized.toString()
    }

    private fun findDatasetByName(root: Path, name: String): Path? {
        val dir = root.toFile()
        if (!dir.isDirectory) return null
        val target = "$name.json"
        return dir.walkTopDown()
            .firstOrNull { it.isFile && it.name == target }
            ?.toPath()
    }

    private fun listAvailableDatasets(dir: Path): List<String> {
        val file = dir.toFile()
        if (!file.isDirectory) return emptyList()
        return file.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { it.nameWithoutExtension }
            .sorted()
            .toList()
    }

    fun loadConfig(
        envFilePath: String? = ".env",
        propertiesPath: String? = "application.properties"
    ): Config {
        return Config(
            secrets = loadSecrets(envFilePath),
            project = loadProjectConfig(propertiesPath),
            app = loadApplicationConfig(propertiesPath)
        )
    }

    private fun loadProperties(propertiesPath: String?): LoadedProperties {
        val props = Properties()
        val file = propertiesPath?.let { File(it) }
        if (file?.exists() == true) {
            file.inputStream().use { props.load(it) }
        }

        val baseDir = file
            ?.toPath()
            ?.toAbsolutePath()
            ?.normalize()
            ?.parent
            ?: Path.of(".").toAbsolutePath().normalize()

        return LoadedProperties(props, baseDir)
    }

    private fun resolvePath(value: String, baseDir: Path): String {
        val path = Path.of(value.trim())
        val resolved = if (path.isAbsolute) path else baseDir.resolve(path)
        return resolved.normalize().toString()
    }
}
