package com.unshoo.pixelmusic.data.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A generic AI client for OpenAI-compatible APIs (NVIDIA, Kimi, GLM, etc.)
 */
class GenericOpenAiClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val defaultModelId: String,
    private val providerName: String = "OpenAI",
    private val chatEndpoint: String = "/chat/completions"
) : AiClient {
    
    @Serializable
    private data class ChatMessage(val role: String, val content: String)
    
    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.7
    )
    
    @Serializable
    private data class ChatChoice(val message: ChatMessage)
    
    @Serializable
    private data class ChatResponse(val choices: List<ChatChoice>)
    
    @Serializable
    private data class ModelItem(val id: String)
    
    @Serializable
    private data class ModelsResponse(val data: List<ModelItem>)
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    override suspend fun generateContent(
        model: String, 
        systemPrompt: String, 
        prompt: String,
        temperature: Float
    ): String {
        return withContext(Dispatchers.IO) {
            val resolvedModel = model.ifBlank { defaultModelId }
            val messagesList = mutableListOf<ChatMessage>()
            if (systemPrompt.isNotBlank()) {
                messagesList.add(ChatMessage(role = "system", content = systemPrompt))
            }
            messagesList.add(ChatMessage(role = "user", content = prompt))

            val requestBody = ChatRequest(
                model = resolvedModel,
                messages = messagesList,
                temperature = temperature.toDouble()
            )
            
            val jsonBody = json.encodeToString(ChatRequest.serializer(), requestBody)
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            
            val cleanBase = baseUrl.trim().trimEnd('/')
            val cleanEndpoint = chatEndpoint.trim()
            val resolvedUrl = when {
                cleanEndpoint.startsWith("http://") || cleanEndpoint.startsWith("https://") -> cleanEndpoint
                cleanBase.endsWith("/chat/completions") || cleanBase.endsWith("/completions") -> cleanBase
                cleanBase.endsWith("/v1") && (cleanEndpoint.startsWith("/v1/") || cleanEndpoint.startsWith("v1/")) -> {
                    cleanBase + "/" + cleanEndpoint.trimStart('/').removePrefix("v1/")
                }
                cleanEndpoint.isNotBlank() -> "$cleanBase/${cleanEndpoint.trimStart('/')}"
                else -> "$cleanBase/chat/completions"
            }

            android.util.Log.d("GenericOpenAiClient", "[$providerName] POST $resolvedUrl (model: $resolvedModel)")

            val requestBuilder = Request.Builder()
                .url(resolvedUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) PixelMusic/1.0")
            
            if (apiKey.isNotBlank() && !apiKey.equals("dummy", ignoreCase = true) && !apiKey.equals("dummy-key", ignoreCase = true)) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }
            
            if (providerName.equals("OpenRouter", ignoreCase = true)) {
                requestBuilder.addHeader("HTTP-Referer", "https://github.com/ianshulyadav/PixelMusic")
                requestBuilder.addHeader("X-Title", "PixelMusic")
            }

            val request = requestBuilder.post(body).build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()

                    if (!response.isSuccessful) {
                        android.util.Log.e("GenericOpenAiClient", "[$providerName] HTTP ${response.code} error: $responseBody")
                        throw AiProviderSupport.createException(
                            providerName = providerName,
                            statusCode = response.code,
                            transportMessage = response.message,
                            responseBody = responseBody,
                            requestedModel = resolvedModel
                        )
                    }

                    val chatResponse = json.decodeFromString<ChatResponse>(responseBody)
                    chatResponse.choices.firstOrNull()?.message?.content
                        ?: throw AiProviderSupport.createException(
                            providerName = providerName,
                            statusCode = response.code,
                            transportMessage = "Response had no content",
                            responseBody = responseBody,
                            requestedModel = resolvedModel
                        )
                }
            } catch (e: Exception) {
                android.util.Log.e("GenericOpenAiClient", "[$providerName] Request failed: ${e.message}", e)
                throw AiProviderSupport.wrapThrowable(providerName, e, resolvedModel)
            }
        }
    }
    
    override suspend fun countTokens(model: String, systemPrompt: String, prompt: String): Int {
        // Estimation for generic providers
        return (systemPrompt.length + prompt.length) / 4
    }
    
    private fun resolveModelsUrl(): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanEndpoint = chatEndpoint.trim()
        return when {
            cleanBase.endsWith("/chat/completions") -> cleanBase.removeSuffix("/chat/completions") + "/models"
            cleanBase.endsWith("/completions") -> cleanBase.removeSuffix("/completions") + "/models"
            cleanBase.endsWith("/v1") -> "$cleanBase/models"
            cleanEndpoint.startsWith("/v1") || cleanEndpoint.startsWith("v1") -> "$cleanBase/v1/models"
            else -> "$cleanBase/models"
        }
    }

    override suspend fun getAvailableModels(apiKey: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val modelsUrl = resolveModelsUrl()
                val requestBuilder = Request.Builder()
                    .url(modelsUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) PixelMusic/1.0")
                    .get()
                if (apiKey.isNotBlank() && !apiKey.equals("dummy", ignoreCase = true) && !apiKey.equals("dummy-key", ignoreCase = true)) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }
                val request = requestBuilder.build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    return@withContext listOf(defaultModelId)
                }
                
                val responseBody = response.body.string()
                val modelsResponse = json.decodeFromString<ModelsResponse>(responseBody)
                val allRaw = modelsResponse.data.map { it.id }.filter { 
                    !it.contains("whisper") && !it.contains("embed") && !it.contains("tts")
                }
                if (providerName.contains("Gemini", ignoreCase = true)) {
                    val geminiOnly = allRaw.filter { it.contains("gemini", ignoreCase = true) || it.contains("gemma", ignoreCase = true) }
                    geminiOnly.ifEmpty {
                        listOf(
                            "gemini-2.5-flash-lite",
                            "gemini-2.5-pro",
                            "gemini-3.1-flash-lite",
                            "gemini-3.1-pro",
                            "gemini-3.5-flash-lite",
                            "gemini-3.6-flash",
                            "gemini-3.8-flash",
                            "gemma-3-27b",
                            "gemma-4-26b",
                            "gemma-4-31b"
                        )
                    }
                } else {
                    allRaw.ifEmpty { listOf(defaultModelId) }
                }
            } catch (e: Exception) {
                if (providerName.contains("Gemini", ignoreCase = true)) {
                    listOf(
                        "gemini-2.5-flash-lite",
                        "gemini-2.5-pro",
                        "gemini-3.1-flash-lite",
                        "gemini-3.1-pro",
                        "gemini-3.5-flash-lite",
                        "gemini-3.6-flash",
                        "gemini-3.8-flash",
                        "gemma-3-27b",
                        "gemma-4-26b",
                        "gemma-4-31b"
                    )
                } else {
                    listOf(defaultModelId)
                }
            }
        }
    }
    
    override suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(resolveModelsUrl())
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) PixelMusic/1.0")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }
    
    override fun getDefaultModel(): String = defaultModelId
}
