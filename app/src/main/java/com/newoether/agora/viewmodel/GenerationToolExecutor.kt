package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.tool.AskModePolicy
import com.newoether.agora.tool.DetailStore
import com.newoether.agora.tool.DetailStoreToolProvider
import com.newoether.agora.tool.FileToolDefinitions
import com.newoether.agora.tool.ImageGenToolProvider
import com.newoether.agora.tool.ProcessTools
import com.newoether.agora.tool.SkillToolProvider
import com.newoether.agora.tool.RagToolProvider
import com.newoether.agora.tool.ShellToolProvider
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.tool.ToolPresentationMetadata
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.tool.WebSearchToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal data class AuthorizedToolCall(
    val batchIdentity: RunEffectIdentity,
    val callId: String,
    val name: String,
    val arguments: String,
    val context: GenerationContext,
    val authorizedToolNames: Set<String>,
    /** Per-generation single-image transcription flow; null disables it. */
    val toolImageTranscriber: (suspend (com.newoether.agora.model.ToolImageAttachment, suspend (String) -> Unit) -> String?)? = null,
)

internal data class AuthorizedToolResult(
    val batchIdentity: RunEffectIdentity,
    val callId: String,
    val result: ToolExecutionResult,
)

internal interface GenerationToolPresentationSource {
    fun presentationMetadata(name: String): ToolPresentationMetadata?
}

/**
 * Executes one tool call from an already mailbox-authorized batch.
 *
 * This component owns the ToolProvider instances and their provider-local resources. It never
 * chooses a tool round, advances a Provider pass, persists a result, invokes the reducer, or
 * releases a runtime slot. Progress is presentation-only; the returned result retains the exact
 * accepted batch identity and call id supplied by the caller.
 */
internal class GenerationToolExecutor private constructor(
    private val providers: List<ToolProvider>,
    private val imageGenProvider: ImageGenToolProvider?,
    val detailStore: DetailStore,
) : GenerationToolDefinitionSource, GenerationToolPresentationSource {
    companion object {
        private val FILE_TOOL_NAMES = setOf(
            "read_image",
            "open_file",
            "upload_file_to_server",
            "download_file",
        ) + FileToolDefinitions.NAMES

        fun createDefault(
            app: Application,
            conversations: ConversationRepository,
            @Suppress("UNUSED_PARAMETER") memoryManager: MemoryManager,
            skillManager: SkillManager,
            sandboxFactory: SandboxManagerFactory?,
            additionalProviders: List<ToolProvider>,
            confirmShellCommand: suspend (server: String, summary: String) -> Boolean,
            requestAllFilesAccess: (suspend (reason: String?) -> AllFilesAccessController.Outcome)? = null,
            persistSshDetectedShell: ((deviceId: String, detected: String) -> Unit)? = null,
        ): GenerationToolExecutor {
            val imageGenProvider = ImageGenToolProvider(app)
            val detailStore = DetailStore(app)
            val shellProvider = ShellToolProvider(
                sandboxFactory = sandboxFactory,
                imageStore = ToolImageStore(app),
                downloadsDir = java.io.File(app.filesDir, "downloads"),
                context = app,
                detailStore = detailStore,
            ).also { provider ->
                provider.confirm = confirmShellCommand
                provider.requestAllFilesAccess = requestAllFilesAccess
                provider.persistSshDetectedShell = persistSshDetectedShell
            }
            return GenerationToolExecutor(
                providers = listOf(
                    SkillToolProvider(skillManager),
                    WebSearchToolProvider(),
                    DetailStoreToolProvider(detailStore),
                    RagToolProvider(conversations),
                    imageGenProvider,
                    shellProvider,
                ) + additionalProviders,
                imageGenProvider = imageGenProvider,
                detailStore = detailStore,
            )
        }

        internal fun forTest(providers: List<ToolProvider>): GenerationToolExecutor =
            GenerationToolExecutor(
                providers = providers,
                imageGenProvider = null,
                detailStore = DetailStore(
                    directory = kotlin.io.path.createTempDirectory("agora-detail-store").toFile(),
                ),
            )
    }

    override fun definitions(context: GenerationContext): List<ToolDefinition> =
        providers.flatMap { it.definitions(context) }.forAskMode(context)

    fun imageDefinitions(context: GenerationContext): List<ToolDefinition> =
        imageGenProvider?.definitions(context).orEmpty().forAskMode(context)

    fun memoryDefinitions(context: GenerationContext): List<ToolDefinition> = emptyList()

    fun webSearchDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<WebSearchToolProvider>().flatMap { it.definitions(context) }

    fun ragDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<RagToolProvider>().flatMap { it.definitions(context) }

    fun shellDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<ShellToolProvider>()
            .flatMap { it.definitions(context) }
            .filter { it.function.name !in FILE_TOOL_NAMES }
            .forAskMode(context)

    fun fileDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<ShellToolProvider>()
            .flatMap { it.definitions(context) }
            .filter { it.function.name in FILE_TOOL_NAMES }
            .forAskMode(context)

    override fun presentationMetadata(name: String): ToolPresentationMetadata? {
        if (name.isBlank()) return null
        for (provider in providers) {
            provider.presentationMetadata(name)?.let { return it }
        }
        return null
    }

    suspend fun semanticSearch(
        query: String,
        limit: Int,
        context: GenerationContext,
    ): List<Pair<MessageEntity, Float>> = providers.filterIsInstance<RagToolProvider>()
        .first()
        .semanticSearch(query, limit, context)

    fun drainGeneratedImages(conversationId: String): List<String> =
        imageGenProvider?.drainImages(conversationId).orEmpty()

    /** Cleanup side effect only; it cannot authorize continuation or change runtime state. */
    suspend fun acknowledgeCommittedShellJobs(
        calls: List<ToolCallData>,
        context: GenerationContext,
    ) {
        providers.filterIsInstance<ShellToolProvider>()
            .firstOrNull()
            ?.acknowledgeCommittedJobs(calls, context)
    }

    suspend fun execute(
        call: AuthorizedToolCall,
        onEvent: suspend (ToolExecutionEvent) -> Unit,
    ): AuthorizedToolResult {
        if (call.name !in call.authorizedToolNames) {
            val text = if (call.context.askMode) {
                "Error executing tool '${call.name}': Ask mode is read-only and this tool is not in the request. Do not retry. The refusal is the mode, not another failure."
            } else {
                "Error executing tool '${call.name}': tool was not authorized for this provider pass"
            }
            return call.result(ToolExecutionResult(text = text, isError = true))
        }
        val completeArguments = call.arguments.ifBlank { "{}" }
        val argumentsAreCompleteObject = runCatching {
            Json.parseToJsonElement(completeArguments).jsonObject
        }.isSuccess
        if (!argumentsAreCompleteObject) {
            return call.result(
                ToolExecutionResult(
                    text = "Error executing tool '${call.name}': arguments are not a complete JSON object",
                    isError = true,
                ),
            )
        }

        val result = try {
            val provider = providers.firstOrNull { it.handles(call.name) }
                ?: return call.result(
                    ToolExecutionResult(text = "Unknown tool: ${call.name}", isError = true),
                )
            // A blocking provider must not pin the stream consumer forever. The detached attempt
            // lets the deadline stop awaiting immediately; cancellation still reaches cooperative
            // provider work and the tool round receives a recoverable error.
            val attemptJob = Job()
            val attempt = CoroutineScope(currentCoroutineContext() + attemptJob).async {
                var completedResult: ToolExecutionResult? = null
                provider.executeEvents(call.name, completeArguments, call.context).collect { event ->
                    if (event is ToolExecutionEvent.Completed) {
                        completedResult = event.result
                    }
                    onEvent(event)
                }
                completedResult
                    ?: ToolExecutionResult(
                        text = "Error executing tool '${call.name}': provider ended without a result",
                        isError = true,
                    )
            }
            try {
                withTimeout(call.context.toolTimeoutMs) { attempt.await() }
            } finally {
                attemptJob.cancel()
            }
        } catch (error: TimeoutCancellationException) {
            ToolExecutionResult(
                text = "Error executing tool '${call.name}': timed out after ${call.context.toolTimeoutMs}ms",
                isError = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult(
                text = "Error executing tool '${call.name}': ${error.localizedMessage ?: "Unknown error"}",
                isError = true,
            )
        }
        return call.result(result)
    }

    private fun AuthorizedToolCall.result(result: ToolExecutionResult) = AuthorizedToolResult(
        batchIdentity = batchIdentity,
        callId = callId,
        result = result,
    )
}

internal fun appendBoundedToolOutput(
    current: String?,
    delta: String,
    maxChars: Int = 32 * 1024,
): String {
    if (delta.isEmpty()) return current.orEmpty()
    val combined = current.orEmpty() + delta
    return if (combined.length <= maxChars) combined
    else combined.takeLast(maxChars)
}

private fun List<ToolDefinition>.forAskMode(context: GenerationContext): List<ToolDefinition> =
    if (!context.askMode) this else filter { AskModePolicy.allows(it.function.name) }

internal fun finalToolState(result: String): String {
    if (result.isEmpty()) return ToolExecutionStates.EMPTY
    val resultObject = runCatching {
        Json.parseToJsonElement(result).jsonObject
    }.getOrNull()
    val errorCode = (resultObject?.get("error") as? JsonPrimitive)?.content
    if (errorCode == "no_results") return ToolExecutionStates.EMPTY
    if (result.startsWith("Error", ignoreCase = true) || errorCode != null) {
        return ToolExecutionStates.FAILED
    }
    val runningFlag = (resultObject?.get("running") as? JsonPrimitive)
        ?.content
        ?.toBooleanStrictOrNull() == true
    val hasProcessId = resultObject?.get(ProcessTools.PROCESS_ID) != null ||
        resultObject?.get("async_command_id") != null
    val isBackground = (resultObject?.get("background") as? JsonPrimitive)
        ?.content
        ?.toBooleanStrictOrNull() == true ||
        (
            (resultObject?.get("state") as? JsonPrimitive)
                ?.content
                ?.equals("running", ignoreCase = true) == true &&
                resultObject.get("job_id") != null
            ) ||
        (runningFlag && hasProcessId)
    return if (isBackground) ToolExecutionStates.BACKGROUND_RUNNING
    else ToolExecutionStates.SUCCEEDED
}
