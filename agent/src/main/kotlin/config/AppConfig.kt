package org.example.config

import kotlinx.serialization.Serializable

@Serializable
data class SecretConfig(
    val openaiApiKey: String? = null,
    val anthropicApiKey: String? = null,
    val googleApiKey: String? = null,
    val javaHome: String = System.getProperty("java.home"),
    val githubToken: String? = null,
)

@Serializable
data class ProjectConfig(
    val agentDir: String,
    val projectLink: String,
    val projectName: String
)

@Serializable
enum class ExecutionMode {
    TEST_REPAIR,
    DOCKER_SWE;

    companion object {
        fun fromProperty(value: String?): ExecutionMode =
            when (value?.trim()?.lowercase()) {
                null, "", "test-repair", "test_repair", "file-scoped", "file_scoped" -> TEST_REPAIR
                "docker-swe", "docker_swe", "commit-scoped", "commit_scoped" -> DOCKER_SWE
                else -> throw IllegalArgumentException("Unsupported app.execution.mode: $value")
            }
    }
}

@Serializable
data class ApplicationConfig(
    val maxAttempts: Int = 5,
    val maxTests: Int = 100,
    val timeoutSeconds: Long = 300,
    val dockerBuildTimeoutSeconds: Long = 3600,
    val dockerBuildMaxAttempts: Int = 5,
    val llmModel: String = "gpt-4o",
    val llmInputUsdPerMillion: Double? = null,
    val llmOutputUsdPerMillion: Double? = null,
    val executionMode: ExecutionMode = ExecutionMode.TEST_REPAIR,
    val sweDatasetPath: String = "dataset_swe/public/gson.json",
    val sweDatasetName: String = "gson",
    val dockerBuildScript: String = "scripts/docker/docker-build.sh",
    val dockerRunScript: String = "scripts/docker/docker-run.sh",
    val caseIds: List<Int>? = null,
    val maxAttemptRetries: Int = 3
)

data class Config(
    val secrets: SecretConfig,
    val project: ProjectConfig,
    val app: ApplicationConfig
)
