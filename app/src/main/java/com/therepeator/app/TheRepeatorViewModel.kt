package com.therepeator.app

import android.app.Application
import android.util.Base64
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
import java.util.*
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class TheRepeatorViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = TheRepeatorRepository(database.requestDao(), database.browserHistoryDao())

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

    private val _historySortAscending = MutableStateFlow(false)

    private val _onlyShowInScope = MutableStateFlow(false)
    val onlyShowInScope = _onlyShowInScope.asStateFlow()

    private val _isInterceptEnabled = MutableStateFlow(false)
    val isInterceptEnabled = _isInterceptEnabled.asStateFlow()

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

    val browserHistory: StateFlow<List<BrowserHistoryItem>> = repository.browserHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearBrowserHistory() {
        viewModelScope.launch { repository.clearBrowserHistory() }
    }

    @OptIn(FlowPreview::class)
    val history: StateFlow<List<HistoryItemSummary>> = combine(
        repository.history,
        _historyFilters,
        _historySearchQuery.debounce(300),
        _onlyShowInScope,
        combine(_historySortField, _historySortAscending) { f, a -> Pair(f, a) }
    ) { list, filters, query, inScopeOnly, sortInfo ->
        val (sortField, ascending) = sortInfo
        var filteredList = if (filters.isEmpty()) list else list.filter { req -> filters.contains(inferContentType(req)) }
        if (inScopeOnly) filteredList = filteredList.filter { isRequestInScope(it) }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            filteredList = filteredList.filter { req ->
                req.host.lowercase().contains(q) || 
                req.path.lowercase().contains(q) || 
                req.method.lowercase().contains(q) || 
                req.statusCode.toString().contains(q) ||
                req.url.lowercase().contains(q) ||
                req.id.toString() == q
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
            repository.clearHistory()
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

    private val _repeaterTabs = MutableStateFlow(listOf(
        RepeaterTabState(id = UUID.randomUUID().toString(), name = "1", rawRequest = "GET / HTTP/1.1\nHost: google.com\nConnection: close\n\n")
    ))
    val repeaterTabs: StateFlow<List<RepeaterTabState>> = _repeaterTabs.asStateFlow()

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    private val _updatedRawRequest = MutableStateFlow<String?>(null)
    val updatedRawRequest: StateFlow<String?> = _updatedRawRequest.asStateFlow()

    private val _decodedSelection = MutableStateFlow<String?>(null)

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

    val filteredIntruderResults: StateFlow<List<IntruderResult>> = _intruderState.map { state ->
        state.results.filter { res ->
            val f = state.filters
            (f.status.isEmpty() || res.statusCode.toString().contains(f.status)) && 
            (f.minLength == null || res.length >= f.minLength) && 
            (f.maxLength == null || res.length <= f.maxLength) && 
            (f.regex.isEmpty() || res.response.contains(f.regex.toRegex(RegexOption.IGNORE_CASE)))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _wsMessages = MutableStateFlow<List<WebSocketMessage>>(emptyList())

    private val _wsState = MutableStateFlow("DISCONNECTED")
    val wsState = _wsState.asStateFlow()
    
    private val _wsSearchQuery = MutableStateFlow("")
    fun updateWsSearch(q: String) { _wsSearchQuery.value = q }

    @OptIn(FlowPreview::class)
    val filteredWsMessages = combine(_wsMessages, _wsSearchQuery.debounce(300)) { messages, query ->
        if (query.isBlank()) messages else messages.filter { it.content.contains(query, ignoreCase = true) || (it.binaryData?.contains(query, ignoreCase = true) == true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _browserSearchQuery = MutableStateFlow("")
    fun updateBrowserSearch(q: String) { _browserSearchQuery.value = q }
    
    @OptIn(FlowPreview::class)
    val browserSuggestions = combine(browserHistory, _browserSearchQuery.debounce(200)) { history, query ->
        if (query.isBlank()) emptyList()
        else history.filter { it.url.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }.take(5)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var webSocket: WebSocket? = null
    private var autoReconnect = false
    private var lastWsUrl: String? = null
    
    private val okHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .hostnameVerifier { _, _ -> true }
        .sslSocketFactory(createUnsafeSslSocketFactory(), object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        .build()

    private val intruderOkHttpClient = okHttpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private fun createUnsafeSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    private val repeaterJobs = ConcurrentHashMap<String, Job>()
    private var redirectJob: Job? = null

    fun selectTab(index: Int) { _selectedTabIndex.value = index }
    fun addEmptyRepeaterTab() {
        val newTab = RepeaterTabState(id = UUID.randomUUID().toString(), name = (_repeaterTabs.value.size + 1).toString(), rawRequest = "GET / HTTP/1.1\nHost: example.com\nConnection: close\n\n")
        _repeaterTabs.value = _repeaterTabs.value + newTab
        _selectedTabIndex.value = _repeaterTabs.value.size - 1
    }

    fun addRepeaterTab(name: String, raw: String) {
        val newTab = RepeaterTabState(id = UUID.randomUUID().toString(), name = name, rawRequest = raw)
        _repeaterTabs.value = _repeaterTabs.value + newTab
        _selectedTabIndex.value = _repeaterTabs.value.size - 1
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
        if (stack.isEmpty() || stack.peek() != currentRaw) stack.push(currentRaw)
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
        updateTabLoading(tabId, false) 
    }

    private val jsonPretty = Json { prettyPrint = true; ignoreUnknownKeys = true }

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
        if (body.isBlank() || body.length > 500_000) return body
        val tb = body.trim()
        return try {
            if (tb.startsWith("{") || tb.startsWith("[")) {
                val element = jsonPretty.parseToJsonElement(tb)
                jsonPretty.encodeToString(element)
            } else if (tb.startsWith("<")) {
                prettifyXmlHtml(tb)
            } else {
                val firstBrace = tb.indexOfFirst { it == '{' || it == '[' }
                if (firstBrace != -1) {
                    val possibleJson = tb.substring(firstBrace)
                    val jsonElement = jsonPretty.parseToJsonElement(possibleJson)
                    tb.substring(0, firstBrace) + "\n" + jsonPretty.encodeToString(jsonElement)
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

    fun sendToIntruder(req: String) { _intruderState.value = _intruderState.value.copy(templateRequest = req) }
    fun updateIntruderPayloads(p: String) { _intruderState.value = _intruderState.value.copy(payloads = p.lines().filter { it.isNotBlank() }) }
    fun appendIntruderPayloads(p: String) {
        val current = _intruderState.value.payloads
        val added = p.lines().filter { it.isNotBlank() }
        _intruderState.value = _intruderState.value.copy(payloads = (current + added).distinct())
    }
    fun clearIntruderPayloads() { _intruderState.value = _intruderState.value.copy(payloads = emptyList()) }

    fun setIntruderSettings(c: Int, r: Long, t: Int, rd: Boolean, eu: Boolean, db: Boolean, rps: Int) {
        _intruderState.value = _intruderState.value.copy(concurrency = c, rateLimitMillis = r, timeoutSeconds = t, randomDelay = rd, encodeUrl = eu, decodeBase64 = db, rps = rps)
    }

    fun setIntruderFilters(status: String, min: Int?, max: Int?, regex: String) {
        _intruderState.value = _intruderState.value.copy(filters = IntruderFilters(status, min, max, regex))
    }

    fun cancelIntruderAttack() { intruderJob?.cancel(); _intruderState.value = _intruderState.value.copy(isRunning = false) }

    fun runIntruderAttack() {
        intruderJob = viewModelScope.launch(Dispatchers.IO) {
            val state = _intruderState.value
            _intruderState.value = state.copy(isRunning = true, results = emptyList())
            val template = state.templateRequest
            val payloads = state.payloads
            val resultsL = Collections.synchronizedList(mutableListOf<IntruderResult>())
            
            val semaphore = Semaphore(state.concurrency)
            val jobs = payloads.map { p -> launch {
                semaphore.withPermit {
                    if (_intruderState.value.isPaused) { while(_intruderState.value.isPaused) delay(500) }
                    var payload = p
                    if (state.decodeBase64) { try { payload = String(Base64.decode(payload, Base64.DEFAULT)) } catch (_: Exception) {} }
                    if (state.encodeUrl) { payload = java.net.URLEncoder.encode(payload, "UTF-8") }
                    val rawP = payload
                    val finalReq = if (template.contains("§")) {
                        val pairRegex = "§.*?§".toRegex()
                        if (pairRegex.containsMatchIn(template)) {
                            template.replace(pairRegex, rawP)
                        } else {
                            template.replace("§", rawP)
                        }
                    } else {
                        template
                    }
                    val startTime = System.currentTimeMillis()
                    try {
                        val parsed = parseRawRequest(finalReq)
                        val builder = Request.Builder().url(parsed.url)
                        parsed.headers.forEach { (k, v) -> if (k.lowercase() != "host" && k.lowercase() != "content-length") builder.addHeader(k, v) }
                        val mediaType = parsed.headers["Content-Type"]?.toMediaTypeOrNull()
                        val reqBody = if (parsed.body.isNotEmpty()) parsed.body.toRequestBody(mediaType) else if (listOf("POST", "PUT", "PATCH").contains(parsed.method)) "".toRequestBody(mediaType) else null
                        intruderOkHttpClient.newCall(builder.method(parsed.method, reqBody).build()).execute().use { resp ->
                            val rb = resp.body?.string() ?: ""
                            resultsL.add(IntruderResult(payload = rawP, statusCode = resp.code, length = rb.length, responseTime = System.currentTimeMillis() - startTime, response = "HTTP ${resp.code}\n${resp.headers}\n$rb"))
                        }
                    } catch (e: Exception) { resultsL.add(IntruderResult(payload = rawP, statusCode = 0, length = 0, responseTime = System.currentTimeMillis() - startTime, response = "Error: ${e.message}")) }
                    _intruderState.value = _intruderState.value.copy(results = resultsL.toList())
                } }
            }
            jobs.joinAll()
            _intruderState.value = _intruderState.value.copy(isRunning = false)
        }
    }
    fun pauseAttack() { _intruderState.value = _intruderState.value.copy(isPaused = true) }
    fun resumeAttack() { _intruderState.value = _intruderState.value.copy(isPaused = false) }

    fun selectIntruderResult(res: IntruderResult?) { _selectedIntruderResult.value = res }

    fun sortIntruderResults(field: String) {
        val s = _intruderState.value
        val sorted = when (field) { 
            "Status" -> s.results.sortedBy { it.statusCode }
            "Length" -> s.results.sortedBy { it.length }
            "Time" -> s.results.sortedByDescending { it.timestamp }
            "Duration" -> s.results.sortedBy { it.responseTime }
            "Payload" -> s.results.sortedBy { it.payload }
            else -> s.results.sortedByDescending { it.timestamp } 
        }
        _intruderState.value = s.copy(results = sorted)
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
            finalHeaders.forEach { (k, v) -> if (k.lowercase() != "host" && k.lowercase() != "content-length") builder.addHeader(k, v) }
            
            val mediaType = finalHeaders["Content-Type"]?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            val requestBody = if (finalBody != null) {
                finalBody.toRequestBody(mediaType)
            } else if (finalMethod == "POST" || finalMethod == "PUT" || finalMethod == "PATCH") {
                "".toRequestBody(mediaType)
            } else {
                null
            }
            
            val call = okHttpClient.newCall(builder.method(finalMethod, requestBody).build())
            val resp = withContext(Dispatchers.IO) { call.execute() }
            
            val rawBytes = withContext(Dispatchers.IO) { resp.body?.bytes() } ?: byteArrayOf()
            val contentType = resp.header("Content-Type") ?: "text/html"
            val isBinary = isBinary(contentType)
            val rb = if (isBinary) "[Binary Data]" else String(rawBytes, StandardCharsets.UTF_8)
            
            val respHeaders = mutableMapOf<String, String>()
            resp.headers.forEach { (k, v) -> respHeaders[k] = v }
            
            val cookieManager = android.webkit.CookieManager.getInstance()
            resp.headers("Set-Cookie").forEach { cookieManager.setCookie(finalUrl, it) }
            
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
                bodyLength = rawBytes.size
            )
            if (isRequestInScope(req)) repository.addRequest(req)
            
            android.webkit.WebResourceResponse(
                contentType.substringBefore(";"), 
                resp.header("Content-Encoding"), 
                resp.code, 
                resp.message.ifEmpty { "OK" }, 
                respHeaders, 
                java.io.ByteArrayInputStream(rawBytes)
            )
        } catch (_: Exception) { null }
    }

    private fun isBinary(contentType: String?): Boolean {
        val ct = contentType?.lowercase() ?: return false
        return ct.contains("image/") || ct.contains("video/") || ct.contains("audio/") || 
               ct.contains("application/octet-stream") || ct.contains("application/zip") || 
               ct.contains("application/pdf") || ct.contains("application/wasm")
    }

    suspend fun handleBrowserTraffic(method: String, url: String, headers: Map<String, String>): android.webkit.WebResourceResponse? {
        val host = try { URL(url).host } catch(e: Exception) { "" }
        val path = try { URL(url).path.ifEmpty { "/" } } catch(e: Exception) { "/" }
        
        val isInScope = isRequestInScope(HistoryItemSummary(
            id = 0, method = method, url = url, host = host, path = path,
            statusCode = 0, protocol = "HTTP/1.1", timestamp = System.currentTimeMillis(),
            isIntercepted = false, bodyLength = 0, headersJson = "{}"
        ))

        if (_isInterceptEnabled.value && isInScope) {
            val deferred = CompletableDeferred<android.webkit.WebResourceResponse?>()
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            val allHeaders = headers.toMutableMap()
            if (!cookies.isNullOrEmpty() && !allHeaders.containsKey("Cookie") && !allHeaders.containsKey("cookie")) {
                allHeaders["Cookie"] = cookies
            }
            
            val raw = applyRules(replaceVariables("$method ${path.ifEmpty { "/" }} HTTP/1.1\nHost: $host\n" + allHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n"))
            val intercepted = InterceptedBrowserRequest(id = UUID.randomUUID().toString(), method = method, url = url, headers = allHeaders, rawRequest = raw, deferred = deferred)
            
            interceptionChannel.send(intercepted)
            
            return try {
                withTimeout(60000) { deferred.await() }
            } catch (e: Exception) {
                dropInterceptedRequest(intercepted.id)
                null
            }
        } else {
            return performOkHttpCall(method, url, headers)
        }
    }

    fun inferContentType(req: HistoryItemSummary): String {
        val url = req.url.lowercase().substringBefore("?")
        val headers = try { Json.decodeFromString<Map<String, String>>(req.headersJson) } catch (e: Exception) { emptyMap() }
        val contentType = headers.entries.find { it.key.equals("content-type", true) }?.value?.lowercase() ?: ""
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

    private fun isRequestInScope(req: HistoryItemSummary): Boolean {
        val rules = scopeRules.value.filter { it.enabled }
        if (rules.isEmpty()) return true
        val inRules = rules.filter { it.isInScope }; val outRules = rules.filter { !it.isInScope }
        val matchesIn = if (inRules.isEmpty()) true else inRules.any { matchesRule(req, it) }
        val matchesOut = outRules.any { matchesRule(req, it) }
        return matchesIn && !matchesOut
    }

    private fun isRequestInScope(req: TheRepeatorRequest): Boolean {
        return isRequestInScope(toSummary(req))
    }

    private fun toSummary(req: TheRepeatorRequest) = HistoryItemSummary(
        id = req.id, method = req.method, url = req.url, host = req.host, path = req.path,
        statusCode = req.statusCode, protocol = req.protocol, timestamp = req.timestamp,
        isIntercepted = req.isIntercepted, bodyLength = req.bodyLength, headersJson = req.headersJson
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
    fun tryDecodeBase64(input: String) { try { val d = Base64.decode(input, Base64.DEFAULT); val str = String(d, StandardCharsets.UTF_8); _decodedSelection.value = str } catch (e: Exception) { _decodedSelection.value = null } }
    fun clearHistory() { viewModelScope.launch { repository.clearHistory() } }

    fun toggleIntercept(enabled: Boolean) {
        _isInterceptEnabled.value = enabled
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
                    val parsed = parseRawRequest(finalRequest)
                    val response = performOkHttpCall(parsed.method, parsed.url, parsed.headers, parsed.body, shouldApplyRules = false)
                    intercepted.deferred.complete(response)
                } catch (e: Exception) { intercepted.deferred.complete(null) } finally { nextInterceptedRequest() }
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
            req?.deferred?.complete(null)
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
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _wsState.value = "DISCONNECTED"; if (autoReconnect) viewModelScope.launch { delay(3000L); connectWebSocket(u, true) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { _wsState.value = "ERROR"; addWsMsg(MessageDirection.RECEIVED, "Error: ${t.message}"); if (autoReconnect) viewModelScope.launch { delay(5000L); connectWebSocket(u, true) } }
        }
        webSocket = okHttpClient.newWebSocket(Request.Builder().url(u).build(), listener)
    }

    fun sendWsMessage(txt: String) { 
        val modTxt = applyRules(replaceVariables(txt))
        if (modTxt.startsWith("0x")) { try { val bytes = modTxt.substring(2).decodeHex(); webSocket?.send(bytes); addWsMsg(MessageDirection.SENT, "Binary: $modTxt", binaryData = bytes.hex(), type = "Binary") } catch (e: Exception) { webSocket?.send(modTxt); addWsMsg(MessageDirection.SENT, modTxt) } }
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
        val headers = try { Json.decodeFromString<Map<String, String>>(headersJson) } catch (e: Exception) { emptyMap() }
        val sb = StringBuilder()
        sb.append("${req.protocol} ${req.statusCode}\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\n") }
        sb.append("\n")
        sb.append(req.body)
        return sb.toString()
    }

    fun getRawFromTheRepeatorRequest(req: TheRepeatorRequest): String {
        val headersJson = req.requestHeadersJson ?: req.headersJson
        val headers = try { Json.decodeFromString<Map<String, String>>(headersJson) } catch (e: Exception) { emptyMap() }
        val sb = StringBuilder(); sb.append("${req.method} ${req.path.ifEmpty { "/" }} ${req.protocol}\n"); sb.append("Host: ${req.host}\n")
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
                updateTabLoading(tabId, true); updateTabResponse(idx, "Sending...")
                val startTime = System.nanoTime()
                try {
                    _updatedRawRequest.value = null
                    val finalRequest = applyRules(replaceVariables(raw))
                    val parsed = parseRawRequest(finalRequest)
                    val builder = Request.Builder().url(parsed.url)
                    parsed.headers.forEach { (k, v) -> if (k.lowercase() != "host" && k.lowercase() != "content-length") builder.addHeader(k, v) }
                    
                    val mediaType = parsed.headers["Content-Type"]?.toMediaTypeOrNull()
                    val body = if (parsed.body.isNotEmpty()) {
                        parsed.body.toRequestBody(mediaType)
                    } else if (parsed.method == "POST" || parsed.method == "PUT" || parsed.method == "PATCH") {
                        "".toRequestBody(mediaType)
                    } else {
                        null
                    }
                    
                    val call = try { okHttpClient.newCall(builder.method(parsed.method, body).build()) } catch (e: IllegalArgumentException) { if (body != null) okHttpClient.newCall(builder.method(parsed.method, null).build()) else throw e }
                    call.execute().use { resp ->
                        val rawBytes = resp.body?.bytes() ?: byteArrayOf()
                        val contentType = resp.header("Content-Type") ?: "text/html"
                        val rb = if (isBinary(contentType)) "[Binary Data]" else String(rawBytes, StandardCharsets.UTF_8)
                        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
                        val meta = ResponseMetadata(timing = duration, size = rawBytes.size, protocol = resp.protocol.toString(), tls = resp.handshake?.tlsVersion?.toString())
                        withContext(Dispatchers.Main) {
                            val currentTabs = _repeaterTabs.value.toMutableList()
                            if (idx in currentTabs.indices) {
                                currentTabs[idx] = currentTabs[idx].copy(response = "HTTP ${resp.code}\n${resp.headers}\n$rb", redirectUrl = if (resp.code in 300..399) resp.header("Location") else null, isLoading = false, metadata = meta)
                                _repeaterTabs.value = currentTabs
                                val req = TheRepeatorRequest(
                                    method = parsed.method, url = parsed.url, host = try { URL(parsed.url).host } catch(e: Exception) { "" }, 
                                    path = try { URL(parsed.url).path } catch(e: Exception) { "" }, statusCode = resp.code, protocol = resp.protocol.toString(), 
                                    body = rb, 
                                    headersJson = Json.encodeToString(resp.headers.toMap()), 
                                    timestamp = System.currentTimeMillis(),
                                    requestBody = parsed.body,
                                    requestHeadersJson = Json.encodeToString(parsed.headers),
                                    responseHeadersJson = Json.encodeToString(resp.headers.toMap()),
                                    bodyLength = rawBytes.size
                                )
                                if (isRequestInScope(req)) repository.addRequest(req)
                            }
                        }
                    }
                } catch (e: Exception) { if (e !is CancellationException) { updateTabResponse(idx, "Error: ${e.message}"); updateTabLoading(tabId, false) } }
            }
            repeaterJobs[tabId] = job
        }
    }

    fun followRedirect(tabId: String, followAll: Boolean = true) {
        redirectJob = viewModelScope.launch(Dispatchers.IO) {
            var currentReq: String? = null; var hasRedir = true; var count = 0
            while (hasRedir && count < 10) {
                count++; val tabs = _repeaterTabs.value.toMutableList(); val idx = tabs.indexOfFirst { it.id == tabId }; if (idx == -1) break
                val t = tabs[idx]; val nextUrl = t.redirectUrl ?: break
                val lastRaw = currentReq ?: t.rawRequest; val p = try { parseRawRequest(lastRaw) } catch (e: Exception) { break }
                var nu = nextUrl; if (!nu.startsWith("http")) { val ou = URL(p.url); nu = "${ou.protocol}://${ou.host}${if (ou.port != -1 && ou.port != 80 && ou.port != 443) ":${ou.port}" else ""}${if (nu.startsWith("/")) "" else "/"}$nu" }
                val uo = try { URL(nu) } catch (e: Exception) { break }
                val sc = if (t.response.startsWith("HTTP")) t.response.split(" ")[1].toIntOrNull() ?: 0 else 0
                val nm = when(sc) { 301, 302, 303 -> "GET"; 307, 308 -> p.method; else -> "GET" }
                val nr = "$nm ${uo.path.ifEmpty { "/" }}${if (uo.query != null) "?"+uo.query else ""} HTTP/1.1\nHost: ${uo.host}\n" + p.headers.filter { it.key.lowercase() !in listOf("host", "content-length") }.entries.joinToString("\n") { (k, v) -> "${k}: ${v}" } + "\n\n" + (if (nm != "GET") p.body else "")
                currentReq = nr
                withContext(Dispatchers.Main) { val cTabs = _repeaterTabs.value.toMutableList(); val tIdx = cTabs.indexOfFirst { it.id == tabId }; if (tIdx != -1) { cTabs[tIdx] = cTabs[tIdx].copy(rawRequest = nr, redirectUrl = null, history = cTabs[tIdx].history + cTabs[tIdx].rawRequest, historyIndex = cTabs[tIdx].history.size); _repeaterTabs.value = cTabs; _updatedRawRequest.value = nr } }
                val res = sendRawRepeaterRequestSync(idx, nr) ?: break; hasRedir = res.second != null && followAll
            }
        }
    }

    private suspend fun sendRawRepeaterRequestSync(idx: Int, raw: String): Pair<String, String?>? {
        val tabId = _repeaterTabs.value[idx].id; updateTabLoading(tabId, true)
        val startTime = System.nanoTime()
        return try {
            val fr = applyRules(replaceVariables(raw)); val p = parseRawRequest(fr); val builder = Request.Builder().url(p.url)
            p.headers.forEach { (k, v) -> if (k.lowercase() != "host" && k.lowercase() != "content-length") builder.addHeader(k, v) }
            val body = if (p.body.isNotEmpty()) p.body.toRequestBody(p.headers["Content-Type"]?.toMediaTypeOrNull()) else if (listOf("POST", "PUT", "PATCH").contains(p.method)) "".toRequestBody(p.headers["Content-Type"]?.toMediaTypeOrNull()) else null
            val call = try { okHttpClient.newCall(builder.method(p.method, body).build()) } catch (e: IllegalArgumentException) { if (body != null) okHttpClient.newCall(builder.method(p.method, null).build()) else throw e }
            call.execute().use { resp ->
                val rb = resp.body?.string() ?: ""; val full = "HTTP ${resp.code}\n${resp.headers}\n$rb"; val loc = if (resp.code in 300..399) resp.header("Location") else null
                withContext(Dispatchers.Main) {
                    val cTabs = _repeaterTabs.value.toMutableList()
                    if (idx in cTabs.indices) {
                        cTabs[idx] = cTabs[idx].copy(response = full, redirectUrl = loc, isLoading = false, metadata = ResponseMetadata(timing = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime), size = rb.length, protocol = resp.protocol.toString(), tls = resp.handshake?.tlsVersion?.toString()))
                        _repeaterTabs.value = cTabs
                        val req = TheRepeatorRequest(method = p.method, url = p.url, host = try { URL(p.url).host } catch(e: Exception) { "" }, path = try { URL(p.url).path } catch(e: Exception) { "" }, statusCode = resp.code, protocol = resp.protocol.toString(), body = rb, headersJson = "{}", timestamp = System.currentTimeMillis(), bodyLength = rb.length)
                        if (isRequestInScope(req)) repository.addRequest(req)
                    }
                }
                Pair(full, loc)
            }
        } catch (e: Exception) { updateTabResponse(idx, "Error: ${e.message}"); updateTabLoading(_repeaterTabs.value[idx].id, false); null }
    }

    private fun parseRawRequest(raw: String): ParsedRequest {
        if (raw.isBlank()) throw Exception("Empty request")
        val headerBodySplit = raw.split("\n\n", limit = 2)
        val headerPart = headerBodySplit[0]
        val body = if (headerBodySplit.size > 1) headerBodySplit[1] else ""
        val lines = headerPart.lines()
        if (lines.isEmpty() || lines[0].isBlank()) throw Exception("Invalid request headers")
        val firstLineParts = lines[0].split(" ")
        if (firstLineParts.size < 2) throw Exception("Invalid request line: ${lines[0]}")
        val method = firstLineParts[0].uppercase()
        var url = firstLineParts[1]
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val sep = line.indexOf(":")
            if (sep != -1) headers[line.substring(0, sep).trim()] = line.substring(sep + 1).trim()
        }
        if (!url.startsWith("http")) {
            val host = headers["Host"] ?: headers["host"] ?: throw Exception("No Host header found and URL is relative")
            val isExplicitHttp = url.contains(":80") || host.contains(":80")
            val scheme = if (isExplicitHttp) "http" else "https"
            url = "$scheme://$host$url"
        }
        return ParsedRequest(method, url, headers, body)
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
            if (c == '\\' && i + 1 < input.length && input[i + 1] == 'u') { try { val hex = input.substring(i + 2, i + 6); sb.append(hex.toInt(16).toChar()); i += 6 } catch (e: Exception) { sb.append(c); i++ } }
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
            "Header: $header\nPayload: $payload"
        } catch (e: Exception) { "JWT Decode Error: ${e.message}" }
    }

    fun addDecoderStep(type: DecoderTransformType) { _decoderSteps.value += DecoderStep(type = type) }
    fun removeDecoderStep(id: String) { _decoderSteps.value = _decoderSteps.value.filter { it.id != id } }
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
