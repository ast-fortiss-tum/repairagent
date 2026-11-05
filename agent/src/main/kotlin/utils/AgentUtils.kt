package org.example.utils

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import org.example.config.ApplicationConfig
import org.example.config.SecretConfig

data class ModelSpec(
    val name: String,
    val model: LLModel,
    val apiKeyVariable: String,
    val maxOutputTokens: Int,
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
)

object Models {
    val all: List<ModelSpec> = listOf(
        ModelSpec("gpt-4o", OpenAIModels.Chat.GPT4o, "OPENAI_API_KEY", 16_384, 2.50, 10.00),
        ModelSpec("gemini-2.5-flash", GoogleModels.Gemini2_5Flash, "GOOGLE_API_KEY", 64_000, 0.30, 2.50),
        ModelSpec("claude-sonnet-4", AnthropicModels.Sonnet_4, "ANTHROPIC_API_KEY", 64_000, 3.00, 15.00),
    )

    fun byName(name: String): ModelSpec =
        all.firstOrNull { it.name == name.trim().lowercase() }
            ?: throw IllegalArgumentException(
                "Unsupported LLM_MODEL '$name'. Supported: ${all.joinToString { it.name }}"
            )
}

class SpendMeter(private val inputUsdPerMillion: Double, private val outputUsdPerMillion: Double) {
    var inputTokens: Long = 0
        private set
    var outputTokens: Long = 0
        private set

    @Synchronized
    fun add(input: Long, output: Long) {
        inputTokens += input
        outputTokens += output
    }

    fun record(meta: ResponseMetaInfo?) {
        add((meta?.inputTokensCount ?: 0).toLong(), (meta?.outputTokensCount ?: 0).toLong())
    }

    val dollars: Double
        @Synchronized get() = (inputTokens * inputUsdPerMillion + outputTokens * outputUsdPerMillion) / 1_000_000.0
}

class MeteredPromptExecutor(
    private val inner: PromptExecutor,
    private val meter: SpendMeter,
) : PromptExecutor() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        inner.execute(prompt, model, tools).also { meter.record(it.metaInfo) }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        inner.executeStreaming(prompt, model, tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = inner.executeMultipleChoices(prompt, model, tools).also { choices ->
        choices.forEach { meter.record(it.metaInfo) }
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = inner.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = inner.models()

    override fun close() = inner.close()
}

class AgentUtils(
    secretConfig: SecretConfig,
    appConfig: ApplicationConfig,
) {
    private val spec = Models.byName(appConfig.llmModel)

    private val meter = SpendMeter(
        appConfig.llmInputUsdPerMillion ?: spec.inputUsdPerMillion,
        appConfig.llmOutputUsdPerMillion ?: spec.outputUsdPerMillion,
    )

    private val clients: List<Pair<LLMProvider, LLMClient>> = buildList {
        secretConfig.openaiApiKey?.let { add(LLMProvider.OpenAI to OpenAILLMClient(it)) }
        secretConfig.anthropicApiKey?.let { add(LLMProvider.Anthropic to AnthropicLLMClient(it)) }
        secretConfig.googleApiKey?.let { add(LLMProvider.Google to GoogleLLMClient(it)) }
    }

    private val promptExecutor: PromptExecutor by lazy {
        check(clients.any { it.first == spec.model.provider }) {
            "No API key for model ${spec.name}: set ${spec.apiKeyVariable} in the environment or in .env"
        }
        MeteredPromptExecutor(MultiLLMPromptExecutor(*clients.toTypedArray()), meter)
    }

    fun getExecutor(): PromptExecutor = promptExecutor

    fun getLLModels(): LLModel = spec.model

    fun maxOutputTokens(): Int = spec.maxOutputTokens

    fun apiKeyVariable(): String = spec.apiKeyVariable

    suspend fun getSpendDollars(): Double = meter.dollars

    suspend fun ping() {
        getExecutor().execute(
            prompt("preflight", params = LLMParams(maxTokens = 8)) { user("Reply with OK.") },
            getLLModels()
        )
    }
}
