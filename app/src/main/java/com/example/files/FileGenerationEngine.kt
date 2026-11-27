package com.example.files

import android.content.Context
import com.example.AiProvider
import com.example.ChatApiResult
import com.example.ChatMessage
import com.example.FirebaseAiService
import com.example.GeminiBuiltInModel
import com.example.GeneratedFileAttachment
import com.example.MessageSender
import com.example.NetworkClient
import com.example.SystemPromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class FileGenerationResult {
    data class Success(
        val attachment: GeneratedFileAttachment,
        val executiveSummaryMarkdown: String,
        val blueprint: FileBlueprint,
        val format: GeneratedFileFormat
    ) : FileGenerationResult()

    data class Error(val message: String) : FileGenerationResult()
}

/**
 * Executive-Grade On-Device File Generation Engine.
 *
 * 1. Requests a thorough, zero-filler JSON blueprint from the active Custom AI Provider
 *    (`max_tokens = 3500`, `temperature = 0.25`) — or Built-in Firebase AI Logic if active.
 * 2. Performs resilient JSON extraction + 1 automatic retry if JSON is malformed/truncated.
 * 3. Renders publication-quality PDF / XLSX / DOCX / CSV strictly on `Dispatchers.IO` into
 *    `filesDir/generated_files/`.
 */
class FileGenerationEngine(
    private val networkClient: NetworkClient,
    private val firebaseAiService: FirebaseAiService
) {

    suspend fun generateFile(
        context: Context,
        userRequest: String,
        targetFormat: GeneratedFileFormat,
        useBuiltInGemini: Boolean,
        builtInModel: GeminiBuiltInModel,
        activeProvider: AiProvider?,
        conversationContext: String = "",
        personalMemoryContext: String = "",
        onStatusUpdate: (String) -> Unit = {}
    ): FileGenerationResult = withContext(Dispatchers.IO) {
        val systemPrompt = ExecutiveBlueprintPrompt.buildSystemPrompt(
            targetFormat = targetFormat,
            currentDateTime = SystemPromptBuilder.formatCurrentDateTime()
        )

        val enrichedPrompt = buildString {
            appendLine("Create a complete, executive-grade ${targetFormat.badgeLabel} file for this request:")
            appendLine("\"$userRequest\"")
            if (personalMemoryContext.isNotBlank()) {
                appendLine()
                appendLine("Personal user context to incorporate where relevant:")
                appendLine(personalMemoryContext.take(600))
            }
            if (conversationContext.isNotBlank()) {
                appendLine()
                appendLine("Recent conversation context:")
                appendLine(conversationContext.take(900))
            }
            appendLine()
            append("Output ONLY the complete valid JSON blueprint matching the schema.")
        }

        onStatusUpdate("Designing ${targetFormat.badgeLabel} blueprint…")

        // Attempt 1
        var rawJson = requestBlueprintJson(
            systemPrompt = systemPrompt,
            userPrompt = enrichedPrompt,
            useBuiltInGemini = useBuiltInGemini,
            builtInModel = builtInModel,
            activeProvider = activeProvider
        )

        var blueprint = rawJson.getOrNull()?.let {
            FileBlueprintParser.parse(it, targetFormat, userRequest)
        }

        // Automatic resilient retry once if JSON was malformed or truncated
        if (blueprint == null) {
            onStatusUpdate("Refining ${targetFormat.badgeLabel} structure…")
            val repairPrompt = buildString {
                appendLine("Your previous output was truncated or not valid JSON. Return ONE complete, strictly valid JSON object (no markdown fences, no trailing commas) for:")
                appendLine("\"$userRequest\"")
                append("Keep all content thorough, realistic, and formatted for ${targetFormat.badgeLabel}.")
            }
            rawJson = requestBlueprintJson(
                systemPrompt = systemPrompt,
                userPrompt = repairPrompt,
                useBuiltInGemini = useBuiltInGemini,
                builtInModel = builtInModel,
                activeProvider = activeProvider
            )
            blueprint = rawJson.getOrNull()?.let {
                FileBlueprintParser.parse(it, targetFormat, userRequest)
            }
        }

        if (blueprint == null) {
            val errDetail = rawJson.exceptionOrNull()?.localizedMessage
                ?: "The AI model returned an incomplete document blueprint."
            return@withContext FileGenerationResult.Error(
                "Could not build the ${targetFormat.badgeLabel} blueprint: $errDetail"
            )
        }

        onStatusUpdate("Rendering ${targetFormat.badgeLabel} file…")

        val outputFile = FileExportManager.createOutputFile(
            context = context,
            suggestedFileName = blueprint.fileName,
            format = targetFormat
        )

        val renderResult = runCatching {
            when (targetFormat) {
                GeneratedFileFormat.PDF -> StyledPdfGenerator.generate(blueprint, outputFile)
                GeneratedFileFormat.XLSX -> StyledXlsxGenerator.generate(blueprint, outputFile)
                GeneratedFileFormat.DOCX -> StyledDocxGenerator.generate(blueprint, outputFile)
                GeneratedFileFormat.CSV -> CsvGenerator.generate(blueprint, outputFile)
            }
        }

        val renderedFile = renderResult.getOrElse { ex ->
            return@withContext FileGenerationResult.Error(
                "Failed to render ${targetFormat.badgeLabel} file: ${ex.localizedMessage ?: "Write error"}"
            )
        }

        val sizeBytes = renderedFile.length()
        val attachment = GeneratedFileAttachment(
            fileName = renderedFile.name,
            fileType = targetFormat.id,
            filePath = renderedFile.absolutePath,
            sizeBytes = sizeBytes
        )
        val summaryMarkdown = FileBlueprintParser.buildChatExecutiveSummary(blueprint, sizeBytes)

        FileGenerationResult.Success(
            attachment = attachment,
            executiveSummaryMarkdown = summaryMarkdown,
            blueprint = blueprint,
            format = targetFormat
        )
    }

    private suspend fun requestBlueprintJson(
        systemPrompt: String,
        userPrompt: String,
        useBuiltInGemini: Boolean,
        builtInModel: GeminiBuiltInModel,
        activeProvider: AiProvider?
    ): Result<String> {
        val preferCustom = !useBuiltInGemini && activeProvider != null
        if (preferCustom && activeProvider != null) {
            val apiResult = networkClient.sendChatMessage(
                provider = activeProvider,
                conversationHistory = listOf(ChatMessage(sender = MessageSender.USER, text = userPrompt)),
                systemPrompt = systemPrompt,
                maxTokens = 3500,
                temperature = 0.25,
                enableGoogleSearch = false
            )
            return when (apiResult) {
                is ChatApiResult.Success -> Result.success(apiResult.reply)
                is ChatApiResult.Error -> {
                    // Graceful fallback to Built-in Gemini if custom provider fails on rate/quota
                    if (FirebaseAiService.isQuotaOrRateLimitMessage(apiResult.message)) {
                        firebaseAiService.generateExecutiveBlueprintJson(
                            modelChoice = builtInModel,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            maxOutputTokens = 3500,
                            temperature = 0.25f
                        )
                    } else {
                        Result.failure(IllegalStateException(apiResult.message))
                    }
                }
            }
        }

        val geminiResult = firebaseAiService.generateExecutiveBlueprintJson(
            modelChoice = builtInModel,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            maxOutputTokens = 3500,
            temperature = 0.25f
        )
        if (geminiResult.isSuccess || activeProvider == null) return geminiResult

        // Fallback to custom provider if built-in Gemini hits quota
        return when (
            val fallback = networkClient.sendChatMessage(
                provider = activeProvider,
                conversationHistory = listOf(ChatMessage(sender = MessageSender.USER, text = userPrompt)),
                systemPrompt = systemPrompt,
                maxTokens = 3500,
                temperature = 0.25,
                enableGoogleSearch = false
            )
        ) {
            is ChatApiResult.Success -> Result.success(fallback.reply)
            is ChatApiResult.Error -> geminiResult
        }
    }
}
