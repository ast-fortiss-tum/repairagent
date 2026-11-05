package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import org.example.tools.DockerFileSystemTools
import org.example.tools.DockerProjectRunTools
import org.example.utils.AgentUtils
import org.example.utils.DockerProjectManager
import org.example.utils.EvaluationManager
import org.example.utils.Logger
import org.example.utils.SweDatasetManager

class DockerSweAgent(
    datasetManager: SweDatasetManager,
    dockerProjectManager: DockerProjectManager,
    evaluationManager: EvaluationManager,
    logger: Logger,
    agentUtils: AgentUtils
) {
    private val agent: AIAgent<String, String>

    init {
        val toolRegistry = ToolRegistry {
            tool(DockerFileSystemTools.ReadFileTool(dockerProjectManager))
            tool(DockerFileSystemTools.ListFolderTool(dockerProjectManager))
            tool(DockerFileSystemTools.ExistsTool(dockerProjectManager))
            tool(DockerProjectRunTools.ModifyFileTool(dockerProjectManager, datasetManager))
            tool(
                DockerProjectRunTools.RunTests(
                    dockerProjectManager = dockerProjectManager,
                    datasetManager = datasetManager,
                    evaluationManager = evaluationManager,
                    logger = logger
                )
            )
        }

        val strategy = strategy<String, String>("docker-swe strategy") {
            val nodeCallLLM by nodeLLMRequest()
            val executeToolCall by nodeExecuteTools()
            val sendToolResult by nodeLLMSendToolResults()

            edge(nodeStart forwardTo nodeCallLLM)

            edge(nodeCallLLM forwardTo executeToolCall onToolCalls { true })
            edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
            edge(executeToolCall forwardTo sendToolResult)
            edge(sendToolResult forwardTo executeToolCall onToolCalls { true })
            edge(sendToolResult forwardTo nodeFinish onTextMessage { true })
        }

        val agentConfig = AIAgentConfig(
            prompt = prompt("Docker SWE system prompt", params = LLMParams(maxTokens = agentUtils.maxOutputTokens())) { system(DockerSweSystemPrompt) },
            model = agentUtils.getLLModels(),
            maxAgentIterations = 500
        )

        agent = AIAgent(
            promptExecutor = agentUtils.getExecutor(),
            strategy = strategy,
            agentConfig = agentConfig,
            toolRegistry = toolRegistry
        ) {
            handleEvents {
                onToolCallStarting { eventContext ->
                    logger.recordToolCall(eventContext.toolName, eventContext.toolArgs)
                }
                onToolCallCompleted { eventContext ->
                    logger.recordToolCompleted(eventContext.toolName)
                }
                onToolCallFailed { eventContext ->
                    logger.recordToolFailed(eventContext.toolName, eventContext.message)
                }
                onToolValidationFailed { eventContext ->
                    logger.recordToolFailed(eventContext.toolName, eventContext.message)
                }
                onAgentCompleted { eventContext ->
                    logger.log("Agent result: ${eventContext.result}")
                }
            }
        }
    }

    fun run(datasetManager: SweDatasetManager) {
        val repairCase = datasetManager.getCase()
        val userRequest = buildString {
            appendLine("Repair the failing tests for benchmark task: ${repairCase.instanceId}")
            appendLine()
            appendLine("Fail-to-pass (f2p) tests — must be made to pass:")
            repairCase.parsedFailToPassTests().ifEmpty { listOf("(not listed)") }.forEach { appendLine("- $it") }
            appendLine()

            val testFiles = repairCase.testPatchChangedFilePaths()
            if (testFiles.isNotEmpty()) {
                appendLine("These are the only test files to fix, and the only files you may modify:")
                testFiles.forEach { appendLine("- $it") }
                appendLine("Every other file in the repository is read-only. Do not create new files.")
                appendLine()
            }

            val diff = sourceDiffSection(repairCase.patch)
            if (diff.isNotEmpty()) {
                append(diff)
                appendLine()
            }

            appendLine("Inspect the repository, repair only the test files listed above, and validate with run_tests.")
        }

        runBlocking {
            agent.run(userRequest)
        }
    }
}
