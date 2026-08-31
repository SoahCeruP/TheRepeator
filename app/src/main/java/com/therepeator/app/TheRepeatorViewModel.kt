package com.therepeator.app

import android.annotation.SuppressLint
import android.app.Application
import android.util.Base64
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.DeflaterOutputStream
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class TheRepeatorViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = TheRepeatorRepository(database.requestDao(), database.intruderResultDao(), database.browserHistoryDao())

    private val MAX_BODY_SIZE = 50_000_000L // 50MB safety limit for "Full" response

    private fun formatSize(bytes: Int): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.2f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    private fun readResponseBodySafely(body: ResponseBody?, limit: Long = MAX_BODY_SIZE): Pair<String, Int> {
        if (body == null) return "" to 0
        return try {
            val source = body.source()
            source.request(limit)
            val buffer = source.buffer
            val actualBodyLength = body.contentLength().takeIf { it != -1L } ?: buffer.size
            val isTruncated = (body.contentLength() > limit) || (body.contentLength() == -1L && buffer.size >= limit)
            
            val bytesToRead = minOf(buffer.size, limit)
            val bytes = buffer.clone().readByteArray(bytesToRead)
            val content = String(bytes, StandardCharsets.UTF_8)
            
            val finalContent = if (isTruncated) "$content\n\n[... CONTENT TRUNCATED AT ${formatSize(limit.toInt())} FOR PERFORMANCE ...]" else content
            finalContent to actualBodyLength.toInt()
        } catch (e: Exception) {
            "Error reading body: ${e.message}" to 0
        }
    }

    data class ParsedRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String)

    val matchReplaceRules = repository.matchReplaceRules
    val variables = repository.variables
    val scopeRules = repository.scopeRules

    private val _historyFilters = MutableStateFlow(setOf<String>())
    val historyFilters = _historyFilters.asStateFlow()

    private val _historySearchQuery = MutableStateFlow("")
    val historySearchQuery = _historySearchQuery.asStateFlow()

    private val _historySortField = MutableStateFlow("Time")
    val historySortField = _historySortField.asStateFlow()

    private val _historySortAscending = MutableStateFlow(value = false)
    val historySortAscending = _historySortAscending.asStateFlow()

    private val _onlyShowInScope = MutableStateFlow(value = false)
    val onlyShowInScope = _onlyShowInScope.asStateFlow()

    private val _isInterceptEnabled = MutableStateFlow(value = false)
    val isInterceptEnabled = _isInterceptEnabled.asStateFlow()

    private val _interceptionSettings = MutableStateFlow(InterceptionSettings())
    val interceptionSettings = _interceptionSettings.asStateFlow()

    private val _comparerText1 = MutableStateFlow("")
    val comparerText1 = _comparerText1.asStateFlow()

    private val _comparerText2 = MutableStateFlow("")
    val comparerText2 = _comparerText2.asStateFlow()

    private val _interceptedRequest = MutableStateFlow<InterceptedBrowserRequest?>(null)
    val interceptedRequest = _interceptedRequest.asStateFlow()

    private val _allInterceptedRequests = MutableStateFlow<List<InterceptedBrowserRequest>>(emptyList())
    val allInterceptedRequests = _allInterceptedRequests.asStateFlow()

    private val interceptionChannel = Channel<InterceptedBrowserRequest>(Channel.UNLIMITED)

    private val _selectedHistoryRequestDetails = MutableStateFlow<TheRepeatorRequest?>(null)
    val selectedHistoryRequestDetails = _selectedHistoryRequestDetails.asStateFlow()

    private val _authorizedInsecureDomains = MutableStateFlow(setOf<String>())
    val authorizedInsecureDomains = _authorizedInsecureDomains.asStateFlow()

    val browserHistory: StateFlow<List<BrowserHistoryItem>> = repository.browserHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearBrowserHistory() {
        viewModelScope.launch { repository.clearBrowserHistory() }
    }

    @OptIn(FlowPreview::class)
    val history: StateFlow<List<HistoryItemSummary>> = combine(
        repository.history,
        _historyFilters,
        _historySearchQuery.debounce(300.milliseconds),
        _onlyShowInScope,
        scopeRules,
        combine(_historySortField, _historySortAscending) { f, a -> Pair(f, a) },
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val list = args[0] as List<HistoryItemSummary>
        @Suppress("UNCHECKED_CAST")
        val filters = args[1] as Set<String>
        val query = args[2] as String
        val inScopeOnly = args[3] as Boolean
        @Suppress("UNCHECKED_CAST")
        val rules = args[4] as List<ScopeRule>
        @Suppress("UNCHECKED_CAST")
        val sortInfo = args[5] as Pair<String, Boolean>

        val (sortField, ascending) = sortInfo
        var filteredList = if (filters.isEmpty()) list else list.filter { req -> filters.contains(inferContentType(req)) }
        if (inScopeOnly) filteredList = filteredList.filter { isRequestInScope(it, rules) }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            filteredList = filteredList.filter { req ->
                (req.host.lowercase().contains(q)) || 
                (req.path.lowercase().contains(q)) || 
                (req.method.lowercase().contains(q)) || 
                (req.statusCode.toString().contains(q)) ||
                (req.url.lowercase().contains(q)) ||
                (req.id.toString() == q)
            }
        }
        
        val sorted = when (sortField) {
            "ID" -> filteredList.sortedBy { it.id }
            "Host" -> filteredList.sortedBy { it.host }
            "Code" -> filteredList.sortedBy { it.statusCode }
            "Size" -> filteredList.sortedBy { it.bodyLength }
            "Method" -> filteredList.sortedBy { it.method }
            else -> filteredList.sortedBy { it.timestamp }
        }
        if (ascending) sorted else sorted.reversed()
    }.flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            // Cleanup session data on startup
            repository.clearHistory()
            repository.clearAllIntruderResults()
        }
        viewModelScope.launch {
            for (request in interceptionChannel) {
                _allInterceptedRequests.update { it + request }
                if (_interceptedRequest.value == null) {
                    _interceptedRequest.value = request
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        webSocket?.close(1000, null)
    }

    private val _repeaterTabs = MutableStateFlow(
        listOf(
            RepeaterTabState(id = UUID.randomUUID().toString(), name = "1", rawRequest = "GET / HTTP/1.1\nHost: google.com\nConnection: close\n\n"),
        ),
    )
    val repeaterTabs: StateFlow<List<RepeaterTabState>> = _repeaterTabs.asStateFlow()

    private val _selectedBottomTab = MutableStateFlow(0)
    val selectedBottomTab: StateFlow<Int> = _selectedBottomTab.asStateFlow()

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    private val _updatedRawRequest = MutableStateFlow<String?>(null)
    val updatedRawRequest: StateFlow<String?> = _updatedRawRequest.asStateFlow()

    private val _hasNewRepeaterItem = MutableStateFlow(value = false)
    val hasNewRepeaterItem: StateFlow<Boolean> = _hasNewRepeaterItem.asStateFlow()

    private val _hasNewIntruderItem = MutableStateFlow(value = false)
    val hasNewIntruderItem: StateFlow<Boolean> = _hasNewIntruderItem.asStateFlow()

    private val _intruderState = MutableStateFlow(IntruderState())
    val intruderState: StateFlow<IntruderState> = _intruderState.asStateFlow()
    private var intruderJob: Job? = null

    private val _decoderInput = MutableStateFlow("")
    val decoderInput: StateFlow<String> = _decoderInput.asStateFlow()

    private val _decoderSteps = MutableStateFlow<List<DecoderStep>>(emptyList())
    val decoderSteps = _decoderSteps.asStateFlow()

    val decoderOutput: StateFlow<String> = _decoderInput.combine(_decoderSteps) { input, steps ->
        var current: Any = input
        try {
            steps.forEach { step -> current = applyTransform(current, step) }
            when (current) {
                is ByteArray -> current.toByteString().hex()
                else -> current.toString()
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _selectedIntruderResult = MutableStateFlow<IntruderResult?>(null)
    val selectedIntruderResult: StateFlow<IntruderResult?> = _selectedIntruderResult.asStateFlow()

    @OptIn(FlowPreview::class)
    val filteredIntruderResults: StateFlow<List<IntruderResult>> = combine(
        _intruderState.map { Triple(it.filters, it.sortField, it.sortAscending) }.distinctUntilChanged(),
        repository.getIntruderResults("default"),
    ) { (filters, sortField, sortAscending), dbResults ->
        val filtered = dbResults.filter { res ->
            val f = filters
            
            // Inclusion filters
            val statusOk = (f.status.isEmpty()) || (res.statusCode.toString().contains(f.status))
            val minOk = (f.minLength == null) || (res.length >= f.minLength)
            val maxOk = (f.maxLength == null) || (res.length <= f.maxLength)
            val regexOk = (f.regex.isEmpty()) || (res.response.contains(f.regex.toRegex(RegexOption.IGNORE_CASE)))
            
            // Exclusion filters
            val excludeStatusOk = if (f.excludeStatus.isEmpty()) true else {
                val excludedCodes = f.excludeStatus.split(",").mapNotNull { it.trim().toIntOrNull() }
                res.statusCode !in excludedCodes
            }
            val excludeLengthOk = if (f.excludeLength.isEmpty()) true else {
                val excludedLengths = f.excludeLength.split(",").mapNotNull { it.trim().toIntOrNull() }
                res.length !in excludedLengths
            }
            
            val excludeExtOk = if (f.excludeExtensions.isEmpty()) true else {
                val exts = f.excludeExtensions.split(",").map { it.trim().lowercase() }
                val reqUrl = try { res.request.split("\n")[0].split(" ")[1] } catch(_: Exception) { "" }
                val path = reqUrl.substringBefore("?").lowercase()
                exts.none { ext -> 
                    when (ext) {
                        "images" -> path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".ico")
                        else -> path.endsWith(".$ext")
                    }
                }
            }
            
            statusOk && minOk && maxOk && regexOk && excludeStatusOk && excludeLengthOk && excludeExtOk
        }
        
        val sorted = when (sortField) {
            "Status" -> filtered.sortedBy { it.statusCode }
            "Length" -> filtered.sortedBy { it.length }
            "Time" -> filtered.sortedByDescending { it.timestamp }
            "Duration" -> filtered.sortedBy { it.responseTime }
            "Payload" -> filtered.sortedBy { it.payload }
            else -> filtered.sortedByDescending { it.timestamp }
        }
        
        if (sortAscending) sorted.reversed() else sorted
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _wsMessages = MutableStateFlow<List<WebSocketMessage>>(emptyList())

    private val _wsState = MutableStateFlow("DISCONNECTED")
    val wsState = _wsState.asStateFlow()
    
    private val _wsSearchQuery = MutableStateFlow("")
    fun updateWsSearch(q: String) { _wsSearchQuery.value = q }

    @OptIn(FlowPreview::class)
    val filteredWsMessages = combine(_wsMessages, _wsSearchQuery.debounce(300.milliseconds)) { messages, query ->
        if (query.isBlank()) messages else messages.filter { it.content.contains(query, ignoreCase = true) || (it.binaryData?.contains(query, ignoreCase = true) == true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _browserSearchQuery = MutableStateFlow("")
    fun updateBrowserSearch(q: String) { _browserSearchQuery.value = q }
    
    @OptIn(FlowPreview::class)
    val browserSuggestions = combine(browserHistory, _browserSearchQuery.debounce(200.milliseconds)) { history, query ->
        if (query.isBlank()) emptyList()
        else history.asSequence().filter { it.url.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }.take(5).toList()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var webSocket: WebSocket? = null
    private var autoReconnect = false
    private var lastWsUrl: String? = null
    
    private val baseOkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1)) // Force HTTP/1.1 for broad compatibility and proxy support
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(followRedirects = true)
        .followSslRedirects(followProtocolRedirects = true)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .build()

    @SuppressLint("CustomX509TrustManager")
    private val trustAllManager = object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val unsafeOkHttpClient = baseOkHttpClient.newBuilder()
        .hostnameVerifier { _, _ -> true }
        .sslSocketFactory(createUnsafeSslSocketFactory(trustAllManager), trustAllManager)
        .connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                    .allEnabledCipherSuites()
                    .build(),
                ConnectionSpec.CLEARTEXT,
            )
        )
        .build()

    private fun getOkHttpClient(url: String, followRedirects: Boolean = true): OkHttpClient {
        val host = try { URL(url).host } catch(_: Exception) { "" }
        val isUnsafe = authorizedInsecureDomains.value.contains(host)
        val base = if (isUnsafe) unsafeOkHttpClient else baseOkHttpClient
        
        return if (followRedirects) base else base.newBuilder().followRedirects(followRedirects = false).followSslRedirects(followProtocolRedirects = false).build()
    }

    private val repeaterBaseClient = baseOkHttpClient.newBuilder()
        .followRedirects(followRedirects = false)
        .followSslRedirects(followProtocolRedirects = false)
        .build()

    private val repeaterUnsafeClient = unsafeOkHttpClient.newBuilder()
        .followRedirects(followRedirects = false)
        .followSslRedirects(followProtocolRedirects = false)
        .retryOnConnectionFailure(true)
        .build()

    private fun getRepeaterHttpClient(url: String): OkHttpClient {
        val host = try { URL(url).host } catch(_: Exception) { "" }
        val isUnsafe = authorizedInsecureDomains.value.contains(host)
        android.util.Log.d("Repeater", "Using ${if (isUnsafe) "UNSAFE" else "SAFE"} client for $host")
        return if (isUnsafe) repeaterUnsafeClient else repeaterBaseClient
    }

    private val intruderBaseClient = baseOkHttpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(followRedirects = true)
        .followSslRedirects(followProtocolRedirects = true)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 500
                maxRequestsPerHost = 500
            }
        )
        .build()

    private val intruderUnsafeClient = unsafeOkHttpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(followRedirects = true)
        .followSslRedirects(followProtocolRedirects = true)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 500
                maxRequestsPerHost = 500
            }
        )
        .build()

    private fun getIntruderHttpClient(url: String): OkHttpClient {
        val host = try { URL(url).host } catch(_: Exception) { "" }
        val isUnsafe = authorizedInsecureDomains.value.contains(host)
        return if (isUnsafe) intruderUnsafeClient else intruderBaseClient
    }

    private fun createUnsafeSslSocketFactory(trustManager: X509TrustManager): javax.net.ssl.SSLSocketFactory {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(trustManager)
        val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    private val repeaterJobs = ConcurrentHashMap<String, Job>()
    private var redirectJob: Job? = null

    fun selectTab(index: Int) { 
        _selectedTabIndex.value = index 
    }

    fun togglePinTab(id: String) {
        _repeaterTabs.value = _repeaterTabs.value.map { if (it.id == id) it.copy(isPinned = !it.isPinned) else it }
    }

    fun selectBottomTab(index: Int) {
        _selectedBottomTab.value = index
        if (index == 0) _hasNewRepeaterItem.value = false
        if (index == 1) _hasNewIntruderItem.value = false
    }
    fun addEmptyRepeaterTab() {
        val newTab = RepeaterTabState(id = UUID.randomUUID().toString(), name = (_repeaterTabs.value.size + 1).toString(), rawRequest = "GET / HTTP/1.1\nHost: example.com\nConnection: close\n\n")
        _repeaterTabs.value += newTab
        _selectedTabIndex.value = _repeaterTabs.value.size - 1
    }

    fun addRepeaterTab(name: String, raw: String) {
        val newTab = RepeaterTabState(id = UUID.randomUUID().toString(), name = name, rawRequest = raw)
        _repeaterTabs.value += newTab
        _selectedTabIndex.value = _repeaterTabs.value.size - 1
        if (_selectedBottomTab.value != 0) {
            _hasNewRepeaterItem.value = true
        }
    }

    fun renameTab(id: String, name: String) { _repeaterTabs.value = _repeaterTabs.value.map { if (it.id == id) it.copy(name = name) else it } }
    fun closeTab(id: String) {
        val currentTabs = _repeaterTabs.value
        if (currentTabs.size > 1) {
            _repeaterTabs.value = currentTabs.filter { it.id != id }
            if (_selectedTabIndex.value >= _repeaterTabs.value.size) _selectedTabIndex.value = _repeaterTabs.value.size - 1
            repeaterJobs[id]?.cancel()
        }
    }

    fun updateCurrentTabRequest(raw: String) {
        val index = _selectedTabIndex.value
        val tabs = _repeaterTabs.value.toMutableList()
        if (index in tabs.indices) {
            tabs[index] = tabs[index].copy(rawRequest = raw)
            _repeaterTabs.value = tabs
        }
    }

    private val historyUndoStack = mutableMapOf<String, Stack<String>>()
    private val historyRedoStack = mutableMapOf<String, Stack<String>>()

    private fun saveHistoryCheckpoint(tabId: String) {
        val currentRaw = _repeaterTabs.value.find { it.id == tabId }?.rawRequest ?: return
        val stack = historyUndoStack.getOrPut(tabId) { Stack() }
        if ((stack.isEmpty()) || (stack.peek() != currentRaw)) stack.push(currentRaw)
        historyRedoStack[tabId]?.clear()
    }

    fun undoRepeater(tabId: String) {
        val undo = historyUndoStack[tabId] ?: return
        if (undo.isNotEmpty()) {
            val currentRaw = _repeaterTabs.value.find { it.id == tabId }?.rawRequest ?: return
            historyRedoStack.getOrPut(tabId) { Stack() }.push(currentRaw)
            val prev = undo.pop()
            _repeaterTabs.value = _repeaterTabs.value.map { if (it.id == tabId) it.copy(rawRequest = prev) else it }
            if (_selectedTabIndex.value == _repeaterTabs.value.indexOfFirst { it.id == tabId }) _updatedRawRequest.value = prev
        }
    }

    fun redoRepeater(tabId: String) {
        val redo = historyRedoStack[tabId] ?: return
        if (redo.isNotEmpty()) {
            val currentRaw = _repeaterTabs.value.find { it.id == tabId }?.rawRequest ?: return
            historyUndoStack.getOrPut(tabId) { Stack() }.push(currentRaw)
            val next = redo.pop()
            _repeaterTabs.value = _repeaterTabs.value.map { if (it.id == tabId) it.copy(rawRequest = next) else it }
            if (_selectedTabIndex.value == _repeaterTabs.value.indexOfFirst { it.id == tabId }) _updatedRawRequest.value = next
        }
    }

    fun cancelRepeaterRequest(tabId: String) { 
        repeaterJobs[tabId]?.cancel()
        redirectJob?.cancel()
        updateTabLoading(tabId, loading = false) 
    }

    private val jsonPretty = Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true }

    @Suppress("unused")
    fun prettifyCurrentResponse() {
        val idx = _selectedTabIndex.value
        val tabs = _repeaterTabs.value.toMutableList()
        if (idx in tabs.indices) {
            val t = tabs[idx]
            val c = t.response
            if (c.isBlank() || !c.contains("\n\n")) return
            val p = c.split("\n\n", limit = 2)
            val pb = prettifyBody(p[1])
            tabs[idx] = t.copy(response = "${p[0]}\n\n$pb")
            _repeaterTabs.value = tabs
        }
    }

    fun prettifyBody(body: String): String {
        if (body.isBlank()) return body
        val tb = body.trim()
        return try {
            if (tb.startsWith("{") || tb.startsWith("[")) {
                val element = jsonPretty.parseToJsonElement(tb)
                jsonPretty.encodeToString(element)
            } else if (tb.startsWith("<")) {
                prettifyXmlHtml(tb)
            } else {
                // Improved JSON detection for embedded JSON
                val firstBrace = tb.indexOf('{')
                val firstBracket = tb.indexOf('[')
                val startIdx = when {
                    (firstBrace != -1) && (firstBracket != -1) -> minOf(firstBrace, firstBracket)
                    firstBrace != -1 -> firstBrace
                    firstBracket != -1 -> firstBracket
                    else -> -1
                }
                
                if (startIdx != -1) {
                    val possibleJson = tb.substring(startIdx)
                    try {
                        val jsonElement = jsonPretty.parseToJsonElement(possibleJson)
                        tb.substring(0, startIdx) + "\n" + jsonPretty.encodeToString(jsonElement)
                    } catch (_: Exception) {
                        if (tb.startsWith("<")) prettifyXmlHtml(tb) else body
                    }
                } else body
            }
        } catch (_: Exception) { body }
    }

    private fun prettifyXmlHtml(input: String): String {
        return try { 
            val sb = StringBuilder(); var indent = 0; val parts = input.replace(">", ">\n").replace("<", "\n<").split("\n")
            for (p in parts) { 
                val t = p.trim(); if (t.isEmpty()) continue
                if (t.startsWith("</")) indent--
                sb.append("  ".repeat(maxOf(0, indent))).append(t).append("\n")
                if (t.startsWith("<") && !t.startsWith("</") && !t.endsWith("/>") && !t.contains("</")) indent++ 
            }
            sb.toString().trim() 
        } catch (_: Exception) { input }
    }

    fun sendToIntruder(req: String) {
        _intruderState.update { it.copy(templateRequest = req) }
        if (_selectedBottomTab.value != 1) {
            _hasNewIntruderItem.value = true
        }
    }
    fun updateIntruderPayloads(p: String) { _intruderState.update { s -> s.copy(payloads = p.lines().filter { it.isNotBlank() }) } }
    fun clearIntruderPayloads() { _intruderState.update { it.copy(payloads = emptyList(), payloadFileUri = null) } }

    fun setIntruderSettings(c: Int, r: Long, t: Int, rd: Boolean, eu: Boolean, db: Boolean, rps: Int) {
        _intruderState.update { it.copy(
            concurrency = c.coerceAtLeast(1), 
            rateLimitMillis = r, 
            timeoutSeconds = t.coerceAtLeast(1), 
            randomDelay = rd, 
            encodeUrl = eu, 
            decodeBase64 = db, 
            rps = rps.coerceAtLeast(1)
        ) }
    }

    fun setIntruderFilters(status: String, min: Int?, max: Int?, regex: String, excludeStatus: String = "", excludeLength: String = "", excludeExtensions: String = "") {
        _intruderState.update { it.copy(filters = IntruderFilters(status, min, max, regex, excludeStatus, excludeLength, excludeExtensions)) }
    }

    fun setIntruderPayloadFile(uri: String) {
        _intruderState.update { it.copy(payloadFileUri = uri, payloads = emptyList()) }
    }

    fun setInterceptionMode(mode: InterceptMode) {
        _interceptionSettings.update { it.copy(mode = mode) }
    }

    fun authorizeInsecureDomain(domain: String) {
        _authorizedInsecureDomains.update { it + domain }
    }

    fun addInterceptionHost(host: String) {
        _interceptionSettings.update { it.copy(selectedHosts = it.selectedHosts + host) }
    }

    fun removeInterceptionHost(host: String) {
        _interceptionSettings.update { it.copy(selectedHosts = it.selectedHosts - host) }
    }

    fun pauseAttack() { _intruderState.update { it.copy(status = IntruderStatus.PAUSED) } }
    fun resumeAttack() { _intruderState.update { it.copy(status = IntruderStatus.RUNNING) } }
    fun cancelIntruderAttack() { 
        intruderJob?.cancel() 
        _intruderState.update { it.copy(status = IntruderStatus.CANCELLED, lastProcessedIndex = -1) }
    }

    fun runIntruderAttack(resume: Boolean = false) {
        if (_intruderState.value.status == IntruderStatus.RUNNING) return
        
        val stateSnapshot = _intruderState.value
        val template = stateSnapshot.templateRequest
        val hasPayloads = (stateSnapshot.payloadFileUri != null) || stateSnapshot.payloads.isNotEmpty()
        
        if (template.isBlank() || !template.contains("§") || !hasPayloads) {
            val reason = when {
                template.isBlank() -> "No template request"
                !template.contains("§") -> "Template must contain § marker"
                else -> "No payloads provided"
            }
            android.util.Log.e("Intruder", "Validation Failed: $reason")
            android.widget.Toast.makeText(getApplication(), "Attack Failed: $reason", android.widget.Toast.LENGTH_SHORT).show()
            _intruderState.update { it.copy(status = IntruderStatus.FAILED) }
            return
        }

        val startIdx = if (resume) _intruderState.value.lastProcessedIndex + 1 else 0
        if (!resume) {
            _intruderState.update { it.copy(status = IntruderStatus.RUNNING, stats = IntruderStats(startTime = System.currentTimeMillis(), totalPayloads = 0), lastProcessedIndex = -1) }
        } else {
            _intruderState.update { it.copy(status = IntruderStatus.RUNNING, stats = it.stats.copy(startTime = System.currentTimeMillis())) }
        }
        
        val intruderExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("Intruder", "FATAL ATTACK ERROR", throwable)
            viewModelScope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(getApplication(), "Attack Failed: ${throwable.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            _intruderState.update { it.copy(status = IntruderStatus.FAILED) }
        }

        intruderJob = viewModelScope.launch(Dispatchers.IO + intruderExceptionHandler) {
            try {
                if (!resume) {
                    try { repository.clearIntruderResults("default") } catch (e: Exception) {
                        android.util.Log.e("Intruder", "Failed to clear results", e)
                    }
                }
                
                val concurrency = _intruderState.value.concurrency.coerceAtLeast(1)
                
                // Bounded payload channel for the producer-consumer pattern
                val payloadChannel = Channel<Pair<Int, String>>(capacity = concurrency * 4)
                
                // Producer coroutine: streams payloads and feeds the channel
                val producerJob = launch {
                    try {
                        val producerState = _intruderState.value
                        if (producerState.payloadFileUri != null) {
                            val uri = producerState.payloadFileUri.toUri()
                            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                                stream.bufferedReader().useLines { lines ->
                                    lines.forEachIndexed { index, line ->
                                        if (index >= startIdx) {
                                            if (!isActive) return@useLines
                                            payloadChannel.send(Pair(index, line))
                                        }
                                    }
                                }
                            }
                        } else {
                            producerState.payloads.forEachIndexed { index, p ->
                                if (index >= startIdx) {
                                    if (!isActive) return@forEachIndexed
                                    payloadChannel.send(Pair(index, p))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Intruder", "Producer Error", e)
                    } finally {
                        payloadChannel.close()
                    }
                }

                // Estimate total
                val totalEstimate = if (_intruderState.value.payloadFileUri != null) {
                    _intruderState.value.stats.totalPayloads.takeIf { it > 0 } ?: 1000000 
                } else {
                    _intruderState.value.payloads.size
                }

                _intruderState.update { it.copy(stats = it.stats.copy(totalPayloads = totalEstimate, processedCount = startIdx, currentIndex = startIdx)) }

                // Bounded results channel to apply backpressure to workers
                val resultsChannel = Channel<IntruderResult>(capacity = 100)
                
                // Single dedicated DB writer to avoid I/O bottlenecks and race conditions
                val collectorJob = launch {
                    val window = ArrayDeque<Long>()
                    var lastUiUpdate = 0L
                    val batch = mutableListOf<IntruderResult>()
                    var processed = startIdx
                    var success = 0
                    var errors = 0
                    
                    try {
                        for (result in resultsChannel) {
                            batch.add(result)
                            processed++
                            if (result.statusCode in 200..299) success++
                            else if (result.statusCode == 0 || result.statusCode >= 500) errors++
                            
                            val now = System.currentTimeMillis()
                            window.addLast(now)
                            while (window.isNotEmpty() && (window.peekFirst() ?: 0L) < now - 2000) {
                                window.removeFirst()
                            }
                            
                            // Batch database writes
                            if (batch.size >= 50 || now - lastUiUpdate > 500) {
                                val currentBatch = batch.toList()
                                batch.clear()
                                try {
                                    repository.addIntruderResults(currentBatch)
                                } catch (_: Exception) {}
                            }
                            
                            // Throttle UI state updates
                            if (now - lastUiUpdate > 300) {
                                val currentProcessed = processed
                                val currentSuccess = success
                                val currentErrors = errors
                                val currentIndex = result.resultIndex
                                
                                _intruderState.update { s ->
                                    val newStats = s.stats.copy(
                                        processedCount = currentProcessed,
                                        successCount = currentSuccess,
                                        errorCount = currentErrors,
                                        currentIndex = currentIndex,
                                        elapsedMillis = now - s.stats.startTime,
                                        rps = window.size / 2.0
                                    )
                                    s.copy(stats = newStats, lastProcessedIndex = newStats.currentIndex)
                                }
                                lastUiUpdate = now
                            }
                        }
                    } finally {
                        if (batch.isNotEmpty()) {
                            try { repository.addIntruderResults(batch) } catch (_: Exception) {}
                        }
                    }
                }
                
                // RPS Token bucket - capacity 1 to allow small burst
                val tokenChannel = Channel<Unit>(capacity = 50)
                val tickerJob = launch {
                    var nextReleaseNanos = System.nanoTime()
                    while (isActive) {
                        val currentRps = _intruderState.value.rps.coerceAtLeast(1)
                        val intervalNanos = 1_000_000_000L / currentRps
                        
                        if (_intruderState.value.status == IntruderStatus.RUNNING) {
                            try {
                                tokenChannel.send(Unit)
                            } catch (_: Exception) { break }
                        } else {
                            // When paused, don't block. Just wait a bit.
                            delay(100.milliseconds)
                            continue
                        }
                        
                        nextReleaseNanos += intervalNanos
                        val now = System.nanoTime()
                        val waitNanos = nextReleaseNanos - now
                        
                        if (waitNanos > 0) {
                            delay((waitNanos / 1_000_000).milliseconds)
                        } else if (waitNanos < -intervalNanos * 10) {
                            nextReleaseNanos = now
                        }
                    }
                }

                // Dynamic worker management
                val activeWorkersMap = mutableMapOf<Int, Job>()
                val workerManagerJob = launch {
                    var workerIdCounter = 0
                    while (isActive) {
                        val targetConcurrency = _intruderState.value.concurrency.coerceAtLeast(1)
                        
                        // Remove finished workers
                        val finished = activeWorkersMap.filter { !it.value.isActive }.keys
                        finished.forEach { activeWorkersMap.remove(it) }

                        // Reconcile worker count
                        if (activeWorkersMap.size < targetConcurrency) {
                            repeat(targetConcurrency - activeWorkersMap.size) {
                                val id = workerIdCounter++
                                val workerJob = launch {
                                    for (next in payloadChannel) {
                                        if (!isActive) break
                                        val (absIdx, p) = next
                                        
                                        // Backpressure / RPS control
                                        tokenChannel.receive()
                                        
                                        // Pause control
                                        while (_intruderState.value.status == IntruderStatus.PAUSED && isActive) { 
                                            delay(500.milliseconds) 
                                        }
                                        if (!isActive || _intruderState.value.status != IntruderStatus.RUNNING) {
                                            if (_intruderState.value.status == IntruderStatus.CANCELLED) break
                                        }
                                        
                                        try {
                                            // Always use the latest settings from the state
                                            val currentState = _intruderState.value
                                            var payload = p
                                            if (currentState.decodeBase64) {
                                                try {
                                                    payload = String(Base64.decode(payload, Base64.DEFAULT))
                                                } catch (_: Exception) {}
                                            }
                                            if (currentState.encodeUrl) {
                                                try {
                                                    payload = URLEncoder.encode(payload, "UTF-8").replace("+", "%20")
                                                } catch (_: Exception) {}
                                            }
                                            val rawP = payload
                                            
                                            val baseReq = if (template.contains("§")) {
                                                val pairRegex = "§.*?§".toRegex()
                                                if (pairRegex.containsMatchIn(template)) {
                                                    template.replace(pairRegex, java.util.regex.Matcher.quoteReplacement(rawP))
                                                } else {
                                                    template.replace("§", rawP)
                                                }
                                            } else {
                                                template
                                            }
                                            
                                            val finalReq = applyRules(replaceVariables(baseReq))
                                            val startTime = System.currentTimeMillis()
                                            
                                            try {
                                                val parsed = parseRawRequest(finalReq)
                                                val builder = Request.Builder().url(parsed.url)
                                                parsed.headers.forEach { (k, v) -> 
                                                    val lowerK = k.lowercase()
                                                    if ((lowerK != "host") && (lowerK != "content-length")) {
                                                        try { builder.addHeader(k, v) } catch(_: Exception) {}
                                                    }
                                                }
                                                val contentType = parsed.headers.entries.find { it.key.equals("Content-Type", ignoreCase = true) }?.value
                                                val mediaType = contentType?.toMediaTypeOrNull()
                                                val reqBody = createRequestBody(parsed.method, parsed.body, mediaType)
                                                
                                                getIntruderHttpClient(parsed.url).newCall(builder.method(parsed.method, reqBody).build()).execute().use { resp ->
                                                    val (rb, bodyLen) = readResponseBodySafely(resp.body)
                                                    val result = IntruderResult(id = UUID.randomUUID().toString(), attackId = "default", resultIndex = absIdx, payload = rawP, statusCode = resp.code, length = bodyLen, responseTime = System.currentTimeMillis() - startTime, request = finalReq, response = "HTTP ${resp.code}\n${resp.headers}\n\n$rb")
                                                    if (isActive) {
                                                        resultsChannel.send(result)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                val errResult = IntruderResult(id = UUID.randomUUID().toString(), attackId = "default", resultIndex = absIdx, payload = rawP, statusCode = 0, length = 0, responseTime = System.currentTimeMillis() - startTime, request = finalReq, response = "Error: ${e.message}")
                                                if (isActive) {
                                                    resultsChannel.send(errResult)
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) { 
                                            android.util.Log.e("Intruder", "Worker Task Error", e)
                                        }
                                    }
                                }
                                activeWorkersMap[id] = workerJob
                            }
                        } else if (activeWorkersMap.size > targetConcurrency) {
                            // Gracefully reduce workers
                            val toRemove = activeWorkersMap.size - targetConcurrency
                            activeWorkersMap.keys.take(toRemove).forEach { id ->
                                activeWorkersMap[id]?.cancel()
                                activeWorkersMap.remove(id)
                            }
                        }
                        
                        delay(1000.milliseconds) // Re-check every second
                    }
                }

                try {
                    producerJob.join()
                    payloadChannel.close()
                    
                    // Wait for all active workers with a safe timeout
                    withTimeoutOrNull(10000.milliseconds) {
                        while (activeWorkersMap.any { it.value.isActive }) {
                            delay(200.milliseconds)
                            activeWorkersMap.entries.removeIf { !it.value.isActive }
                        }
                    }
                    
                    workerManagerJob.cancelAndJoin()
                } catch (e: Exception) {
                    android.util.Log.e("Intruder", "Attack lifecycle error", e)
                } finally {
                    tickerJob.cancel()
                    tokenChannel.close()
                    resultsChannel.close()
                    collectorJob.join()
                    val finalStatus = when(_intruderState.value.status) {
                        IntruderStatus.RUNNING -> IntruderStatus.COMPLETED
                        else -> _intruderState.value.status
                    }
                    _intruderState.update { it.copy(status = finalStatus) }
                }
            } catch (e: Exception) {
                android.util.Log.e("Intruder", "Job Error", e)
                _intruderState.update { it.copy(status = IntruderStatus.FAILED) }
            }
        }
    }

    private fun createRequestBody(method: String, body: String?, mediaType: MediaType?): RequestBody? {
        val m = method.uppercase()
        // OkHttp strictly forbids bodies for GET and HEAD
        if (m == "GET" || m == "HEAD") return null
        
        // These methods SHOULD have a body, even if empty
        val requiresBody = listOf("POST", "PUT", "PATCH").contains(m)
        val finalBody = body ?: ""
        
        return if (requiresBody || finalBody.isNotEmpty()) {
            finalBody.toRequestBody(mediaType)
        } else {
            null
        }
    }

    fun selectIntruderResult(res: IntruderResult?) { _selectedIntruderResult.value = res }

    fun sortIntruderResults(field: String) {
        _intruderState.update { state ->
            if (state.sortField == field) {
                state.copy(sortAscending = !state.sortAscending)
            } else {
                state.copy(sortField = field, sortAscending = false)
            }
        }
    }

    fun setHistorySort(field: String) {
        if (_historySortField.value == field) {
            _historySortAscending.value = !_historySortAscending.value
        } else {
            _historySortField.value = field
            _historySortAscending.value = false
        }
    }

    private suspend fun performOkHttpCall(method: String, url: String, headers: Map<String, String>, body: String? = null, shouldApplyRules: Boolean = true): android.webkit.WebResourceResponse? {
        val finalUrl = if (shouldApplyRules) applyRules(replaceVariables(url)) else url
        val finalMethod = (if (shouldApplyRules) applyRules(replaceVariables(method)) else method).uppercase()
        val finalHeaders = if (shouldApplyRules) headers.map { (k, v) -> k to applyRules(replaceVariables(v)) }.toMap() else headers
        val finalBody = if (shouldApplyRules) body?.let { applyRules(replaceVariables(it)) } else body

        return try {
            val builder = Request.Builder().url(finalUrl)
            
            // Strictly enforce body rules and sanitize headers
            val isNoBodyMethod = listOf("GET", "HEAD").contains(finalMethod)
            
            finalHeaders.forEach { (k, v) -> 
                val lowerK = k.lowercase()
                if (lowerK != "host") {
                    // Sanitize headers that are illegal for GET/HEAD
                    if (isNoBodyMethod && (lowerK == "content-length" || lowerK == "transfer-encoding")) {
                        // Skip
                    } else {
                        try { builder.addHeader(k, v) } catch (_: Exception) {}
                    }
                }
            }
            
            val mediaType = finalHeaders["Content-Type"]?.toMediaTypeOrNull() ?: finalHeaders["content-type"]?.toMediaTypeOrNull()
            val requestBody = createRequestBody(finalMethod, finalBody, mediaType)
            
            // To allow the browser to handle cookies, state, and URL updates correctly,
            // we must NOT follow redirects internally. We return the 30x to the WebView.
            val call = getOkHttpClient(finalUrl, followRedirects = false).newCall(builder.method(finalMethod, requestBody).build())
            val resp = withContext(Dispatchers.IO) { call.execute() }
            
            val contentType = resp.header("Content-Type") ?: "text/html"
            val isBinary = isBinary(contentType)
            
            // For logging to history, we only peek a portion of the body to avoid OOM
            val loggingLimit = 2_000_000L // 2MB for history logging
            val source = resp.body?.source()
            source?.request(loggingLimit)
            val buffer = source?.buffer ?: okio.Buffer()
            
            val bodyLen = resp.body?.contentLength().takeIf { it != -1L } ?: buffer.size
            val bytesForLogging = buffer.clone().readByteArray(minOf(buffer.size, loggingLimit))
            
            val rb = if (isBinary) "[Binary Data]" else {
                val s = String(bytesForLogging, StandardCharsets.UTF_8)
                if (bodyLen > loggingLimit) "$s\n\n[... TRUNCATED IN HISTORY ...]" else s
            }
            
            val respHeaders = mutableMapOf<String, String>()
            resp.headers.forEach { (k, v) -> respHeaders[k] = v }
            
            val cookieManager = android.webkit.CookieManager.getInstance()
            resp.headers("Set-Cookie").forEach { cookieManager.setCookie(finalUrl, it) }
            cookieManager.flush()
            
            val host = try { URL(finalUrl).host } catch(_: Exception) { "" }
            val path = try { URL(finalUrl).path.ifEmpty { "/" } } catch(_: Exception) { "/" }
            val req = TheRepeatorRequest(
                method = finalMethod, url = finalUrl, host = host, path = path, statusCode = resp.code, 
                protocol = resp.protocol.toString(), body = rb, 
                headersJson = Json.encodeToString(respHeaders), 
                timestamp = System.currentTimeMillis(), isIntercepted = true, 
                requestBody = finalBody ?: "",
                requestHeadersJson = Json.encodeToString(finalHeaders),
                responseHeadersJson = Json.encodeToString(respHeaders),
                bodyLength = bodyLen.toInt(),
            )
            if (isRequestInScope(req)) repository.addRequest(req)
            
            if (resp.code in 300..399) {
                // Fixed: Android WebView strictly forbids returning responses with status code 300-399 
                // in shouldInterceptRequest. We log it and return null to let the WebView handle 
                // the redirect natively.
                return null
            }
            
            android.webkit.WebResourceResponse(
                contentType.substringBefore(";"), 
                resp.header("Content-Encoding"), 
                resp.code, 
                resp.message.ifEmpty { "OK" }, 
                respHeaders, 
                resp.body?.byteStream(), // Return original stream to WebView
            )
        } catch (e: Exception) { 
            val errorMsg = e.message ?: "Unknown Error"
            val isSslError = errorMsg.contains("SSL") || errorMsg.contains("cert", ignoreCase = true)
            
            android.webkit.WebResourceResponse(
                "text/html", "UTF-8", 502, "Connection Error", emptyMap(),
                ByteArrayInputStream(createErrorHtml(finalUrl, errorMsg, isSslError).toByteArray())
            )
        }
    }

    private fun createErrorHtml(url: String, error: String, isSsl: Boolean): String {
        val host = try { URL(url).host } catch(_: Exception) { url }
        val title = if (isSsl) "SSL Certificate Error" else "Connection Error"
        val icon = if (isSsl) "🔒" else "⚠️"
        
        return """
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: -apple-system, sans-serif; background: #0f172a; color: #f8fafc; padding: 2rem; text-align: center; }
                    .card { background: #1e293b; border-radius: 1rem; padding: 2rem; box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1); }
                    h1 { color: #ef4444; margin-bottom: 0.5rem; }
                    p { color: #94a3b8; margin-bottom: 2rem; }
                    .btn { background: #7c3aed; color: white; border: none; padding: 0.75rem 1.5rem; border-radius: 0.5rem; font-weight: bold; text-decoration: none; display: inline-block; cursor: pointer; }
                    .btn-outline { background: transparent; border: 1px solid #475569; color: #94a3b8; margin-top: 1rem; }
                    .error-detail { font-family: monospace; font-size: 0.8rem; background: #0b1020; padding: 1rem; border-radius: 0.5rem; text-align: left; margin-top: 2rem; overflow-x: auto; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div style="font-size: 3rem;">$icon</div>
                    <h1>$title</h1>
                    <p>TheRepeator blocked an insecure connection to <strong>$host</strong>.</p>
                    
                    <div style="display: flex; flex-direction: column; gap: 10px; align-items: center;">
                        <a href="${url.replace("http://", "https://")}" class="btn" style="background: #22c55e;">Try HTTPS Version</a>
                        <a href="https://repeator.local/authorize?url=${URLEncoder.encode(url, "UTF-8")}" class="btn">Accept Risk & Continue (HTTP)</a>
                    </div>
                    
                    <div class="error-detail">
                        <strong>Error Detail:</strong><br>
                        $error
                    </div>
                    
                    <button onclick="window.history.back()" class="btn btn-outline">Go Back</button>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun isBinary(contentType: String?): Boolean {
        val ct = contentType?.lowercase() ?: return false
        return ct.contains("image/") || ct.contains("video/") || ct.contains("audio/") || 
               ct.contains("application/octet-stream") || ct.contains("application/zip") || 
               ct.contains("application/pdf") || ct.contains("application/wasm")
    }

    suspend fun handleBrowserTraffic(method: String, url: String, headers: Map<String, String>): android.webkit.WebResourceResponse? {
        // Logins/State: standard WebView interception CANNOT see POST bodies.
        // If we intercept a POST, the body is lost, breaking login pages.
        // Therefore, we let POST requests go through the standard networking stack.
        if (method.uppercase() == "POST") {
            return null
        }

        // Handle internal authorization protocol via virtual domain
        if (url.startsWith("https://repeator.local/authorize") || url.startsWith("http://repeator.local/authorize")) {
            val targetUrl = url.toUri().getQueryParameter("url")
            if (targetUrl != null) {
                val domain = try { URL(targetUrl).host } catch(_: Exception) { "" }
                if (domain.isNotEmpty()) {
                    authorizeInsecureDomain(domain)
                    // Return a simple HTML page that redirects back viaJavascript (more stable than 302 in shouldInterceptRequest)
                    val redirectHtml = "<html><script>window.location.href='${targetUrl.replace("'", "\\'")}';</script></html>"
                    return android.webkit.WebResourceResponse(
                        "text/html", "UTF-8", 200, "OK", emptyMap(),
                        ByteArrayInputStream(redirectHtml.toByteArray())
                    )
                }
            }
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null // Let WebView handle data:, blob:, etc. natively
        }
        
        val uo = try { URL(url) } catch(_: Exception) { null }
        val host = uo?.host ?: ""

        // Security: Automatically try to switch to HTTPS if possible
        if (url.startsWith("http://") && !authorizedInsecureDomains.value.contains(host)) {
            // Check if this is a main document navigation
            val accept = headers.entries.find { it.key.equals("Accept", ignoreCase = true) }?.value ?: ""
            val isRootPath = uo?.path == null || uo.path.isEmpty() || (uo.path == "/")
            
            if (method == "GET" && (accept.contains("text/html") || isRootPath)) {
                // Return the "Accept Risk" warning page
                return android.webkit.WebResourceResponse(
                    "text/html", "UTF-8", 200, "OK", emptyMap(),
                    ByteArrayInputStream(createErrorHtml(url, "Plain HTTP connections are unencrypted and insecure. You should use HTTPS whenever possible.", false).toByteArray())
                )
            }
        }
        val pathAndQuery = (uo?.path ?: "/").ifEmpty { "/" } + (if (uo?.query != null) "?" + uo.query else "")
        
        val contentType = headers.entries.find { it.key.equals("Content-Type", ignoreCase = true) }?.value?.lowercase() ?: ""

        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("chrome-extension:")) {
            return performOkHttpCall(method, url, headers)
        }

        val summary = HistoryItemSummary(
            id = 0, method = method, url = url, host = host, path = uo?.path ?: "/",
            statusCode = 0, protocol = "HTTP/1.1", timestamp = System.currentTimeMillis(),
            isIntercepted = false, bodyLength = 0, headersJson = "{}",
        )
        
        val settings = _interceptionSettings.value
        val shouldIntercept = _isInterceptEnabled.value && when (settings.mode) {
            InterceptMode.ALL -> true
            InterceptMode.IN_SCOPE -> isRequestInScope(summary, scopeRules.value)
            InterceptMode.POST_PUT -> method == "POST" || method == "PUT"
            InterceptMode.JSON -> contentType.contains("json")
            InterceptMode.NONE -> settings.selectedHosts.contains(host)
        }

        if (shouldIntercept) {
            val deferred = CompletableDeferred<android.webkit.WebResourceResponse?>()
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            val allHeaders = headers.toMutableMap()
            if (!cookies.isNullOrEmpty() && !allHeaders.containsKey("Cookie") && !allHeaders.containsKey("cookie")) {
                allHeaders["Cookie"] = cookies
            }
            
            val raw = "$method $pathAndQuery HTTP/1.1\nHost: $host\n" + allHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n"
            val intercepted = InterceptedBrowserRequest(id = UUID.randomUUID().toString(), method = method, url = url, headers = allHeaders, rawRequest = raw, deferred = deferred)
            
            interceptionChannel.send(intercepted)
            
            return try {
                withTimeout(60000.milliseconds) { deferred.await() }
                    ?: performOkHttpCall(method, url, headers) // Proceed with original request if null (Forward All / User opted not to modify)
            } catch (_: Exception) {
                dropInterceptedRequest(intercepted.id)
                android.webkit.WebResourceResponse("text/html", "UTF-8", 403, "Timeout", null, ByteArrayInputStream("Request Timed Out".toByteArray()))
            }
        } else {
            return performOkHttpCall(method, url, headers)
        }
    }

    fun inferContentType(req: HistoryItemSummary): String {
        val url = req.url.lowercase().substringBefore("?")
        val headers = try { Json.decodeFromString<Map<String, String>>(req.headersJson) } catch (_: Exception) { emptyMap() }
        val contentType = headers.entries.find { it.key.equals("content-type", ignoreCase = true) }?.value?.lowercase() ?: ""
        return when {
            url.endsWith(".js") || contentType.contains("javascript") -> "JS"
            url.endsWith(".json") || contentType.contains("json") -> "JSON"
            url.endsWith(".xml") || contentType.contains("xml") -> "XML"
            url.endsWith(".html") || url.endsWith(".htm") || contentType.contains("text/html") -> "HTML"
            url.endsWith(".css") || contentType.contains("text/css") -> "CSS"
            url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") || url.endsWith(".gif") || url.endsWith(".webp") || url.endsWith(".svg") || contentType.contains("image/") -> "Images"
            url.endsWith(".woff") || url.endsWith(".woff2") || url.endsWith(".ttf") || url.endsWith(".otf") || contentType.contains("font/") -> "Fonts"
            url.endsWith(".mp4") || url.endsWith(".webm") || url.endsWith(".ogg") || contentType.contains("video/") -> "Video"
            url.endsWith(".mp3") || url.endsWith(".wav") || contentType.contains("audio/") -> "Audio"
            else -> "Text"
        }
    }

    private fun isRequestInScope(req: HistoryItemSummary, rules: List<ScopeRule>): Boolean {
        val enabledRules = rules.filter { it.enabled }
        if (enabledRules.isEmpty()) return true
        val inRules = enabledRules.filter { it.isInScope }; val outRules = enabledRules.filter { !it.isInScope }
        val matchesIn = if (inRules.isEmpty()) true else inRules.any { matchesRule(req, it) }
        val matchesOut = outRules.any { matchesRule(req, it) }
        return matchesIn && !matchesOut
    }

    private fun isRequestInScope(req: TheRepeatorRequest): Boolean {
        return isRequestInScope(toSummary(req), scopeRules.value)
    }

    private fun toSummary(req: TheRepeatorRequest) = HistoryItemSummary(
        id = req.id, method = req.method, url = req.url, host = req.host, path = req.path,
        statusCode = req.statusCode, protocol = req.protocol, timestamp = req.timestamp,
        isIntercepted = req.isIntercepted, bodyLength = req.bodyLength, headersJson = req.headersJson,
    )

    private fun matchesRule(req: HistoryItemSummary, rule: ScopeRule): Boolean = when (rule.type) { 
        ScopeRuleType.HOST -> req.host.contains(rule.pattern, ignoreCase = true)
        ScopeRuleType.PATH -> req.path.contains(rule.pattern, ignoreCase = true)
        ScopeRuleType.KEYWORD -> req.url.contains(rule.pattern, ignoreCase = true)
    }

    fun replaceVariables(input: String): String { var res = input; variables.value.forEach { res = res.replace("{{${it.name}}}", it.value) }; return res }
    fun addMatchReplaceRule(t: RuleType, m: String, r: String) { repository.addMatchReplaceRule(MatchReplaceRule(type = t, match = m, replace = r)) }
    fun removeMatchReplaceRule(id: String) { repository.removeMatchReplaceRule(id) }
    fun toggleMatchReplaceRule(id: String) { repository.toggleMatchReplaceRule(id) }
    private fun applyRules(input: String): String { var res = input; matchReplaceRules.value.filter { it.enabled }.forEach { res = res.replace(it.match, it.replace) }; return res }
    
    fun toggleHistoryFilter(filter: String) { val current = _historyFilters.value; _historyFilters.value = if (current.contains(filter)) current - filter else current + filter }
    fun updateHistorySearch(q: String) { _historySearchQuery.value = q }
    fun toggleOnlyInScope(enabled: Boolean) { _onlyShowInScope.value = enabled }
    fun clearHistory() { viewModelScope.launch { repository.clearHistory() } }

    fun toggleIntercept(enabled: Boolean) {
        _isInterceptEnabled.value = enabled
        val msg = if (enabled) "Interception Enabled" else "Interception Disabled"
        android.widget.Toast.makeText(getApplication(), msg, android.widget.Toast.LENGTH_SHORT).show()
        if (!enabled) { 
            _allInterceptedRequests.value.forEach { it.deferred.complete(it.originalResponse) }
            _allInterceptedRequests.value = emptyList()
            _interceptedRequest.value = null 
        }
    }

    fun forwardInterceptedRequest(rawRequest: String) {
        _interceptedRequest.value?.let { intercepted ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val finalRequest = applyRules(replaceVariables(rawRequest))
                    val parsed = try { 
                        parseRawRequest(finalRequest) 
                    } catch (e: Exception) {
                        val errHtml = "<html><body><h1>Request Parse Error</h1><p>${e.message}</p><pre>$finalRequest</pre></body></html>"
                        intercepted.deferred.complete(android.webkit.WebResourceResponse("text/html", "UTF-8", 400, "Bad Request", emptyMap(), ByteArrayInputStream(errHtml.toByteArray())))
                        return@launch
                    }
                    val response = performOkHttpCall(parsed.method, parsed.url, parsed.headers, parsed.body, shouldApplyRules = false)
                    intercepted.deferred.complete(response)
                } catch (e: Exception) { 
                    val errHtml = "<html><body><h1>Forwarding Error</h1><p>${e.message}</p></body></html>"
                    intercepted.deferred.complete(android.webkit.WebResourceResponse("text/html", "UTF-8", 502, "Forwarding Failed", emptyMap(), ByteArrayInputStream(errHtml.toByteArray())))
                } finally { 
                    nextInterceptedRequest() 
                }
            }
        }
    }

    fun nextInterceptedRequest() { 
        _allInterceptedRequests.update { queue ->
            if (queue.isNotEmpty()) {
                val nextQueue = queue.drop(1)
                _interceptedRequest.value = nextQueue.firstOrNull()
                nextQueue
            } else {
                _interceptedRequest.value = null
                emptyList()
            }
        }
    }

    fun forwardAllIntercepted() { 
        _allInterceptedRequests.value.forEach { it.deferred.complete(null) }
        _allInterceptedRequests.value = emptyList()
        _interceptedRequest.value = null 
    }

    fun dropInterceptedRequest(id: String? = null) {
        val targetId = id ?: _interceptedRequest.value?.id ?: return
        _allInterceptedRequests.update { queue ->
            val req = queue.find { it.id == targetId }
            // Complete with a specific 403 response to indicate it was dropped by user
            req?.deferred?.complete(
                android.webkit.WebResourceResponse(
                    "text/html", "UTF-8", 403, "Dropped by User", null, 
                    ByteArrayInputStream("Request Dropped".toByteArray()),
                )
            )
            val nextQueue = queue.filter { it.id != targetId }
            _interceptedRequest.value = nextQueue.firstOrNull()
            nextQueue
        }
    }

    fun connectWebSocket(u: String, reconnect: Boolean = false) {
        autoReconnect = reconnect; lastWsUrl = u; webSocket?.close(1000, null); _wsMessages.value = emptyList(); _wsState.value = "CONNECTING"
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { _wsState.value = "CONNECTED"; addWsMsg(MessageDirection.RECEIVED, "Connected") }
            override fun onMessage(webSocket: WebSocket, text: String) { addWsMsg(MessageDirection.RECEIVED, text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { addWsMsg(MessageDirection.RECEIVED, "Binary (${bytes.size} bytes)", binaryData = bytes.hex(), type = "Binary") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { _wsState.value = "CLOSING"; addWsMsg(MessageDirection.RECEIVED, "Closing") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _wsState.value = "DISCONNECTED"; if (autoReconnect) viewModelScope.launch { delay(3000.milliseconds); connectWebSocket(u, reconnect = true) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { _wsState.value = "ERROR"; addWsMsg(MessageDirection.RECEIVED, "Error: ${t.message}"); if (autoReconnect) viewModelScope.launch { delay(5000.milliseconds); connectWebSocket(u, reconnect = true) } }
        }
        webSocket = getOkHttpClient(u).newWebSocket(Request.Builder().url(u).build(), listener)
    }

    fun sendWsMessage(txt: String) { 
        val modTxt = applyRules(replaceVariables(txt))
        if (modTxt.startsWith("0x")) { try { val bytes = modTxt.substring(2).decodeHex(); webSocket?.send(bytes); addWsMsg(MessageDirection.SENT, "Binary: $modTxt", binaryData = bytes.hex(), type = "Binary") } catch (_: Exception) { webSocket?.send(modTxt); addWsMsg(MessageDirection.SENT, modTxt) } }
        else { webSocket?.send(modTxt); addWsMsg(MessageDirection.SENT, modTxt) }
    }
    private fun addWsMsg(d: MessageDirection, t: String, binaryData: String? = null, type: String = "Text") { _wsMessages.value += WebSocketMessage(direction = d, content = t, binaryData = binaryData, type = type) }

    fun updateComparerText1(text: String) { _comparerText1.value = text }
    fun updateComparerText2(text: String) { _comparerText2.value = text }

    fun sendToComparerSmart(text: String) {
        if (_comparerText1.value.isEmpty()) _comparerText1.value = text else _comparerText2.value = text
    }

    fun sendHistoryToComparerSmart(id: Int) {
        viewModelScope.launch {
            val req = repository.getRequestById(id)
            req?.let {
                val raw = getRawFromTheRepeatorRequest(it)
                sendToComparerSmart(raw)
            }
        }
    }

    fun loadHistoryDetails(id: Int) { viewModelScope.launch { _selectedHistoryRequestDetails.value = repository.getRequestById(id) } }
    fun clearSelectedHistoryDetail() { _selectedHistoryRequestDetails.value = null }
    fun deleteRequests(ids: List<Int>) { viewModelScope.launch { repository.deleteRequests(ids) } }

    fun getRawResponse(req: TheRepeatorRequest): String {
        val headersJson = req.responseHeadersJson ?: req.headersJson
        val headers = try { Json.decodeFromString<Map<String, String>>(headersJson) } catch (_: Exception) { emptyMap() }
        val sb = StringBuilder()
        sb.append("${req.protocol} ${req.statusCode}\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\n") }
        sb.append("\n")
        sb.append(req.body)
        return sb.toString()
    }

    fun getRawFromTheRepeatorRequest(req: TheRepeatorRequest): String {
        val headersJson = req.requestHeadersJson ?: req.headersJson
        val headers = try { Json.decodeFromString<Map<String, String>>(headersJson) } catch (_: Exception) { emptyMap() }
        val sb = StringBuilder(); sb.append("${req.method} ${req.path.ifEmpty { "/" }} ${req.protocol}\n")
        
        // Use a virtual protocol prefix in the Host header so parseRawRequest knows the original scheme
        val hostPrefix = if (req.url.startsWith("http://")) "http://" else ""
        sb.append("Host: $hostPrefix${req.host}\n")
        
        headers.forEach { (k, v) -> if (k.lowercase() != "host") sb.append("$k: $v\n") }
        sb.append("\n")
        sb.append(req.requestBody ?: "")
        return sb.toString()
    }

    fun sendRawRepeaterRequest(raw: String) {
        val idx = _selectedTabIndex.value
        val tabs = _repeaterTabs.value.toMutableList()
        if (idx in tabs.indices) {
            val tabId = tabs[idx].id; saveHistoryCheckpoint(tabId)
            val job = viewModelScope.launch(Dispatchers.IO) {
                updateTabLoading(tabId, loading = true); updateTabResponse(idx, "Sending...")
                val startTime = System.nanoTime()
                try {
                    _updatedRawRequest.value = null
                    val finalRequest = applyRules(replaceVariables(raw))
                    val parsed = parseRawRequest(finalRequest)
                    val builder = Request.Builder().url(parsed.url)
                    parsed.headers.forEach { (k, v) -> if ((k.lowercase() != "host") && (k.lowercase() != "content-length")) builder.addHeader(k, v) }
                    
                    val mediaType = parsed.headers["Content-Type"]?.toMediaTypeOrNull()
                    val body = if (parsed.body.isNotEmpty()) {
                        parsed.body.toRequestBody(mediaType)
                    } else if (parsed.method == "POST" || parsed.method == "PUT" || parsed.method == "PATCH") {
                        "".toRequestBody(mediaType)
                    } else {
                        null
                    }
                    
                    val call = try { getRepeaterHttpClient(parsed.url).newCall(builder.method(parsed.method, body).build()) } catch (e: IllegalArgumentException) { if (body != null) getRepeaterHttpClient(parsed.url).newCall(builder.method(parsed.method, null).build()) else throw e }
                    call.execute().use { resp ->
                        val (rb, bodyLen) = readResponseBodySafely(resp.body)
                        val contentType = resp.header("Content-Type") ?: "text/html"
                        val isBinary = isBinary(contentType)
                        
                        val finalRb = if (isBinary) "[Binary Data]" else rb
                        
                        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
                        val meta = ResponseMetadata(timing = duration, size = bodyLen, protocol = resp.protocol.toString(), tls = resp.handshake?.tlsVersion?.toString())
                        withContext(Dispatchers.Main) {
                            val currentTabs = _repeaterTabs.value.toMutableList()
                            if (idx in currentTabs.indices) {
                                currentTabs[idx] = currentTabs[idx].copy(response = "HTTP ${resp.code}\n${resp.headers}\n\n$finalRb", redirectUrl = if (resp.code in 300..399) resp.header("Location") else null, isLoading = false, metadata = meta)
                                _repeaterTabs.value = currentTabs
                                val req = TheRepeatorRequest(
                                    method = parsed.method, url = parsed.url, host = try { URL(parsed.url).host } catch(_: Exception) { "" }, 
                                    path = try { URL(parsed.url).path } catch(_: Exception) { "" }, statusCode = resp.code, protocol = resp.protocol.toString(), 
                                    body = finalRb, 
                                    headersJson = Json.encodeToString(resp.headers.toMap()), 
                                    timestamp = System.currentTimeMillis(),
                                    requestBody = parsed.body,
                                    requestHeadersJson = Json.encodeToString(parsed.headers),
                                    responseHeadersJson = Json.encodeToString(resp.headers.toMap()),
                                    bodyLength = bodyLen,
                                )
                                if (isRequestInScope(req)) repository.addRequest(req)
                            }
                        }
                    }
                } catch (e: Exception) { 
                    if (e !is CancellationException) { 
                        val errorMsg = e.message ?: "Unknown Error"
                        val isSslOrInsecure = errorMsg.contains("SSL") || errorMsg.contains("cert", ignoreCase = true) || errorMsg.contains("cleartext", ignoreCase = true)
                        
                        val finalMsg = if (isSslOrInsecure) {
                             "Error: Insecure Connection. Please use the Browser tab to authorize this domain.\n\nDetail: $errorMsg"
                        } else {
                            "Error: $errorMsg"
                        }
                        updateTabResponse(idx, finalMsg)
                        updateTabLoading(tabId, loading = false) 
                    } 
                }
            }
            repeaterJobs[tabId] = job
        }
    }

    fun followRedirect(tabId: String, followAll: Boolean = true) {
        redirectJob = viewModelScope.launch(Dispatchers.IO) {
            var currentReq: String? = null; var hasRedir = true; var count = 0
            while (hasRedir && (count < 10)) {
                count++; val tabs = _repeaterTabs.value.toMutableList(); val idx = tabs.indexOfFirst { it.id == tabId }; if (idx == -1) break
                val t = tabs[idx]; val nextUrl = t.redirectUrl ?: break
                val lastRaw = currentReq ?: t.rawRequest; val p = try { parseRawRequest(lastRaw) } catch (_: Exception) { break }
                var nu = nextUrl; if (!nu.startsWith("http")) { val ou = URL(p.url); nu = "${ou.protocol}://${ou.host}${if (ou.port != -1 && ou.port != 80 && ou.port != 443) ":${ou.port}" else ""}${if (nu.startsWith("/")) "" else "/"}$nu" }
                val uo = try { URL(nu) } catch (_: Exception) { break }
                val sc = if (t.response.startsWith("HTTP")) t.response.split(" ")[1].toIntOrNull() ?: 0 else 0
                val nm = when(sc) { 301, 302, 303 -> "GET"; 307, 308 -> p.method; else -> "GET" }
                val nr = "$nm ${uo.path.ifEmpty { "/" }}${if (uo.query != null) "?"+uo.query else ""} HTTP/1.1\nHost: ${uo.host}\n" + p.headers.filter { it.key.lowercase() !in listOf("host", "content-length") }.entries.joinToString("\n") { (k, v) -> "$k: $v" } + "\n\n" + (if (nm != "GET") p.body else "")
                currentReq = nr
                withContext(Dispatchers.Main) { val cTabs = _repeaterTabs.value.toMutableList(); val tIdx = cTabs.indexOfFirst { it.id == tabId }; if (tIdx != -1) { cTabs[tIdx] = cTabs[tIdx].copy(rawRequest = nr, redirectUrl = null, history = cTabs[tIdx].history + cTabs[tIdx].rawRequest, historyIndex = cTabs[tIdx].history.size); _repeaterTabs.value = cTabs; _updatedRawRequest.value = nr } }
                val res = sendRawRepeaterRequestSync(idx, nr) ?: break; hasRedir = res.second != null && followAll
            }
        }
    }

    private suspend fun sendRawRepeaterRequestSync(idx: Int, raw: String): Pair<String, String?>? {
        val tabId = _repeaterTabs.value[idx].id; updateTabLoading(tabId, loading = true)
        val startTime = System.nanoTime()
        return try {
            val fr = applyRules(replaceVariables(raw)); val p = parseRawRequest(fr); val builder = Request.Builder().url(p.url)
            p.headers.forEach { (k, v) -> if (k.lowercase() != "host" && k.lowercase() != "content-length") builder.addHeader(k, v) }
            val body = if (p.body.isNotEmpty()) p.body.toRequestBody(p.headers["Content-Type"]?.toMediaTypeOrNull()) else if (listOf("POST", "PUT", "PATCH").contains(p.method)) "".toRequestBody(p.headers["Content-Type"]?.toMediaTypeOrNull()) else null
            val call = try { getOkHttpClient(p.url).newCall(builder.method(p.method, body).build()) } catch (e: IllegalArgumentException) { if (body != null) getOkHttpClient(p.url).newCall(builder.method(p.method, null).build()) else throw e }
            call.execute().use { resp ->
                val (rb, bodyLen) = readResponseBodySafely(resp.body)
                val full = "HTTP ${resp.code}\n${resp.headers}\n\n$rb"; val loc = if (resp.code in 300..399) resp.header("Location") else null
                withContext(Dispatchers.Main) {
                    val cTabs = _repeaterTabs.value.toMutableList()
                    if (idx in cTabs.indices) {
                        cTabs[idx] = cTabs[idx].copy(response = full, redirectUrl = loc, isLoading = false, metadata = ResponseMetadata(timing = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime), size = bodyLen, protocol = resp.protocol.toString(), tls = resp.handshake?.tlsVersion?.toString()))
                        _repeaterTabs.value = cTabs
                        val req = TheRepeatorRequest(method = p.method, url = p.url, host = try { URL(p.url).host } catch(_: Exception) { "" }, path = try { URL(p.url).path } catch(_: Exception) { "" }, statusCode = resp.code, protocol = resp.protocol.toString(), body = rb, headersJson = "{}", timestamp = System.currentTimeMillis(), bodyLength = bodyLen)
                        if (isRequestInScope(req)) repository.addRequest(req)
                    }
                }
                Pair(full, loc)
            }
        } catch (e: Exception) { updateTabResponse(idx, "Error: ${e.message}"); updateTabLoading(_repeaterTabs.value[idx].id, loading = false); null }
    }

    private fun parseRawRequest(raw: String): ParsedRequest {
        if (raw.isBlank()) throw Exception("Empty request")
        
        // Handle both \r\n and \n
        val normalized = raw.replace("\r\n", "\n")
        val headerBodySplit = normalized.split("\n\n", limit = 2)
        val headerPart = headerBodySplit[0]
        val body = if (headerBodySplit.size > 1) headerBodySplit[1] else ""
        val lines = headerPart.lines().filter { it.isNotBlank() }
        
        if (lines.isEmpty()) throw Exception("Invalid request headers")
        val firstLineParts = lines[0].split(" ").filter { it.isNotBlank() }
        if (firstLineParts.size < 2) throw Exception("Invalid request line: ${lines[0]}")
        
        val method = firstLineParts[0].uppercase()
        val rawUrl = firstLineParts[1]
        
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val sep = line.indexOf(":")
            if (sep != -1) {
                val key = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim()
                headers[key] = value
            }
        }

        val hostHeader = headers["Host"] ?: headers["host"]
        
        // Smarter URL reconstruction
        var finalUrl = rawUrl
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            if (hostHeader != null) {
                // Support explicit protocol prefix in Host header as a hint
                var scheme = if (hostHeader.startsWith("http://")) "http" else if (hostHeader.startsWith("https://")) "https" else null
                val cleanHost = if (scheme != null) hostHeader.substring(scheme.length + 3) else hostHeader
                
                // Fallback to port detection or default to https
                if (scheme == null) {
                    scheme = if (cleanHost.contains(":80") || finalUrl.contains(":80")) "http" else "https"
                }

                // Check if rawUrl already contains the host (e.g. "google.com/")
                finalUrl = if (finalUrl.startsWith(cleanHost)) {
                    "$scheme://$finalUrl"
                } else {
                    val cleanPath = if (finalUrl.startsWith("/")) finalUrl else "/$finalUrl"
                    "$scheme://$cleanHost$cleanPath"
                }
            } else {
                throw Exception("No Host header found for relative URL: $rawUrl")
            }
        } else {
            // Absolute URL, but check if Host header was changed to a different domain
            if (hostHeader != null) {
                try {
                    val u = URL(finalUrl)
                    val newHost = if (hostHeader.contains(":")) hostHeader.substringBefore(":") else hostHeader
                    val newPort = if (hostHeader.contains(":")) hostHeader.substringAfter(":").toIntOrNull() ?: -1 else -1
                    if (u.host != newHost || u.port != newPort) {
                        val portStr = if (newPort != -1) ":$newPort" else ""
                        finalUrl = "${u.protocol}://$newHost$portStr${u.path}${if (u.query != null) "?" + u.query else ""}"
                    }
                } catch (_: Exception) {}
            }
        }
        
        return ParsedRequest(method, finalUrl, headers, body)
    }

    private fun applyTransform(input: Any, step: DecoderStep): Any {
        val str = when (input) { is ByteArray -> String(input, StandardCharsets.UTF_8); else -> input.toString() }
        val bytes = when (input) { is ByteArray -> input; else -> input.toString().toByteArray(StandardCharsets.UTF_8) }
        return when (step.type) {
            DecoderTransformType.BASE64_ENCODE -> Base64.encodeToString(bytes, Base64.NO_WRAP)
            DecoderTransformType.BASE64_DECODE -> try { Base64.decode(str, Base64.DEFAULT) } catch (e: Exception) { throw Exception("Invalid Base64: ${e.message}") }
            DecoderTransformType.URL_ENCODE -> URLEncoder.encode(str, "UTF-8")
            DecoderTransformType.URL_DECODE -> try { URLDecoder.decode(str, "UTF-8") } catch (e: Exception) { throw Exception("URL Decode Error: ${e.message}") }
            DecoderTransformType.HEX_ENCODE -> bytes.toByteString().hex()
            DecoderTransformType.HEX_DECODE -> try { str.replace(" ", "").replace("0x", "").decodeHex().toByteArray() } catch (e: Exception) { throw Exception("Invalid Hex: ${e.message}") }
            DecoderTransformType.GZIP -> compressGzip(bytes)
            DecoderTransformType.GUNZIP -> try { decompressGzip(bytes) } catch (e: Exception) { throw Exception("Gzip Error: ${e.message}") }
            DecoderTransformType.DEFLATE -> compressDeflate(bytes)
            DecoderTransformType.INFLATE -> try { decompressDeflate(bytes) } catch (e: Exception) { throw Exception("Deflate Error: ${e.message}") }
            DecoderTransformType.HTML_ENTITY_ENCODE -> encodeHtml(str)
            DecoderTransformType.HTML_ENTITY_DECODE -> android.text.Html.fromHtml(str, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            DecoderTransformType.UNICODE_DECODE -> decodeUnicode(str)
            DecoderTransformType.CHARSET_CONVERT -> str
            DecoderTransformType.JWT_DECODE -> decodeJwtInternal(str)
        }
    }

    private fun encodeHtml(str: String): String = str.map { c -> when (c) { '<' -> "&lt;"; '>' -> "&gt;"; '&' -> "&amp;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> if (c.code > 127) "&#${c.code};" else c.toString() } }.joinToString("")
    private fun compressGzip(data: ByteArray): ByteArray { val bos = ByteArrayOutputStream(); GZIPOutputStream(bos).use { it.write(data) }; return bos.toByteArray() }
    private fun decompressGzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).readBytes()
    private fun compressDeflate(data: ByteArray): ByteArray { val bos = ByteArrayOutputStream(); DeflaterOutputStream(bos).use { it.write(data) }; return bos.toByteArray() }
    private fun decompressDeflate(data: ByteArray): ByteArray = InflaterInputStream(ByteArrayInputStream(data)).readBytes()
    private fun decodeUnicode(input: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length && input[i + 1] == 'u') { try { val hex = input.substring(i + 2, i + 6); sb.append(hex.toInt(16).toChar()); i += 6 } catch (_: Exception) { sb.append(c); i++ } }
            else { sb.append(c); i++ }
        }
        return sb.toString()
    }
    private fun decodeJwtInternal(input: String): String {
        val parts = input.split(".")
        if (parts.size < 2) return "Invalid JWT format"
        return try {
            val header = String(Base64.decode(parts[0], Base64.URL_SAFE), StandardCharsets.UTF_8)
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8)
            val prettyHeader = prettifyBody(header)
            val prettyPayload = prettifyBody(payload)
            "Header: $prettyHeader\nPayload: $prettyPayload"
        } catch (e: Exception) { "JWT Decode Error: ${e.message}" }
    }

    fun addDecoderStep(type: DecoderTransformType) { _decoderSteps.value += DecoderStep(type = type) }
    fun removeDecoderStep(id: String) { _decoderSteps.value = _decoderSteps.value.filter { it.id != id } }
    
    fun encodeJwt(header: String, payload: String): String {
        return try {
            val h = Base64.encodeToString(header.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val p = Base64.encodeToString(payload.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            "$h.$p."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun moveDecoderStep(id: String, up: Boolean) {
        val current = _decoderSteps.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return
        val newIndex = if (up) index - 1 else index + 1
        if (newIndex in current.indices) {
            val step = current.removeAt(index)
            current.add(newIndex, step)
            _decoderSteps.value = current
        }
    }
    fun clearDecoder() { _decoderInput.value = ""; _decoderSteps.value = emptyList() }
    fun swapDecoder() { val output = decoderOutput.value; if (!output.startsWith("Error:") && output.isNotEmpty()) { _decoderInput.value = output; _decoderSteps.value = emptyList() } }
    fun updateDecoderInput(i: String) { _decoderInput.value = i }
    fun sendToDecoder(text: String) {
        _decoderInput.value = text
    }

    fun addVariable(n: String, v: String) { repository.addVariable(Variable(name = n, value = v)) }
    fun removeVariable(id: String) { repository.removeVariable(id) }
    fun addScopeRule(type: ScopeRuleType, pattern: String, isInScope: Boolean) { repository.addScopeRule(ScopeRule(type = type, pattern = pattern, isInScope = isInScope)) }
    fun removeScopeRule(id: String) { repository.removeScopeRule(id) }
    fun toggleScopeRule(id: String) { repository.toggleScopeRule(id) }

    fun addBrowserHistory(url: String, title: String) {
        viewModelScope.launch { repository.addBrowserHistory(BrowserHistoryItem(url = url, title = title)) }
    }

    fun updateTabLoading(id: String, loading: Boolean) { 
        _repeaterTabs.update { tabs ->
            tabs.map { if (it.id == id) it.copy(isLoading = loading) else it }
        }
    }
    fun updateTabResponse(i: Int, res: String) { 
        _repeaterTabs.update { tabs ->
            if (i in tabs.indices) {
                tabs.mapIndexed { idx, t -> if (idx == i) t.copy(response = res, redirectUrl = null) else t }
            } else tabs
        }
    }
}
