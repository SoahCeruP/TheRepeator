package com.therepeator.app

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "requests")
data class TheRepeatorRequest(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val method: String,
    val url: String,
    val host: String,
    val path: String,
    val statusCode: Int,
    val protocol: String,
    val body: String,
    val headersJson: String,
    val timestamp: Long,
    val isIntercepted: Boolean = false,
    val requestBody: String? = null,
    val responseBody: String? = null,
    val requestHeadersJson: String? = null,
    val responseHeadersJson: String? = null,
    val bodyLength: Int = 0
)

@Serializable
@Entity(tableName = "browser_history")
data class BrowserHistoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class HistoryItemSummary(
    val id: Int,
    val method: String,
    val url: String,
    val host: String,
    val path: String,
    val statusCode: Int,
    val protocol: String,
    val timestamp: Long,
    val isIntercepted: Boolean,
    val bodyLength: Int,
    val headersJson: String // Still need this for inferContentType
)

@Serializable
data class RepeaterTabState(
    val id: String, // UUID
    val name: String,
    val rawRequest: String,
    val response: String = "",
    val redirectUrl: String? = null,
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1,
    val isLoading: Boolean = false,
    val metadata: ResponseMetadata? = null
)

@Serializable
data class ResponseMetadata(
    val timing: Long,
    val size: Int,
    val protocol: String,
    val tls: String?
)

@Serializable
data class DecoderStep(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: DecoderTransformType,
    val parameters: Map<String, String> = emptyMap()
)

enum class DecoderTransformType {
    BASE64_ENCODE, BASE64_DECODE,
    URL_ENCODE, URL_DECODE,
    HEX_ENCODE, HEX_DECODE,
    GZIP, GUNZIP,
    DEFLATE, INFLATE,
    HTML_ENTITY_ENCODE, HTML_ENTITY_DECODE,
    UNICODE_DECODE,
    CHARSET_CONVERT,
    JWT_DECODE
}

@Serializable
data class IntruderState(
    val templateRequest: String = "",
    val payloads: List<String> = emptyList(),
    val results: List<IntruderResult> = emptyList(),
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val concurrency: Int = 1,
    val rateLimitMillis: Long = 0,
    val rps: Int = 1,
    val randomDelay: Boolean = false,
    val timeoutSeconds: Int = 30,
    val filters: IntruderFilters = IntruderFilters(),
    val encodeUrl: Boolean = false,
    val decodeBase64: Boolean = false
)

@Serializable
data class IntruderFilters(
    val status: String = "",
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val regex: String = ""
)

@Serializable
data class IntruderResult(
    val id: String = java.util.UUID.randomUUID().toString(),
    val payload: String,
    val statusCode: Int,
    val length: Int,
    val responseTime: Long,
    val response: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class WebSocketMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val direction: MessageDirection,
    val content: String,
    val binaryData: String? = null, // Hex encoded binary data
    val timestamp: Long = System.currentTimeMillis(),
    val isPingPong: Boolean = false,
    val type: String = "Text"
)

enum class MessageDirection {
    SENT, RECEIVED
}

data class AppInfo(val name: String, val packageName: String)

data class InterceptedBrowserRequest(
    val id: String,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val rawRequest: String,
    val deferred: kotlinx.coroutines.CompletableDeferred<android.webkit.WebResourceResponse?>,
    val originalResponse: android.webkit.WebResourceResponse? = null
)

@Serializable
data class MatchReplaceRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: RuleType,
    val match: String,
    val replace: String,
    val enabled: Boolean = true
)

@Serializable
data class Variable(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val value: String
)

@Serializable
data class ScopeRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ScopeRuleType,
    val pattern: String,
    val isInScope: Boolean = true,
    val enabled: Boolean = true
)

enum class ScopeRuleType {
    HOST, PATH, KEYWORD
}

enum class RuleType {
    REQUEST_HEADER, REQUEST_BODY, RESPONSE_HEADER, RESPONSE_BODY
}
