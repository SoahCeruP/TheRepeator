package com.therepeator.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: TheRepeatorViewModel = viewModel()
            TheRepeatorTheme { TheRepeatorAppScreen(viewModel) }
        }
    }
}

private fun formatSize(size: Int): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
    }
}

@Composable
fun TheRepeatorAppScreen(vm: TheRepeatorViewModel) {
    val context = LocalContext.current
    val repeaterTabs by vm.repeaterTabs.collectAsState()
    val selectedTabIndex by vm.selectedTabIndex.collectAsState()
    val redirectionUpdate by vm.updatedRawRequest.collectAsState()
    val decoderInput by vm.decoderInput.collectAsState()
    val decoderOutput by vm.decoderOutput.collectAsState()
    val matchReplaceRules by vm.matchReplaceRules.collectAsState()
    val intruderState by vm.intruderState.collectAsState()
    val selectedIntruderResult by vm.selectedIntruderResult.collectAsState()
    val variables by vm.variables.collectAsState()
    val scopeRules by vm.scopeRules.collectAsState()
    val filteredResults by vm.filteredIntruderResults.collectAsState()
    val history by vm.history.collectAsState()
    val historyFilters by vm.historyFilters.collectAsState()
    val onlyShowInScope by vm.onlyShowInScope.collectAsState()
    val isInterceptEnabled by vm.isInterceptEnabled.collectAsState()
    val interceptedRequest by vm.interceptedRequest.collectAsState()
    val allIntercepted by vm.allInterceptedRequests.collectAsState()
    val historySearchQuery by vm.historySearchQuery.collectAsState()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showBrowserHistory by remember { mutableStateOf(false) }
    var sendToDecoderText by remember { mutableStateOf<String?>(null) }
    var rawRepeaterValue by remember { mutableStateOf(TextFieldValue("")) }
    var selectedHistorySummary by remember { mutableStateOf<HistoryItemSummary?>(null) }
    var showHistoryDetail by remember { mutableStateOf(false) }
    val selectedHistoryDetails by vm.selectedHistoryRequestDetails.collectAsState()
    var loadProgress by remember { mutableIntStateOf(0) }

    BackHandler(enabled = showHistoryDetail) {
        showHistoryDetail = false
        vm.clearSelectedHistoryDetail()
    }

    val browserWebView = remember {
        WebView(context).apply {
            settings.apply {
                @SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            }
            webChromeClient = object : android.webkit.WebChromeClient() { 
                override fun onProgressChanged(view: WebView?, newProgress: Int) { 
                    loadProgress = newProgress 
                } 
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    url?.let { 
                        if (it != "about:blank") {
                            vm.addBrowserHistory(it, view?.title ?: "")
                        }
                    }
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    return request?.let { req -> 
                        kotlinx.coroutines.runBlocking { vm.handleBrowserTraffic(req.method, req.url.toString(), req.requestHeaders) } 
                    } ?: super.shouldInterceptRequest(view, request)
                }
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed() 
                }
            }
            loadUrl("about:blank")
        }
    }

    LaunchedEffect(selectedTabIndex, repeaterTabs) {
        repeaterTabs.getOrNull(selectedTabIndex)?.let { if (rawRepeaterValue.text != it.rawRequest) rawRepeaterValue = TextFieldValue(it.rawRequest) }
    }
    LaunchedEffect(redirectionUpdate) { redirectionUpdate?.let { rawRepeaterValue = TextFieldValue(it) } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen, 
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp).fillMaxHeight(),
                drawerContainerColor = Color(0xFF0F172A),
                content = {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
                            Icon(
                                painter = painterResource(id = R.mipmap.ic_app_logo),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("TheRepeator", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        
                        Text("Navigation", color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                        
                        NavigationDrawerItem(
                            label = { Text("Browser") },
                            selected = selectedTab == 5,
                            onClick = { selectedTab = 5; scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.Default.Language, null) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, selectedContainerColor = Color(0xFF7C3AED).copy(alpha = 0.2f), unselectedTextColor = Color(0xFF94A3B8), selectedTextColor = Color.White, selectedIconColor = Color(0xFF7C3AED))
                        )
                        NavigationDrawerItem(
                            label = { Text("Requests") },
                            selected = selectedTab == 4,
                            onClick = { selectedTab = 4; scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, selectedContainerColor = Color(0xFF7C3AED).copy(alpha = 0.2f), unselectedTextColor = Color(0xFF94A3B8), selectedTextColor = Color.White, selectedIconColor = Color(0xFF7C3AED))
                        )
                        NavigationDrawerItem(
                            label = { Text("Repeater") },
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0; scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.AutoMirrored.Filled.Send, null) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, selectedContainerColor = Color(0xFF7C3AED).copy(alpha = 0.2f), unselectedTextColor = Color(0xFF94A3B8), selectedTextColor = Color.White, selectedIconColor = Color(0xFF7C3AED))
                        )
                        NavigationDrawerItem(
                            label = { Text("Intruder") },
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1; scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.Default.Bolt, null) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, selectedContainerColor = Color(0xFF7C3AED).copy(alpha = 0.2f), unselectedTextColor = Color(0xFF94A3B8), selectedTextColor = Color.White, selectedIconColor = Color(0xFF7C3AED))
                        )
                        NavigationDrawerItem(
                            label = { Text("Comparer") },
                            selected = selectedTab == 6,
                            onClick = { selectedTab = 6; scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.Default.Compare, null) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, selectedContainerColor = Color(0xFF7C3AED).copy(alpha = 0.2f), unselectedTextColor = Color(0xFF94A3B8), selectedTextColor = Color.White, selectedIconColor = Color(0xFF7C3AED))
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        Text("Utilities", color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                        
                        NavigationDrawerItem(
                            label = { Text("Decoder / Encoder") },
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2; scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.Default.Transform, null) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, selectedContainerColor = Color(0xFF7C3AED).copy(alpha = 0.2f), unselectedTextColor = Color(0xFF94A3B8), selectedTextColor = Color.White, selectedIconColor = Color(0xFF7C3AED))
                        )
                        NavigationDrawerItem(
                            label = { Text("WebSocket") },
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3; scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.Default.LeakAdd, null) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, selectedContainerColor = Color(0xFF7C3AED).copy(alpha = 0.2f), unselectedTextColor = Color(0xFF94A3B8), selectedTextColor = Color.White, selectedIconColor = Color(0xFF7C3AED))
                        )
                        
                        Spacer(Modifier.weight(1f))
                        NavigationDrawerItem(
                            label = { Text("Settings") },
                            selected = false,
                            onClick = { showSettings = true; scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.Default.Settings, null) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, unselectedTextColor = Color(0xFF94A3B8), unselectedIconColor = Color(0xFF94A3B8))
                        )
                    }
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { if (selectedTab != 5) TopHeaderBar(onMenuClick = { scope.launch { drawerState.open() } }, onSettings = { showSettings = true }) },
            bottomBar = { BottomBarTabs(selectedTab = selectedTab, onTabSelected = { selectedTab = it }) }
        ) { padding ->
            Surface(modifier = Modifier.padding(padding).fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (selectedTab) {
                    0 -> repeaterTabs.getOrNull(selectedTabIndex)?.let { _ ->
                        RepeaterTab(
                            tabs = repeaterTabs, selectedTabIndex = selectedTabIndex, rawRequestValue = rawRepeaterValue,
                            onTabSelected = { vm.selectTab(it) }, onTabClose = { vm.closeTab(it) }, onTabRename = { id, n -> vm.renameTab(id, n) }, onAddTab = { vm.addEmptyRepeaterTab() },
                            onRawRequestChange = { rawRepeaterValue = it; vm.updateCurrentTabRequest(it.text); if (it.selection.length > 0) vm.tryDecodeBase64(it.text.substring(it.selection.start, it.selection.end)) },
                            onFollowRedirect = { vm.followRedirect(it, followAll = true) }, onSend = { vm.sendRawRepeaterRequest(rawRepeaterValue.text) }, 
                            onUndo = { vm.undoRepeater(repeaterTabs.getOrNull(selectedTabIndex)?.id ?: "") }, onRedo = { vm.redoRepeater(repeaterTabs.getOrNull(selectedTabIndex)?.id ?: "") }, onCancel = { vm.cancelRepeaterRequest(repeaterTabs.getOrNull(selectedTabIndex)?.id ?: "") },
                            onToIntruder = { vm.sendToIntruder(it) },
                            onPrettifyBody = { vm.prettifyBody(it) }
                        )
                    }
                    1 -> {
                        val payloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                            uri?.let { 
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        context.contentResolver.openInputStream(it)?.use { stream ->
                                            val content = stream.bufferedReader().readText()
                                            vm.appendIntruderPayloads(content)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        IntruderTab(
                            state = intruderState, results = filteredResults,
                            onTemplateChange = { vm.sendToIntruder(it) }, onPayloadsChange = { vm.updateIntruderPayloads(it) }, onRunAttack = { vm.runIntruderAttack() }, onCancel = { vm.cancelIntruderAttack() },
                            onPause = { vm.pauseAttack() }, onResume = { vm.resumeAttack() },
                            onSelectResult = { vm.selectIntruderResult(it) }, onSetSettings = { c, r, t, rd, eu, db, rps -> vm.setIntruderSettings(c, r, t, rd, eu, db, rps) },
                            onClearPayloads = { vm.clearIntruderPayloads() }, onSetFilters = { s, min, max, reg -> vm.setIntruderFilters(s, min, max, reg) },
                            onUploadPayloads = { payloadLauncher.launch("*/*") },
                            onSort = { vm.sortIntruderResults(it) }
                        )
                    }
                    2 -> {
                        val decoderSteps by vm.decoderSteps.collectAsState()
                        DecoderTab(input = decoderInput, output = decoderOutput, steps = decoderSteps, onInputChange = { vm.updateDecoderInput(it) }, onAddStep = { vm.addDecoderStep(it) }, onRemoveStep = { vm.removeDecoderStep(it) }, onClear = { vm.clearDecoder() }, onSwap = { vm.swapDecoder() }, onCopy = { copyToClipboard(context, it) }, onMoveStep = { id, up -> vm.moveDecoderStep(id, up) })
                    }
                    3 -> {
                        val wsState by vm.wsState.collectAsState()
                        val filteredWsMessages by vm.filteredWsMessages.collectAsState()
                        WebSocketTab(messages = filteredWsMessages, state = wsState, onConnect = { url, reconnect -> vm.connectWebSocket(url, reconnect) }, onSend = { vm.sendWsMessage(it) }, onSearch = { vm.updateWsSearch(it) })
                    }
                    4 -> {
                        val sortField by vm.historySortField.collectAsState()
                        if (showHistoryDetail && selectedHistorySummary != null) {
                            HistoryDetailView(
                                summary = selectedHistorySummary!!,
                                detail = selectedHistoryDetails,
                                onBack = { showHistoryDetail = false; vm.clearSelectedHistoryDetail() },
                                vm = vm,
                                context = context
                            )
                        } else {
                            HistoryTab(
                                history = history, activeFilters = historyFilters, onFilterToggle = { vm.toggleHistoryFilter(it) }, 
                                onlyShowInScope = onlyShowInScope, onToggleOnlyInScope = { vm.toggleOnlyInScope(it) }, 
                                onClear = { vm.clearHistory() }, onDeleteRequests = { vm.deleteRequests(it) }, 
                                searchQuery = historySearchQuery, onSearchChange = { vm.updateHistorySearch(it) }, 
                                onItemClick = { selectedHistorySummary = it; showHistoryDetail = true; vm.loadHistoryDetails(it.id) }, 
                                onSort = { vm.setHistorySort(it) }, currentSortField = sortField, 
                                onGetType = { vm.inferContentType(it) }
                            )
                        }
                    }
                    5 -> BrowserTab(isInterceptEnabled = isInterceptEnabled, interceptedRequest = interceptedRequest, allIntercepted = allIntercepted, onToggleIntercept = { vm.toggleIntercept(it) }, onForward = { vm.forwardInterceptedRequest(it) }, onForwardAll = { vm.forwardAllIntercepted() }, onDrop = { vm.dropInterceptedRequest() }, webView = browserWebView, loadProgress = if (loadProgress < 100) loadProgress else 0, viewModel = vm, onShowHistory = { showBrowserHistory = true })
                    6 -> {
                        val text1 by vm.comparerText1.collectAsState()
                        val text2 by vm.comparerText2.collectAsState()
                        ComparerTab(text1, text2, onText1Change = { vm.updateComparerText1(it) }, onText2Change = { vm.updateComparerText2(it) })
                    }
                }
            }
        }
    }

    if (showSettings) SettingsDialog(matchReplaceRules = matchReplaceRules, onAddRule = { t, m, r -> vm.addMatchReplaceRule(t, m, r) }, onToggleRule = { vm.toggleMatchReplaceRule(it) }, onRemoveRule = { vm.removeMatchReplaceRule(it) }, variables = variables, onAddVariable = { n, v -> vm.addVariable(n, v) }, onRemoveVariable = { vm.removeVariable(it) }, scopeRules = scopeRules, onAddScopeRule = { t, p, i -> vm.addScopeRule(t, p, i) }, onToggleScopeRule = { vm.toggleScopeRule(it) }, onRemoveScopeRule = { vm.removeScopeRule(it) }, onDismiss = { showSettings = false })
    
    if (showBrowserHistory) {
        val historyItems by vm.browserHistory.collectAsState()
        AlertDialog(
            onDismissRequest = { showBrowserHistory = false },
            containerColor = Color(0xFF111827),
            title = { 
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Browser History", color = Color.White)
                    IconButton(onClick = { vm.clearBrowserHistory() }) { Icon(Icons.Default.DeleteSweep, "Clear", tint = Color(0xFFEF4444)) }
                }
            },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    if (historyItems.isEmpty()) {
                        Text("No history yet.", color = Color(0xFF94A3B8), modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn {
                            items(historyItems) { item ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { 
                                        browserWebView.loadUrl(item.url)
                                        showBrowserHistory = false
                                    },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(item.title.ifEmpty { "No Title" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(item.url, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBrowserHistory = false }) { Text("Close") } }
        )
    }

    sendToDecoderText?.let { text -> 
        AlertDialog(
            onDismissRequest = { sendToDecoderText = null }, 
            containerColor = Color(0xFF111827), 
            title = { Text("Send to Decoder", color = Color.White) }, 
            text = { Text("Send selected text to Decoder tab?", color = Color.White) }, 
            confirmButton = { TextButton(onClick = { vm.sendToDecoder(text); selectedTab = 2; sendToDecoderText = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { sendToDecoderText = null }) { Text("Cancel") } }
        ) 
    }

    selectedIntruderResult?.let { res -> AlertDialog(onDismissRequest = { vm.selectIntruderResult(null) }, containerColor = Color(0xFF111827), title = { Text("Result Detail", color = Color.White) }, text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { Text("Payload: ${res.payload}", color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); SelectionContainer { Text(res.response, color = Color.White, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp) } } }, confirmButton = { TextButton(onClick = { vm.selectIntruderResult(null) }) { Text("Close") } }) }
}

private fun copyToClipboard(context: Context, text: String) { 
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val safeText = if (text.length > 100_000) {
        android.widget.Toast.makeText(context, "Content truncated for clipboard (100KB limit)", android.widget.Toast.LENGTH_SHORT).show()
        text.take(100_000) + "\n\n[...TRUNCATED DUE TO SIZE...]"
    } else {
        text
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("TheRepeator HTTP", safeText)) 
}

@Composable
private fun TopHeaderBar(onMenuClick: () -> Unit, onSettings: () -> Unit) {
    val brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF020617)))
    Column(modifier = Modifier.fillMaxWidth().background(brush).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) { 
                IconButton(onClick = onMenuClick, modifier = Modifier.size(40.dp)) { 
                    Icon(
                        painter = painterResource(id = R.mipmap.ic_app_logo),
                        contentDescription = "Menu",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) { 
                    Text("TheRepeator", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                } 
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Settings, "Settings", tint = Color(0xFF94A3B8), modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun RepeaterTab(
    tabs: List<RepeaterTabState>,
    selectedTabIndex: Int,
    rawRequestValue: TextFieldValue,
    onTabSelected: (Int) -> Unit,
    onTabClose: (String) -> Unit,
    onTabRename: (String, String) -> Unit,
    onAddTab: () -> Unit,
    onRawRequestChange: (TextFieldValue) -> Unit,
    onFollowRedirect: (String) -> Unit,
    onSend: () -> Unit,
    onUndo: (String) -> Unit,
    onRedo: (String) -> Unit,
    onCancel: (String) -> Unit,
    onToIntruder: (String) -> Unit,
    onPrettifyBody: (String) -> String
) {
    val tab = tabs.getOrNull(selectedTabIndex) ?: return
    var isRequestVisible by remember { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        ScrollableTabRow(selectedTabIndex = selectedTabIndex, edgePadding = 0.dp, containerColor = Color.Transparent, contentColor = Color(0xFF7C3AED), divider = {}) {
            tabs.forEachIndexed { index, t ->
                Tab(selected = selectedTabIndex == index, onClick = { onTabSelected(index) }, text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var isEditingName by remember { mutableStateOf(false) }
                        var newName by remember { mutableStateOf(t.name) }
                        if (isEditingName) {
                            BasicTextField(value = newName, onValueChange = { newName = it }, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 10.sp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onTabRename(t.id, newName); isEditingName = false }), modifier = Modifier.width(40.dp))
                        } else {
                            Text(t.name, fontSize = 10.sp, modifier = Modifier.clickable { isEditingName = true })
                        }
                        IconButton(onClick = { onTabClose(t.id) }, modifier = Modifier.size(16.dp)) { Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(10.dp)) }
                    }
                })
            }
            IconButton(onClick = onAddTab) { Icon(Icons.Default.Add, null, tint = Color(0xFF7C3AED)) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = !tab.isLoading, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)), modifier = Modifier.weight(1f).height(36.dp)) { 
                if (tab.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Send", fontSize = 12.sp) 
            }
            if (tab.redirectUrl != null) { Button(onClick = { onFollowRedirect(tab.id) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)), modifier = Modifier.height(36.dp)) { Text("Follow", fontSize = 12.sp) } }
            IconButton(onClick = { onUndo(tab.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.Undo, null, tint = Color.White) }
            IconButton(onClick = { onRedo(tab.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.Redo, null, tint = Color.White) }
            IconButton(onClick = { onToIntruder(rawRequestValue.text) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Bolt, "To Intruder", tint = Color(0xFF7C3AED)) }
            IconButton(onClick = { isRequestVisible = !isRequestVisible }, modifier = Modifier.size(36.dp)) { Icon(if (isRequestVisible) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Toggle Request", tint = Color.White) }
            if (tab.isLoading) { IconButton(onClick = { onCancel(tab.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Cancel, null, tint = Color(0xFFEF4444)) } }
        }
        if (isRequestVisible) {
            OutlinedTextField(
                value = rawRequestValue, 
                onValueChange = onRawRequestChange, 
                modifier = Modifier.fillMaxWidth().height(200.dp), 
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, color = Color.White), 
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF7C3AED), unfocusedBorderColor = Color(0xFF374151)), 
                placeholder = { Text("Enter raw HTTP request...", color = Color.Gray, fontSize = 11.sp) },
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        ResponseSection(
            response = tab.response, 
            onExtract = {}, 
            onPrettifyBody = onPrettifyBody,
            metadata = tab.metadata,
            statusCode = if (tab.response.startsWith("HTTP")) tab.response.split(" ").getOrNull(1)?.toIntOrNull() else null,
            showExtract = false,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun IntruderTab(
    state: IntruderState,
    results: List<IntruderResult>,
    onTemplateChange: (String) -> Unit,
    onPayloadsChange: (String) -> Unit,
    onRunAttack: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSelectResult: (IntruderResult) -> Unit,
    onSetSettings: (Int, Long, Int, Boolean, Boolean, Boolean, Int) -> Unit,
    onClearPayloads: () -> Unit,
    onSetFilters: (String, Int?, Int?, String) -> Unit,
    onUploadPayloads: () -> Unit,
    onSort: (String) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }; var showFilters by remember { mutableStateOf(false) }; var showPayloadLibrary by remember { mutableStateOf(false) }; var payloadText by remember { mutableStateOf(state.payloads.joinToString("\n")) }
    val darkCard = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF0B1020), unfocusedContainerColor = Color(0xFF0B1020))
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Intruder", color = Color.White, fontWeight = FontWeight.Bold); Row {
                IconButton(onClick = { showPayloadLibrary = true }) { Icon(Icons.AutoMirrored.Filled.LibraryBooks, "Payload Library", tint = Color.White) }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, null, tint = Color.White) }
                IconButton(onClick = { showFilters = true }) { Icon(Icons.Default.FilterAlt, null, tint = Color.White) }
            }
        }
        OutlinedTextField(
            state.templateRequest, 
            onTemplateChange, 
            modifier = Modifier.fillMaxWidth().height(150.dp), 
            label = { Text("Request Template (§payload§)") }, 
            colors = darkCard, 
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            shape = RoundedCornerShape(12.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Payloads (${state.payloads.size})", color = Color(0xFF7C3AED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = onUploadPayloads) { Text("Upload", fontSize = 10.sp) }
                TextButton(onClick = { onClearPayloads(); payloadText = "" }) { Text("Clear", fontSize = 10.sp, color = Color(0xFFEF4444)) }
            }
        }
        OutlinedTextField(
            payloadText, 
            { payloadText = it; onPayloadsChange(it) }, 
            modifier = Modifier.fillMaxWidth().height(100.dp), 
            placeholder = { Text("One payload per line...") }, 
            colors = darkCard, 
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
            shape = RoundedCornerShape(12.dp)
        )
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isRunning) {
                Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(Color(0xFFEF4444)), modifier = Modifier.weight(1f)) { Text("Stop") }
                if (state.isPaused) Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("Resume") }
                else Button(onClick = onPause, modifier = Modifier.weight(1f)) { Text("Pause") }
            } else {
                Button(onClick = onRunAttack, colors = ButtonDefaults.buttonColors(Color(0xFF7C3AED)), modifier = Modifier.fillMaxWidth(), enabled = state.payloads.isNotEmpty() && state.templateRequest.contains("§")) { Text("Start Attack") }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            item { 
                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E293B)).padding(4.dp)) { 
                    Text("ID", modifier = Modifier.width(30.dp).clickable { onSort("Time") }, color = Color.Gray, fontSize = 10.sp)
                    Text("Payload", modifier = Modifier.weight(1f).clickable { onSort("Payload") }, color = Color.Gray, fontSize = 10.sp)
                    Text("Status", modifier = Modifier.width(50.dp).clickable { onSort("Status") }, color = Color.Gray, fontSize = 10.sp)
                    Text("Length", modifier = Modifier.width(60.dp).clickable { onSort("Length") }, color = Color.Gray, fontSize = 10.sp) 
                } 
            }
            items(results) { res -> Row(modifier = Modifier.fillMaxWidth().clickable { onSelectResult(res) }.padding(4.dp)) { Text(res.id.take(3), modifier = Modifier.width(30.dp), color = Color.White, fontSize = 10.sp); Text(res.payload, modifier = Modifier.weight(1f), color = Color.White, fontSize = 10.sp, maxLines = 1); Text(res.statusCode.toString(), modifier = Modifier.width(50.dp), color = if (res.statusCode == 200) Color(0xFF22C55E) else Color(0xFFEF4444), fontSize = 10.sp); Text(formatSize(res.length), modifier = Modifier.width(60.dp), color = Color.White, fontSize = 10.sp) } }
        }
    }
    if (showSettings) {
        var c by remember { mutableStateOf(state.concurrency.toString()) }; var r by remember { mutableStateOf(state.rateLimitMillis.toString()) }; var t by remember { mutableStateOf(state.timeoutSeconds.toString()) }; var rps by remember { mutableStateOf(state.rps.toString()) }
        var rd by remember { mutableStateOf(state.randomDelay) }; var eu by remember { mutableStateOf(state.encodeUrl) }; var db by remember { mutableStateOf(state.decodeBase64) }
        AlertDialog(onDismissRequest = { showSettings = false }, containerColor = Color(0xFF111827), title = { Text("Intruder Settings") }, text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(c, { c = it }, label = { Text("Threads") }, colors = darkCard)
            OutlinedTextField(rps, { rps = it }, label = { Text("RPS Limit") }, colors = darkCard)
            OutlinedTextField(r, { r = it }, label = { Text("Delay (ms)") }, colors = darkCard)
            OutlinedTextField(t, { t = it }, label = { Text("Timeout (s)") }, colors = darkCard)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rd, { rd = it }); Text("Random Delay", color = Color.White) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(eu, { eu = it }); Text("URL Encode", color = Color.White) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(db, { db = it }); Text("Base64 Decode", color = Color.White) }
        } }, confirmButton = { TextButton(onClick = { onSetSettings(c.toIntOrNull() ?: 1, r.toLongOrNull() ?: 0, t.toIntOrNull() ?: 30, rd, eu, db, rps.toIntOrNull() ?: 1); showSettings = false }) { Text("Save") } })
    }
    if (showFilters) {
        var status by remember { mutableStateOf(state.filters.status) }; var reg by remember { mutableStateOf(state.filters.regex) }
        AlertDialog(onDismissRequest = { showFilters = false }, containerColor = Color(0xFF111827), title = { Text("Filters") }, text = { Column {
            OutlinedTextField(status, { status = it }, label = { Text("Status Contains") }, colors = darkCard)
            OutlinedTextField(reg, { reg = it }, label = { Text("Regex Match") }, colors = darkCard)
        } }, confirmButton = { TextButton(onClick = { onSetFilters(status, state.filters.minLength, state.filters.maxLength, reg); showFilters = false }) { Text("Apply") } })
    }
    if (showPayloadLibrary) {
        PayloadLibraryDialog(onDismiss = { showPayloadLibrary = false }, onPayloadsSelected = { onPayloadsChange(it); payloadText = it })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayloadLibraryDialog(onDismiss: () -> Unit, onPayloadsSelected: (String) -> Unit) {
    val categories = listOf(
        "HTTP Methods" to listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT", "TRACE", "MOVE", "COPY", "LINK", "UNLINK", "WRAPPED", "LOCK", "UNLOCK", "PROPFIND", "VIEW"),
        "Standard Headers" to listOf("User-Agent", "Accept", "Accept-Language", "Accept-Encoding", "Referer", "Connection", "Upgrade-Insecure-Requests", "Cache-Control", "Content-Type", "Content-Length", "Origin", "Cookie"),
        "Uncommon Headers" to listOf("X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Original-URL", "X-Rewrite-URL", "X-Custom-IP-Authorization", "X-Real-IP", "True-Client-IP", "Client-IP", "Forwarded", "Via", "Warning"),
        "Web Security" to listOf("Strict-Transport-Security", "Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "X-XSS-Protection", "Expect-CT", "Feature-Policy", "Referrer-Policy")
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        title = { Text("Payload Library", color = Color.White) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                categories.forEach { (category, payloads) ->
                    item {
                        Button(
                            onClick = { onPayloadsSelected(payloads.joinToString("\n")); onDismiss() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(category, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${payloads.size} items", color = Color(0xFF7C3AED), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun BottomBarTabs(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val footerTabs = listOf(
        Triple("Repeater", Icons.AutoMirrored.Filled.Send, 0),
        Triple("Intruder", Icons.Default.Bolt, 1),
        Triple("Requests", Icons.AutoMirrored.Filled.List, 4),
        Triple("Browser", Icons.Default.Language, 5)
    )
    
    Surface(
        color = Color(0xFF111827),
        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            footerTabs.forEach { (_, icon, index) ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            icon, 
                            null, 
                            modifier = Modifier.size(26.dp), 
                            tint = if (isSelected) Color(0xFF7C3AED) else Color(0xFF94A3B8)
                        )
                        if (isSelected) {
                            Spacer(Modifier.height(4.dp))
                            Box(modifier = Modifier.size(4.dp).background(Color(0xFF7C3AED), RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryTab(
    history: List<HistoryItemSummary>,
    activeFilters: Set<String>,
    onFilterToggle: (String) -> Unit,
    onlyShowInScope: Boolean,
    onToggleOnlyInScope: (Boolean) -> Unit,
    onClear: () -> Unit,
    onDeleteRequests: (List<Int>) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onItemClick: (HistoryItemSummary) -> Unit,
    onSort: (String) -> Unit,
    currentSortField: String,
    onGetType: (HistoryItemSummary) -> String
) {
    val filterTypes = listOf("JS", "XML", "JSON", "Images", "HTML", "Text")
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Int>() }
    var showSortMenu by remember { mutableStateOf(false) }
    var showActionMenu by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var areFiltersVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(history.size, currentSortField) {
        if (history.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    onlyShowInScope,
                    onToggleOnlyInScope,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF7C3AED),
                        uncheckedColor = Color(0xFF94A3B8)
                    )
                )
                Text("In Scope", color = Color(0xFFF8FAFC), fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { isSearchExpanded = !isSearchExpanded }) {
                    Icon(
                        Icons.Default.Search,
                        "Search",
                        tint = if (isSearchExpanded) Color(0xFF7C3AED) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { areFiltersVisible = !areFiltersVisible }) {
                    Icon(
                        if (areFiltersVisible) Icons.Default.FilterAltOff else Icons.Default.FilterAlt,
                        "Filters",
                        tint = if (areFiltersVisible) Color(0xFF7C3AED) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            "Sort",
                            tint = Color(0xFF7C3AED),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier.background(Color(0xFF1E293B))
                    ) {
                        listOf("Time", "ID", "Host", "Code", "Size", "Method").forEach { field ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        field,
                                        color = if (currentSortField == field) Color(0xFF7C3AED) else Color.White,
                                        fontSize = 12.sp
                                    )
                                },
                                onClick = { onSort(field); showSortMenu = false }
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { showActionMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Actions", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showActionMenu,
                        onDismissRequest = { showActionMenu = false },
                        modifier = Modifier.background(Color(0xFF1E293B))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Selection Mode", color = Color.White, fontSize = 12.sp) },
                            onClick = { selectionMode = !selectionMode; showActionMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear History", color = Color(0xFFEF4444), fontSize = 12.sp) },
                            onClick = { onClear(); showActionMenu = false }
                        )
                    }
                }
                if (selectionMode && selectedIds.isNotEmpty()) {
                    IconButton(onClick = {
                        onDeleteRequests(selectedIds.toList()); selectionMode = false; selectedIds.clear()
                    }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) }
                }
            }
        }
        if (isSearchExpanded) {
            CustomTextField(
                searchQuery,
                onSearchChange,
                "Host, method, status, size...",
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) }
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = areFiltersVisible) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                filterTypes.forEach { type ->
                    FilterChip(
                        selected = activeFilters.contains(type),
                        onClick = { onFilterToggle(type) },
                        label = { Text(type, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF7C3AED).copy(0.2f),
                            selectedLabelColor = Color(0xFF7C3AED)
                        )
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f), 
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(history, key = { it.id }) { req ->
                val isSelected = selectedIds.contains(req.id)
                HistoryRow(
                    req = req,
                    isSelected = isSelected,
                    selectionMode = selectionMode,
                    onToggleSelection = { if (isSelected) selectedIds.remove(req.id) else selectedIds.add(req.id) },
                    onItemClick = { onItemClick(req) },
                    type = onGetType(req)
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    req: HistoryItemSummary,
    isSelected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onItemClick: () -> Unit,
    type: String
) {
    val isCleartext = req.url.startsWith("http://")
    val statusColor = when (req.statusCode) {
        in 200..299 -> Color(0xFF22C55E)
        in 300..399 -> Color(0xFFFACC15)
        in 400..499 -> Color(0xFFFB923C)
        in 500..599 -> Color(0xFFEF4444)
        else -> Color(0xFF7C3AED)
    }
    val typeColor = when (type) {
        "JS" -> Color(0xFFFACC15)
        "JSON" -> Color(0xFF38BDF8)
        "XML" -> Color(0xFFFB923C)
        "HTML" -> Color(0xFFF472B6)
        "Images" -> Color(0xFF4ADE80)
        "Fonts" -> Color(0xFF818CF8)
        else -> Color(0xFF94A3B8)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable { if (selectionMode) onToggleSelection() else onItemClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = if (isSelected) Color(0xFF7C3AED).copy(0.2f) else Color(0xFF0F172A)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectionMode) {
                        Checkbox(
                            isSelected,
                            { _ -> onToggleSelection() },
                            modifier = Modifier.size(24.dp),
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF7C3AED))
                        )
                    }
                    Text("#${req.id}", color = SyntaxColors.Muted, fontSize = 10.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(req.method, color = Color(0xFF10B981), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    if (isCleartext) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.LockOpen,
                            "Cleartext",
                            tint = Color(0xFFFACC15),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = typeColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(end = 8.dp),
                        border = BorderStroke(1.dp, typeColor.copy(alpha = 0.3f))
                    ) {
                        Text(
                            type,
                            color = typeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        formatSize(req.bodyLength),
                        color = SyntaxColors.Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        req.statusCode.toString(),
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
            Text(
                "${req.host}${req.path}",
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTab(isInterceptEnabled: Boolean, interceptedRequest: InterceptedBrowserRequest?, allIntercepted: List<InterceptedBrowserRequest>, onToggleIntercept: (Boolean) -> Unit, onForward: (String) -> Unit, onForwardAll: () -> Unit, onDrop: () -> Unit, webView: WebView, loadProgress: Int, viewModel: TheRepeatorViewModel, onShowHistory: () -> Unit) {
    var urlText by remember { mutableStateOf(if (webView.url == "about:blank") "" else (webView.url ?: "")) }
    var isUrlBarVisible by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    
    val browserSuggestions by viewModel.browserSuggestions.collectAsState()
    var showSuggestions by remember { mutableStateOf(false) }
    
    LaunchedEffect(webView.url) {
        webView.url?.let { 
            if (it != "about:blank") urlText = it
        }
    }

    LaunchedEffect(urlText) {
        viewModel.updateBrowserSearch(urlText)
        showSuggestions = urlText.isNotEmpty() && (webView.url == null || urlText != webView.url)
    }

    var lastBackClickTime by remember { mutableLongStateOf(0L) }
    var backClickCount by remember { mutableIntStateOf(0) }
    
    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackClickTime < 2000) {
                backClickCount++
            } else {
                backClickCount = 1
            }
            lastBackClickTime = currentTime
            
            if (backClickCount >= 3) {
                (context as? android.app.Activity)?.finish()
            } else {
                android.widget.Toast.makeText(context, "Press back ${3 - backClickCount} more times to exit", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = isUrlBarVisible) {
                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF111827)).statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (webView.canGoBack()) webView.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    IconButton(onClick = { webView.reload() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Color(0xFF94A3B8)) }
                    IconButton(onClick = { if (webView.canGoForward()) webView.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", tint = Color.White) }
                    Box(modifier = Modifier.weight(1f)) {
                        CustomTextField(
                            v = urlText, 
                            ovc = { 
                                urlText = it
                            }, 
                            ph = "Enter URL...", 
                            modifier = Modifier.fillMaxWidth(), 
                            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp), tint = Color(0xFF94A3B8)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), 
                            keyboardActions = KeyboardActions(onGo = { 
                                if (urlText.isNotBlank()) { 
                                    val finalUrl = if (urlText.startsWith("http")) urlText else "https://$urlText"
                                    webView.loadUrl(viewModel.replaceVariables(finalUrl)) 
                                }
                                showSuggestions = false
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            })
                        )
                        if (showSuggestions && browserSuggestions.isNotEmpty()) {
                            Popup(
                                onDismissRequest = { showSuggestions = false },
                                alignment = Alignment.TopStart,
                                offset = androidx.compose.ui.unit.IntOffset(0, 100)
                            ) {
                                Card(
                                    modifier = Modifier.width(300.dp).padding(horizontal = 16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    elevation = CardDefaults.cardElevation(8.dp)
                                ) {
                                    Column {
                                        browserSuggestions.forEach { item ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(item.title, color = Color.White, fontSize = 12.sp, maxLines = 1)
                                                        Text(item.url, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1)
                                                    }
                                                },
                                                onClick = {
                                                    urlText = item.url
                                                    webView.loadUrl(urlText)
                                                    showSuggestions = false
                                                    focusManager.clearFocus()
                                                    keyboardController?.hide()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    IconButton(onClick = onShowHistory) { Icon(Icons.Default.History, "History", tint = Color.White) }
                }
            }
            if (loadProgress > 0) { LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Color(0xFF7C3AED), trackColor = Color.Transparent) }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (webView.url == "about:blank" || webView.url == null) { 
                    Column(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF0B1020)).padding(32.dp), 
                        horizontalAlignment = Alignment.CenterHorizontally, 
                        verticalArrangement = Arrangement.Center
                    ) { 
                        Box(
                            modifier = Modifier.size(160.dp).background(Color(0xFF1E293B), RoundedCornerShape(40.dp)), 
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.mipmap.ic_app_logo),
                                contentDescription = "Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(120.dp)
                            )
                        }
                        Spacer(Modifier.height(32.dp))
                        Text("TheRepeator", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.ExtraBold)
                    } 
                }
                
                if (interceptedRequest != null) {
                    InterceptView(
                        request = interceptedRequest,
                        allIntercepted = allIntercepted,
                        onForward = onForward,
                        onForwardAll = onForwardAll,
                        onDrop = onDrop,
                        onToggleIntercept = onToggleIntercept,
                        viewModel = viewModel
                    )
                } else {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { 
                            isRefreshing = true
                            webView.reload()
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AndroidView(
                            factory = { webView }, 
                            update = { 
                                it.setOnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
                                    val canScrollDown = v.canScrollVertically(1)
                                    val canScrollUp = v.canScrollVertically(-1)
                                    
                                    if (scrollY > oldScrollY && scrollY > 150 && canScrollDown) {
                                        isUrlBarVisible = false
                                    } else if (scrollY < oldScrollY && canScrollUp) {
                                        isUrlBarVisible = true
                                    } else if (!canScrollUp) {
                                        isUrlBarVisible = true
                                    }
                                }
                                it.requestFocus()
                            }, 
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        
        FloatingActionButton(
            onClick = { onToggleIntercept(!isInterceptEnabled) },
            containerColor = if (isInterceptEnabled) Color(0xFFEF4444) else Color(0xFF7C3AED),
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 16.dp) 
        ) {
            Icon(if (isInterceptEnabled) Icons.Default.Block else Icons.Default.Security, "Intercept")
        }
    }
}

@Composable
private fun InterceptView(
    request: InterceptedBrowserRequest,
    allIntercepted: List<InterceptedBrowserRequest>,
    onForward: (String) -> Unit,
    onForwardAll: () -> Unit,
    onDrop: () -> Unit,
    onToggleIntercept: (Boolean) -> Unit,
    viewModel: TheRepeatorViewModel
) {
    var selectedReq by remember { mutableStateOf(request) }
    var editedRequest by remember(selectedReq.id) { mutableStateOf(TextFieldValue(selectedReq.rawRequest)) }
    var showDetail by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Intercepted (${allIntercepted.size})", color = Color.White, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onToggleIntercept(false) }) {
                Icon(Icons.Default.Block, "Stop", tint = Color(0xFFEF4444))
            }
        }

        Spacer(Modifier.height(8.dp))

        if (!showDetail) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(allIntercepted) { req ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { 
                            selectedReq = req
                            showDetail = true 
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedReq.id == req.id) Color(0xFF7C3AED).copy(alpha = 0.2f) else Color(0xFF1E293B)
                        )
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(req.method, color = Color(0xFF22C55E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(req.url, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onForwardAll, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) {
                    Text("Forward All")
                }
                Button(onClick = onDrop, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) {
                    Text("Drop All")
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showDetail = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                        }
                        Text("Detail", color = Color.White)
                    }
                    Row {
                        IconButton(onClick = { viewModel.addRepeaterTab("Int", editedRequest.text) }) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Repeater", tint = Color(0xFF22C55E))
                        }
                        IconButton(onClick = { viewModel.sendToIntruder(editedRequest.text) }) {
                            Icon(Icons.Default.Bolt, "Intruder", tint = Color(0xFF7C3AED))
                        }
                    }
                }
                OutlinedTextField(
                    value = editedRequest,
                    onValueChange = { editedRequest = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7C3AED),
                        unfocusedBorderColor = Color(0xFF374151)
                    )
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onForward(editedRequest.text); showDetail = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))) {
                        Text("Forward")
                    }
                    Button(onClick = { onDrop(); showDetail = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) {
                        Text("Drop")
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparerTab(text1: String, text2: String, onText1Change: (String) -> Unit, onText2Change: (String) -> Unit) {
    var viewMode by remember { mutableIntStateOf(0) } // 0: Input, 1: Visual
    val darkCard = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF0B1020), unfocusedContainerColor = Color(0xFF0B1020), focusedBorderColor = Color(0xFF7C3AED), unfocusedBorderColor = Color(0xFF374151))

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Compare, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Comparer", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onText1Change(""); onText2Change("") }) {
                    Icon(Icons.Default.DeleteSweep, "Clear All", tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                }
            }
            TabRow(selectedTabIndex = viewMode, containerColor = Color.Transparent, contentColor = Color.White, divider = {}, modifier = Modifier.width(200.dp)) {
                Tab(viewMode == 0, { viewMode = 0 }, text = { Text("Input", fontSize = 12.sp) })
                Tab(viewMode == 1, { viewMode = 1 }, text = { Text("Visual", fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(12.dp))
        if (viewMode == 0) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text1, onText1Change, label = { Text("Response 1") }, modifier = Modifier.weight(1f).fillMaxWidth(), colors = darkCard, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp))
                OutlinedTextField(text2, onText2Change, label = { Text("Response 2") }, modifier = Modifier.weight(1f).fillMaxWidth(), colors = darkCard, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp))
            }
        } else {
            val lines1 = text1.lines()
            val lines2 = text2.lines()
            val maxLines = maxOf(lines1.size, lines2.size)
            
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B1020)).border(1.dp, Color(0xFF374151), RoundedCornerShape(8.dp))) {
                items(maxLines) { i ->
                    val line1 = lines1.getOrNull(i) ?: ""
                    val line2 = lines2.getOrNull(i) ?: ""
                    val isDifferent = line1 != line2
                    
                    Row(modifier = Modifier.fillMaxWidth().background(if (isDifferent) Color(0xFF7C3AED).copy(alpha = 0.1f) else Color.Transparent).padding(vertical = 2.dp)) {
                        Text("${i + 1}", color = Color(0xFF4B5563), fontSize = 10.sp, modifier = Modifier.width(30.dp).padding(start = 4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (line1.isNotEmpty() || i < lines1.size) {
                                Text(line1, color = if (isDifferent) Color(0xFFEF4444) else Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                            if (isDifferent && (line2.isNotEmpty() || i < lines2.size)) {
                                Text(line2, color = Color(0xFF22C55E), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
            }
        }
    }
}

@Composable
private fun WebSocketTab(messages: List<WebSocketMessage>, state: String, onConnect: (String, Boolean) -> Unit, onSend: (String) -> Unit, onSearch: (String) -> Unit) {
    var url by remember { mutableStateOf("wss://echo.websocket.org") }; var msg by remember { mutableStateOf("") }; var autoReconnect by remember { mutableStateOf(false) }; var searchQuery by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("WebSocket", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp); val stateColor = when (state) { "CONNECTED" -> Color(0xFF22C55E); "CONNECTING" -> Color(0xFFFACC15); "ERROR" -> Color(0xFFEF4444); else -> Color(0xFF94A3B8) }; AssistChip(onClick = {}, label = { Text(state, fontSize = 10.sp) }, colors = AssistChipDefaults.assistChipColors(labelColor = stateColor, containerColor = Color(0xFF1E293B))) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(url, { url = it }, modifier = Modifier.weight(1f), label = { Text("WS URL") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)); Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Auto", color = Color.White, fontSize = 10.sp); Checkbox(autoReconnect, { autoReconnect = it }, modifier = Modifier.size(24.dp), colors = CheckboxDefaults.colors(checkedColor = Color(0xFF7C3AED))) } }
        Button(onClick = { onConnect(url, autoReconnect) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) { Text("Connect", fontSize = 12.sp) }
        CustomTextField(searchQuery, { searchQuery = it; onSearch(it) }, "Filter messages...", Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) })
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) { items(messages) { m -> val color = if (m.direction == MessageDirection.SENT) Color(0xFF7C3AED) else Color(0xFF22C55E); Card(colors = CardDefaults.cardColors(Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) { Column(modifier = Modifier.padding(8.dp)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (m.direction == MessageDirection.SENT) "SENT" else "RECEIVED", color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp); Text(java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(m.timestamp), color = Color(0xFF4B5563), fontSize = 10.sp) }; Text(m.content, color = Color.White, fontSize = 12.sp); if (m.binaryData != null) { Text("Binary (HEX): ${m.binaryData}", color = Color(0xFFFACC15), fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) } } } } }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(msg, { msg = it }, modifier = Modifier.weight(1f), label = { Text("Message (0x for HEX)") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)); IconButton(onClick = { onSend(msg); msg = "" }) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color(0xFF7C3AED)) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecoderTab(input: String, output: String, steps: List<DecoderStep>, onInputChange: (String) -> Unit, onAddStep: (DecoderTransformType) -> Unit, onRemoveStep: (String) -> Unit, onClear: () -> Unit, onSwap: () -> Unit, onCopy: (String) -> Unit, onMoveStep: (String, Boolean) -> Unit) {
    var showAddMenu by remember { mutableStateOf(false) }; val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Decoder / Encoder", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Row { IconButton(onClick = { val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""; onInputChange(clip) }) { Icon(Icons.Default.ContentPaste, "Paste", tint = Color(0xFF94A3B8)) }; IconButton(onClick = { onCopy(output) }) { Icon(Icons.Default.ContentCopy, "Copy", tint = Color(0xFF94A3B8)) }; IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "Clear", tint = Color(0xFFEF4444)) } } }
        OutlinedTextField(input, onInputChange, modifier = Modifier.fillMaxWidth().height(120.dp), label = { Text("Input") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        Text("Transform Chain", color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { 
            steps.forEachIndexed { index, step -> 
                Card(colors = CardDefaults.cardColors(Color(0xFF1E293B)), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(step.type.name.replace("_", " "), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onMoveStep(step.id, true) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, null, tint = if (index > 0) Color.White else Color.Gray, modifier = Modifier.size(16.dp)) }
                        IconButton(onClick = { onMoveStep(step.id, false) }, enabled = index < steps.size - 1) { Icon(Icons.Default.ArrowDownward, null, tint = if (index < steps.size - 1) Color.White else Color.Gray, modifier = Modifier.size(16.dp)) }
                        IconButton(onClick = { onRemoveStep(step.id) }) { Icon(Icons.Default.Close, null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            Box { 
                AssistChip(onClick = { showAddMenu = true }, label = { Text("Add Transform", fontSize = 10.sp) }, leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp)) }, colors = AssistChipDefaults.assistChipColors(labelColor = Color(0xFF7C3AED)))
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }, modifier = Modifier.background(Color(0xFF1E293B))) { 
                    DecoderTransformType.entries.forEach { type -> DropdownMenuItem(text = { Text(type.name.replace("_", " "), color = Color.White, fontSize = 12.sp) }, onClick = { onAddStep(type); showAddMenu = false }) } 
                } 
            } 
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onSwap, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(Color(0xFF1E293B))) { Text("Swap Input/Output", fontSize = 12.sp) } }
        Card(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), colors = CardDefaults.cardColors(Color(0xFF111827)), border = BorderStroke(1.dp, Color(0xFF374151))) { 
            SelectionContainer { 
                Column(modifier = Modifier.padding(12.dp)) { 
                    if (output.startsWith("Header: {")) { 
                        Text("JWT Claims Viewer", color = Color(0xFF7C3AED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        val parts = output.split("\nPayload: ")
                        if (parts.size == 2) {
                            Text("Header", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            Text(parts[0].replace("Header: ", ""), color = Color(0xFF22C55E), fontSize = 12.sp)
                            Text("Payload", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            Text(parts[1], color = Color(0xFFFACC15), fontSize = 12.sp)
                        } else {
                            Text(output, color = Color(0xFF22C55E), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp) 
                        }
                    } else { 
                        Text(output.ifEmpty { "Output..." }, color = if (output.isEmpty()) Color(0xFF4B5563) else if (output.startsWith("Error:")) Color(0xFFEF4444) else Color(0xFF22C55E), fontSize = 12.sp) 
                    } 
                } 
            } 
        }
    }
}

@Composable
private fun SettingsDialog(matchReplaceRules: List<MatchReplaceRule>, onAddRule: (RuleType, String, String) -> Unit, onToggleRule: (String) -> Unit, onRemoveRule: (String) -> Unit, variables: List<Variable>, onAddVariable: (String, String) -> Unit, onRemoveVariable: (String) -> Unit, scopeRules: List<ScopeRule>, onAddScopeRule: (ScopeRuleType, String, Boolean) -> Unit, onToggleScopeRule: (String) -> Unit, onRemoveScopeRule: (String) -> Unit, onDismiss: () -> Unit) {
    var showAddRule by remember { mutableStateOf(false) }; var showAddVar by remember { mutableStateOf(false) }; var showAddScope by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF111827), title = { Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }, text = { Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { 
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { 
            Column { Text("Match & Replace Rules", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text("Automatically modify outgoing requests.", color = Color(0xFF94A3B8), fontSize = 10.sp) }
            IconButton(onClick = { showAddRule = true }) { Icon(Icons.Default.Add, null, tint = Color(0xFF7C3AED)) } 
        }
        matchReplaceRules.forEach { r -> Card(colors = CardDefaults.cardColors(Color(0xFF1E293B)), modifier = Modifier.fillMaxWidth()) { Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(r.enabled, { onToggleRule(r.id) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF7C3AED))); Column(modifier = Modifier.weight(1f)) { Text(r.type.name, fontSize = 10.sp, color = Color(0xFF7C3AED)); Text("${r.match} -> ${r.replace}", fontSize = 12.sp, color = Color.White) }; IconButton(onClick = { onRemoveRule(r.id) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) } } } }
        HorizontalDivider(color = Color(0xFF374151))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { 
            Column { Text("Variables", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text("Define values for use with {{name}} syntax.", color = Color(0xFF94A3B8), fontSize = 10.sp) }
            IconButton(onClick = { showAddVar = true }) { Icon(Icons.Default.Add, null, tint = Color(0xFF7C3AED)) } 
        }
        variables.forEach { v -> Card(colors = CardDefaults.cardColors(Color(0xFF1E293B)), modifier = Modifier.fillMaxWidth()) { Row(modifier = Modifier.padding(8.dp)) { Column(modifier = Modifier.weight(1f)) { Text(v.name, fontSize = 10.sp, color = Color(0xFF7C3AED)); Text(v.value, fontSize = 12.sp, color = Color.White) }; IconButton(onClick = { onRemoveVariable(v.id) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) } } } }
        HorizontalDivider(color = Color(0xFF374151))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { 
            Column { Text("Scope Rules", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text("Filter history and intercept traffic.", color = Color(0xFF94A3B8), fontSize = 10.sp) }
            IconButton(onClick = { showAddScope = true }) { Icon(Icons.Default.Add, null, tint = Color(0xFF7C3AED)) } 
        }
        scopeRules.forEach { r -> Card(colors = CardDefaults.cardColors(Color(0xFF1E293B)), modifier = Modifier.fillMaxWidth()) { Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(r.enabled, { onToggleScopeRule(r.id) }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF7C3AED))); Column(modifier = Modifier.weight(1f)) { Text("${if (r.isInScope) "IN" else "OUT"} - ${r.type.name}", fontSize = 10.sp, color = if (r.isInScope) Color(0xFF22C55E) else Color(0xFFEF4444)); Text(r.pattern, fontSize = 12.sp, color = Color.White) }; IconButton(onClick = { onRemoveScopeRule(r.id) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) } } } }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    val darkCard = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF0B1020), unfocusedContainerColor = Color(0xFF0B1020))
    if (showAddRule) { var m by remember { mutableStateOf("") }; var r by remember { mutableStateOf("") }; var t by remember { mutableStateOf(RuleType.REQUEST_HEADER) }; AlertDialog(onDismissRequest = { showAddRule = false }, containerColor = Color(0xFF111827), title = { Text("Add Rule") }, text = { Column { Row { RuleType.entries.forEach { FilterChip(t == it, { t = it }, { Text(it.name.take(3)) }) } }; OutlinedTextField(m, { m = it }, label = { Text("Match") }, colors = darkCard); OutlinedTextField(r, { r = it }, label = { Text("Replace") }, colors = darkCard) } }, confirmButton = { TextButton(onClick = { if (m.isNotEmpty()) onAddRule(t, m, r); showAddRule = false }) { Text("Add") } }) }
    if (showAddVar) { var n by remember { mutableStateOf("") }; var v by remember { mutableStateOf("") } ; AlertDialog(onDismissRequest = { showAddVar = false }, containerColor = Color(0xFF111827), title = { Text("Add Variable") }, text = { Column { OutlinedTextField(n, { n = it }, label = { Text("Name") }, colors = darkCard); OutlinedTextField(v, { v = it }, label = { Text("Value") }, colors = darkCard) } }, confirmButton = { TextButton(onClick = { if (n.isNotEmpty()) onAddVariable(n, v); showAddVar = false }) { Text("Add") } }) }
    if (showAddScope) { var p by remember { mutableStateOf("") }; var t by remember { mutableStateOf(ScopeRuleType.HOST) }; var i by remember { mutableStateOf(true) }; AlertDialog(onDismissRequest = { showAddScope = false }, containerColor = Color(0xFF111827), title = { Text("Add Scope Rule") }, text = { Column { Row { ScopeRuleType.entries.forEach { FilterChip(t == it, { t = it }, { Text(it.name) }) } }; Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("Out Scope", color = Color.White, fontSize = 12.sp); Switch(i, { i = it }, modifier = Modifier.scale(0.7f), colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF7C3AED), checkedTrackColor = Color(0xFF7C3AED).copy(alpha = 0.5f))); Text("In Scope", color = Color.White, fontSize = 12.sp) }; OutlinedTextField(p, { p = it }, label = { Text("Pattern") }, colors = darkCard) } }, confirmButton = { TextButton(onClick = { if (p.isNotEmpty()) onAddScopeRule(t, p, i); showAddScope = false }) { Text("Add") } }) }
}

@Composable
private fun RenderLargeText(
    text: String,
    searchQuery: String,
    context: Context,
    isExpanded: Boolean,
    listState: LazyListState = rememberLazyListState(),
    currentMatchIndex: Int = 0,
    onMatchesFound: (List<Int>) -> Unit = {}
) {
    val maxChars = if (isExpanded) 1_000_000 else 200_000
    val isActuallyTruncated = text.length > maxChars
    
    val displayContent = remember(text, isExpanded) {
        if (isActuallyTruncated) text.take(maxChars) else text
    }

    val lines = remember(displayContent) { 
        displayContent.split("\n")
    }
    
    val matchIndices = remember(lines, searchQuery) {
        if (searchQuery.isEmpty()) emptyList()
        else lines.mapIndexedNotNull { index, line -> if (line.contains(searchQuery, ignoreCase = true)) index else null }
    }

    LaunchedEffect(matchIndices) {
        onMatchesFound(matchIndices)
    }

    LaunchedEffect(currentMatchIndex, matchIndices) {
        if (matchIndices.isNotEmpty() && currentMatchIndex in matchIndices.indices) {
            listState.animateScrollToItem(matchIndices[currentMatchIndex])
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isActuallyTruncated) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFACC15).copy(alpha = 0.1f))
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFACC15), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Content truncated to ${formatSize(maxChars)} for performance. ${if (!isExpanded) "Expand for more." else ""}",
                        color = Color(0xFFFACC15),
                        fontSize = 11.sp
                    )
                }
            }
        }

        val useSyntaxHighlighting = displayContent.length < 50_000
        
        SelectionContainer { 
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                    val displayLine = remember(line) { 
                        if (line.length > 10_000) line.take(10_000) + " ... [LINE TRUNCATED]" else line 
                    }
                    
                    if (searchQuery.isNotEmpty() && displayLine.contains(searchQuery, ignoreCase = true)) {
                        val isCurrentMatchLine = matchIndices.getOrNull(currentMatchIndex) == index
                        val annotatedString = remember(displayLine, searchQuery, isCurrentMatchLine) {
                            buildAnnotatedString {
                                var startMatch = 0
                                while (startMatch < displayLine.length) {
                                    val matchIdx = displayLine.indexOf(searchQuery, startMatch, ignoreCase = true)
                                    if (matchIdx == -1) { append(displayLine.substring(startMatch)); break }
                                    append(displayLine.substring(startMatch, matchIdx))
                                    val bgColor = if (isCurrentMatchLine) Color(0xFF7C3AED) else Color(0xFFFACC15).copy(alpha = 0.8f)
                                    val textColor = if (isCurrentMatchLine) Color.White else Color.Black
                                    withStyle(style = SpanStyle(background = bgColor, color = textColor)) {
                                        append(displayLine.substring(matchIdx, matchIdx + searchQuery.length))
                                    }
                                    startMatch = matchIdx + searchQuery.length
                                }
                            }
                        }
                        Text(annotatedString, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp))
                    } else {
                        val annotatedLine = remember(displayLine, useSyntaxHighlighting) {
                            if (useSyntaxHighlighting) highlightSyntax(displayLine) else AnnotatedString(displayLine)
                        }
                        Text(annotatedLine, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                if (isActuallyTruncated) {
                    item {
                        Text(
                            "\n[... CONTENT TRUNCATED ...]\n",
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { copyToClipboard(context, text) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937))
            ) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy Full")
            }
        }
    }
}

@Composable
private fun HistoryDetailView(
    summary: HistoryItemSummary,
    detail: TheRepeatorRequest?,
    onBack: () -> Unit,
    vm: TheRepeatorViewModel,
    context: Context
) {
    var searchQuery by remember { mutableStateOf("") }
    var matchIndices by remember { mutableStateOf(emptyList<Int>()) }
    var currentMatchIndex by remember { mutableIntStateOf(0) }
    val requestListState = rememberLazyListState()
    
    var isBeautified by remember { mutableStateOf(false) }
    var isExpandedFull by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val detailTab = pagerState.currentPage
    val scope = rememberCoroutineScope()
    val isLoading = (detail == null || detail.id != summary.id)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                Text("${summary.method} Detail", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Row {
                if (detailTab == 1 && !isLoading) {
                    IconButton(onClick = { isBeautified = !isBeautified }) {
                        Icon(Icons.Default.AutoFixHigh, null, tint = if (isBeautified) Color(0xFF7C3AED) else Color.White)
                    }
                }
                IconButton(onClick = { isExpandedFull = !isExpandedFull }) {
                    Icon(if (isExpandedFull) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null, tint = Color.White)
                }
            }
        }

        TabRow(
            selectedTabIndex = detailTab,
            containerColor = Color(0xFF111827),
            contentColor = Color(0xFF7C3AED),
            indicator = { tabPositions ->
                if (detailTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[detailTab]),
                        color = Color(0xFF7C3AED),
                        height = 3.dp
                    )
                }
            }
        ) {
            Tab(selected = detailTab == 0, onClick = { scope.launch { pagerState.animateScrollToPage(0) } }, text = { Text("Request") })
            Tab(selected = detailTab == 1, onClick = { scope.launch { pagerState.animateScrollToPage(1) } }, text = { Text("Response") })
        }

        if (detailTab == 0) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CustomTextField(
                    searchQuery,
                    { 
                        searchQuery = it
                        currentMatchIndex = 0
                    },
                    "Search...",
                    Modifier.weight(1f),
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) }
                )
                if (searchQuery.isNotEmpty()) {
                    Text(
                        "${if (matchIndices.isEmpty()) 0 else currentMatchIndex + 1}/${matchIndices.size}",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { 
                            if (currentMatchIndex > 0) currentMatchIndex-- 
                            else if (matchIndices.isNotEmpty()) currentMatchIndex = matchIndices.size - 1 
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White)
                    }
                    IconButton(
                        onClick = { 
                            if (currentMatchIndex < matchIndices.size - 1) currentMatchIndex++ 
                            else currentMatchIndex = 0 
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White)
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color(0xFF7C3AED))
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    if (page == 0) {
                        RenderLargeText(
                            text = vm.getRawFromTheRepeatorRequest(detail), 
                            searchQuery = searchQuery, 
                            context = context, 
                            isExpanded = isExpandedFull,
                            listState = requestListState,
                            currentMatchIndex = currentMatchIndex,
                            onMatchesFound = { matchIndices = it }
                        )
                    } else {
                        ResponseSection(
                            response = vm.getRawResponse(detail),
                            onExtract = { /* Handle if needed */ },
                            onPrettifyBody = { vm.prettifyBody(it) },
                            statusCode = detail.statusCode,
                            initialPage = if (isBeautified) 0 else 1
                        )
                    }
                }
            }
        }
        
        if (!isLoading) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addRepeaterTab("H", vm.getRawFromTheRepeatorRequest(detail)); onBack() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) { Text("To Repeater", fontSize = 12.sp) }
                Button(onClick = { vm.sendToIntruder(vm.getRawFromTheRepeatorRequest(detail)); onBack() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))) { Text("To Intruder", fontSize = 12.sp) }
                Button(onClick = { vm.sendHistoryToComparerSmart(detail.id); onBack() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))) { Text("To Comparer", fontSize = 12.sp) }
            }
        }
    }
}

private object SyntaxColors {
    val JsonKey = Color(0xFFFB923C)
    val JsonString = Color(0xFF4ADE80)
    val JsonNumber = Color(0xFF38BDF8)
    val HtmlTag = Color(0xFFF472B6)
    val Muted = Color(0xFF94A3B8)
    val Boolean = Color(0xFFFB7185)
    val Punctuation = Color.White
}

@Composable
private fun ResponseSection(
    response: String,
    onExtract: (String) -> Unit,
    onPrettifyBody: (String) -> String,
    modifier: Modifier = Modifier,
    metadata: ResponseMetadata? = null,
    statusCode: Int? = null,
    initialPage: Int = 0,
    showExtract: Boolean = true
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    
    val fullHeaders = remember(response) {
        val splitIndex = response.indexOf("\n\n")
        val splitIndex2 = response.indexOf("\r\n\r\n")
        val finalSplit = when {
            splitIndex2 != -1 && splitIndex != -1 -> minOf(splitIndex, splitIndex2)
            splitIndex2 != -1 -> splitIndex2
            else -> splitIndex
        }
        if (finalSplit != -1) response.substring(0, finalSplit) else response
    }
    val body = remember(response) {
        val splitIndex = response.indexOf("\n\n")
        val splitIndex2 = response.indexOf("\r\n\r\n")
        if (splitIndex2 != -1) response.substring(splitIndex2 + 4)
        else if (splitIndex != -1) response.substring(splitIndex + 2)
        else ""
    }
    
    val cookies = remember(fullHeaders) {
        fullHeaders.lines()
            .filter { it.startsWith("Set-Cookie:", ignoreCase = true) }
            .joinToString("\n")
    }

    val tabs = listOf("Pretty", "Raw", "Headers", "Cookies")
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabs.size })
    val subTab = pagerState.currentPage
    val scope = rememberCoroutineScope()
    
    var searchQuery by remember { mutableStateOf("") }
    
    // Independent states for each tab to prevent "hanging" and scroll interference
    val listStates = remember { List(4) { LazyListState() } }
    val matchIndicesList = remember { List(4) { mutableStateOf(emptyList<Int>()) } }
    val currentMatchIndices = remember { List(4) { mutableIntStateOf(0) } }
    
    val matchIndices = matchIndicesList[subTab].value
    
    val statusColor = when (statusCode) {
        in 200..299 -> Color(0xFF22C55E)
        in 400..499 -> Color(0xFFFB923C)
        in 500..599 -> Color(0xFFEF4444)
        else -> Color(0xFF94A3B8)
    }

    LaunchedEffect(initialPage) {
        pagerState.scrollToPage(initialPage)
    }

    Card(
        colors = CardDefaults.cardColors(Color(0xFF111827)), 
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ScrollableTabRow(
                    selectedTabIndex = subTab, 
                    containerColor = Color.Transparent, 
                    contentColor = Color.White, 
                    edgePadding = 0.dp, 
                    divider = {},
                    modifier = Modifier.weight(1f)
                ) { 
                    tabs.forEachIndexed { i, t -> 
                        Tab(subTab == i, { scope.launch { pagerState.animateScrollToPage(i) } }, text = { Text(t, fontSize = 10.sp) }) 
                    } 
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (statusCode != null) {
                        Surface(
                            color = statusColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
                        ) {
                            Text(
                                statusCode.toString(),
                                color = statusColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(32.dp)) {
                        Icon(if (isExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
            
            HorizontalDivider(color = Color(0xFF374151))
            
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { 
                CustomTextField(
                    searchQuery, 
                    { 
                        searchQuery = it
                        currentMatchIndices[subTab].intValue = 0
                    }, 
                    "Search...", 
                    Modifier.weight(1f), 
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) }
                )
                if (searchQuery.isNotEmpty()) {
                    Text(
                        "${if (matchIndices.isEmpty()) 0 else currentMatchIndices[subTab].intValue + 1}/${matchIndices.size}",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { 
                            val current = currentMatchIndices[subTab]
                            val indices = matchIndicesList[subTab].value
                            if (current.intValue > 0) current.intValue-- 
                            else if (indices.isNotEmpty()) current.intValue = indices.size - 1 
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White)
                    }
                    IconButton(
                        onClick = { 
                            val current = currentMatchIndices[subTab]
                            val indices = matchIndicesList[subTab].value
                            if (current.intValue < indices.size - 1) current.intValue++ 
                            else current.intValue = 0 
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White)
                    }
                }
                if (searchQuery.isNotEmpty() && showExtract) {
                    IconButton(onClick = { onExtract(searchQuery) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentPasteGo, "Extract", tint = Color(0xFF7C3AED), modifier = Modifier.size(20.dp)) }
                }
            }
            
            HorizontalPager(
                state = pagerState, 
                modifier = Modifier.weight(1f), 
                userScrollEnabled = true, 
                beyondViewportPageCount = 1
            ) { page ->
                val content = when (page) {
                    0 -> onPrettifyBody(body)
                    1 -> response
                    2 -> fullHeaders
                    3 -> cookies.ifEmpty { "No cookies found." }
                    else -> ""
                }
                RenderLargeText(
                    text = content, 
                    searchQuery = searchQuery, 
                    context = context, 
                    isExpanded = isExpanded,
                    listState = listStates[page],
                    currentMatchIndex = currentMatchIndices[page].intValue,
                    onMatchesFound = { matchIndicesList[page].value = it }
                )
            }
        }
    }
    
    if (isExpanded) {
        Dialog(
            onDismissRequest = { isExpanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B1020)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Response Full View", color = Color.White, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { isExpanded = false }) { Icon(Icons.Default.Close, null, tint = Color.White) }
                    }
                    // Recursive call but with isExpanded already being false for the nested one
                    ResponseSection(
                        response = response,
                        onExtract = onExtract,
                        onPrettifyBody = onPrettifyBody,
                        metadata = metadata,
                        statusCode = statusCode,
                        initialPage = 0,
                        showExtract = showExtract,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private val syntaxRegex = Regex("""(".*?")(\s*:)?|(\b\d+\b)|([{}\[\]])|(\btrue\b|\bfalse\b|\bnull\b)|(<[^>]+>)""")

private fun highlightSyntax(line: String): AnnotatedString {
    if (line.length > 2000) return AnnotatedString(line)
    return buildAnnotatedString {
        var start = 0
        val matches = syntaxRegex.findAll(line)
        for (m in matches) {
            append(line.substring(start, m.range.first))
            val color = when {
                m.groups[1] != null -> if (m.groups[2] != null) SyntaxColors.JsonKey else SyntaxColors.JsonString
                m.groups[3] != null -> SyntaxColors.JsonNumber
                m.groups[4] != null -> SyntaxColors.Punctuation
                m.groups[5] != null -> SyntaxColors.Boolean
                m.groups[6] != null -> SyntaxColors.HtmlTag
                else -> Color.White
            }
            if (m.groups[1] != null) {
                withStyle(SpanStyle(color = color)) { append(m.groups[1]!!.value) }
                if (m.groups[2] != null) withStyle(SpanStyle(color = SyntaxColors.Punctuation)) { append(m.groups[2]!!.value) }
            } else withStyle(SpanStyle(color = color)) { append(m.value) }
            start = m.range.last + 1
        }
        if (start < line.length) append(line.substring(start))
    }
}

@Composable
private fun CustomTextField(v: String, ovc: (String) -> Unit, ph: String, modifier: Modifier = Modifier, leadingIcon: (@Composable () -> Unit)? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default) { BasicTextField(v, ovc, modifier = modifier.height(36.dp).background(Color(0xFF0F172A), RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp)), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF7C3AED)), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, decorationBox = { itf -> Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (leadingIcon != null) leadingIcon(); Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { if (v.isEmpty()) Text(ph, color = SyntaxColors.Muted, fontSize = 12.sp); itf() } } } ) }

@Composable
private fun TheRepeatorTheme(content: @Composable () -> Unit) { 
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF8B5CF6),
        onPrimary = Color.White,
        secondary = Color(0xFF10B981),
        onSecondary = Color.White,
        background = Color(0xFF020617),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF0F172A),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF1E293B),
        onSurfaceVariant = Color(0xFF94A3B8),
        outline = Color(0xFF334155),
        error = Color(0xFFEF4444)
    )
    val typography = Typography(
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White),
        titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, color = Color(0xFFE2E8F0)),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, color = Color(0xFFE2E8F0)),
        labelSmall = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = SyntaxColors.Muted)
    )
    MaterialTheme(
        colorScheme = darkColorScheme, 
        typography = typography,
        content = content
    ) 
}
