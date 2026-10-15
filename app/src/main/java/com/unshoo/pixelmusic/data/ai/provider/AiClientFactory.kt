package com.unshoo.pixelmusic.data.ai.provider

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating AI client instances based on provider type
 */
@Singleton
class AiClientFactory @Inject constructor() {
    
    /**
     * Create an AI client for the specified provider
     * @param provider The AI provider type
     * @param apiKey The API key for the provider
     * @param customEndpoint Optional base URL override
     * @return AiClient instance
     */
    fun createClient(
        provider: AiProvider,
        apiKey: String,
        customEndpoint: String? = null,
        customChatEndpoint: String? = null
    ): AiClient {
        if (provider.requiresApiKey && apiKey.isBlank()) {
            throw IllegalArgumentException("API Key cannot be blank for ${provider.displayName}")
        }

        val effectiveKey = apiKey.ifBlank { "dummy-key" }
        val effectiveEndpoint = customEndpoint?.trim()?.takeIf { it.isNotBlank() }
        val effectiveChatEndpoint = customChatEndpoint?.trim()?.takeIf { it.isNotBlank() } ?: "/chat/completions"

        return when (provider) {
            AiProvider.GEMINI -> {
                if (effectiveEndpoint != null && !effectiveEndpoint.contains("generativelanguage.googleapis.com")) {
                    GenericOpenAiClient(
                        apiKey = effectiveKey,
                        baseUrl = effectiveEndpoint,
                        defaultModelId = "gemini-2.5-flash-lite",
                        providerName = "Google Gemini (Custom Endpoint)",
                        chatEndpoint = effectiveChatEndpoint
                    )
                } else {
                    GeminiAiClient(effectiveKey)
                }
            }
            AiProvider.DEEPSEEK -> {
                if (effectiveEndpoint != null && !effectiveEndpoint.contains("api.deepseek.com")) {
                    GenericOpenAiClient(
                        apiKey = effectiveKey,
                        baseUrl = effectiveEndpoint,
                        defaultModelId = "deepseek-chat",
                        providerName = "DeepSeek",
                        chatEndpoint = effectiveChatEndpoint
                    )
                } else {
                    DeepSeekAiClient(effectiveKey)
                }
            }
            AiProvider.GROQ -> {
                if (effectiveEndpoint != null && !effectiveEndpoint.contains("api.groq.com")) {
                    GenericOpenAiClient(
                        apiKey = effectiveKey,
                        baseUrl = effectiveEndpoint,
                        defaultModelId = "llama-3.3-70b-versatile",
                        providerName = "Groq",
                        chatEndpoint = effectiveChatEndpoint
                    )
                } else {
                    GroqAiClient(effectiveKey)
                }
            }
            AiProvider.MISTRAL -> {
                if (effectiveEndpoint != null && !effectiveEndpoint.contains("api.mistral.ai")) {
                    GenericOpenAiClient(
                        apiKey = effectiveKey,
                        baseUrl = effectiveEndpoint,
                        defaultModelId = "mistral-small-latest",
                        providerName = "Mistral",
                        chatEndpoint = effectiveChatEndpoint
                    )
                } else {
                    MistralAiClient(effectiveKey)
                }
            }
            AiProvider.NVIDIA -> GenericOpenAiClient(
                apiKey = effectiveKey,
                baseUrl = effectiveEndpoint ?: "https://integrate.api.nvidia.com/v1",
                defaultModelId = "meta/llama-3.1-8b-instruct",
                providerName = "NVIDIA NIM",
                chatEndpoint = effectiveChatEndpoint
            )
            AiProvider.KIMI -> GenericOpenAiClient(
                apiKey = effectiveKey,
                baseUrl = effectiveEndpoint ?: "https://api.moonshot.cn/v1",
                defaultModelId = "moonshot-v1-8k",
                providerName = "Moonshot Kimi",
                chatEndpoint = effectiveChatEndpoint
            )
            AiProvider.GLM -> GenericOpenAiClient(
                apiKey = effectiveKey,
                baseUrl = effectiveEndpoint ?: "https://open.bigmodel.cn/api/paas/v4",
                defaultModelId = "glm-4",
                providerName = "Zhipu GLM",
                chatEndpoint = effectiveChatEndpoint
            )
            AiProvider.OPENAI -> GenericOpenAiClient(
                apiKey = effectiveKey,
                baseUrl = effectiveEndpoint ?: "https://api.openai.com/v1",
                defaultModelId = "gpt-4o-mini",
                providerName = "OpenAI",
                chatEndpoint = effectiveChatEndpoint
            )
            AiProvider.OPENROUTER -> GenericOpenAiClient(
                apiKey = effectiveKey,
                baseUrl = effectiveEndpoint ?: "https://openrouter.ai/api/v1",
                defaultModelId = "google/gemini-2.0-flash-lite-preview-02-05:free",
                providerName = "OpenRouter",
                chatEndpoint = effectiveChatEndpoint
            )
            AiProvider.CUSTOM -> GenericOpenAiClient(
                apiKey = effectiveKey,
                baseUrl = effectiveEndpoint ?: "http://10.0.2.2:11434/v1",
                defaultModelId = "llama3",
                providerName = "Custom AI",
                chatEndpoint = effectiveChatEndpoint
            )
        }
    }
}
