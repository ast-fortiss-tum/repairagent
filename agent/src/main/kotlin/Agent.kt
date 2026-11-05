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
import org.example.config.Config
import org.example.tools.FileSystemReadTools
import org.example.tools.ProjectRunTools
import org.example.utils.AgentUtils
import org.example.utils.DatasetManager
import org.example.utils.EvaluationManager
import org.example.utils.Logger
import org.example.utils.ProjectManager
import java.io.File
import java.nio.file.Path

class Agent(
    datasetManager: DatasetManager,
    projectManager: ProjectManager,
    evaluationManager: EvaluationManager,
    logger: Logger,
    agentUtils: AgentUtils,
    private val agentDirectory: Path,
    private val config: Config
) {
    private val agent: AIAgent<String, String>

    init {
        val toolRegistry = ToolRegistry {
            tool(FileSystemReadTools.ReadFileTool(agentDirectory = agentDirectory))
            tool(
                FileSystemReadTools.ListFolderTool(
                    agentDirectory = agentDirectory
                )
            )
            tool(FileSystemReadTools.ExistsTool(agentDirectory = agentDirectory))
            tool(
                ProjectRunTools.RunTest(
                    agentDirectory = agentDirectory,
                    datasetManager = datasetManager,
                    projectManager = projectManager,
                    evaluationManager = evaluationManager,
                    logger = logger,
                    config = config
                )
            )
            tool(
                ProjectRunTools.ModifyTool(
                    agentDirectory = agentDirectory,
                    datasetManager = datasetManager
                )
            )
        }

        val strategy = strategy<String, String>("test-repair strategy") {
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
            prompt = prompt("System prompt", params = LLMParams(maxTokens = agentUtils.maxOutputTokens())) { system(FileScopedSystemPrompt) },
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

    fun run(datasetManager: DatasetManager) {
        val codeDiff = File("${agentDirectory}/commitDiff.txt").readText()
        val userRequest =
            "Your task is to fix problem in this test file: ${datasetManager.getCase().testPath}\n" +
                    "All the execution and fixes have to be made for this instance: ${datasetManager.getCase().testName}\n\n" +
                    "You are allowed to modify and run only the test file described." +
                    if (codeDiff != "")
                        "You are also provided with the source code diff, which has to be addressed to fix the test:\n ${
                            File("${agentDirectory}/commitDiff.txt").readText()
                        }" else ""

        runBlocking {
            agent.run(userRequest)
        }
    }

}
