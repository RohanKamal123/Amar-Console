package com.amarhelper.console.ui.workspace

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.HttpAuthHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amarhelper.console.data.config.AppConfig
import com.amarhelper.console.ui.services.ServicesScreen
import com.amarhelper.console.BuildConfig
import com.amarhelper.console.ui.profile.ProfileScreen
import kotlinx.coroutines.launch

private enum class Workspace(val label: String, val icon: ImageVector) {
    IDE("IDE", Icons.Default.Computer),
    OPEN_CODE("OpenCode", Icons.Default.Code),
    OPEN_HANDS("OpenHands", Icons.Default.SmartToy),
    PROFILE("Profile", Icons.Default.AccountCircle),
    SERVICES("Services", Icons.Default.HealthAndSafety),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onOpenSettings: () -> Unit,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(Workspace.OPEN_HANDS) }
    val claudeStyle = config.claudeStyleWorkspaces
    val inApp = config.openWorkspacesInApp
    var reload by remember { mutableStateOf<(() -> Unit)?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected.label) },
                actions = {
                    if (selected in setOf(Workspace.IDE, Workspace.OPEN_CODE, Workspace.OPEN_HANDS)) {
                        IconButton(onClick = { reload?.invoke() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload workspace")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Open settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Workspace.entries.forEach { workspace ->
                    NavigationBarItem(
                        selected = selected == workspace,
                        onClick = { selected = workspace },
                        icon = { Icon(workspace.icon, contentDescription = null) },
                        label = { Text(workspace.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selected) {
                Workspace.IDE -> BrowserWorkspace(
                    url = config.ideUrl,
                    title = "IDE",
                    onReloadAvailable = { reload = it },
                )
                Workspace.OPEN_CODE -> BrowserWorkspace(
                    url = config.openCodeUrl,
                    title = "OpenCode",
                    onReloadAvailable = { reload = it },
                    useSystemBrowser = !inApp,
                    applyStyling = claudeStyle,
                )
                Workspace.OPEN_HANDS -> BrowserWorkspace(
                    url = config.openHandsUrl,
                    title = "OpenHands",
                    onReloadAvailable = { reload = it },
                    useSystemBrowser = !inApp,
                    showCommandBar = inApp,
                    applyStyling = claudeStyle,
                )
                Workspace.PROFILE -> ProfileScreen()
                Workspace.SERVICES -> ServicesScreen(
                    onBack = { selected = Workspace.OPEN_HANDS },
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun BrowserWorkspace(
    url: String,
    title: String,
    basicAuth: (suspend () -> String?)? = null,
    onReloadAvailable: ((() -> Unit)?) -> Unit,
    useSystemBrowser: Boolean = false,
    showCommandBar: Boolean = false,
    applyStyling: Boolean = true,
) {
    if (url.isBlank()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("$title is not configured", style = MaterialTheme.typography.titleLarge)
            Text("Open Settings and enter the $title web URL.")
        }
        LaunchedEffect(Unit) { onReloadAvailable(null) }
        return
    }

    if (useSystemBrowser) {
        SystemBrowserWorkspace(url = url, title = title, onReloadAvailable = onReloadAvailable)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }

    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }
    DisposableEffect(webView) {
        onReloadAvailable(webView?.let { { it.reload() } })
        onDispose { onReloadAvailable(null) }
    }

    Column(Modifier.fillMaxSize()) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    // Remote debugging is how a rendering problem in this WebView gets
                    // diagnosed at all; debug builds only.
                    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    settings.setSupportZoom(true)
                    val workspaceWebView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(workspaceWebView, true)
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                        }

                        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                            // A blank page looks like a successful load from outside;
                            // its console is where the reason actually shows up.
                            message?.let(WorkspaceDiagnostics::record)
                            return super.onConsoleMessage(message)
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?,
                        ): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            filePicker.launch(fileChooserParams?.acceptTypes?.firstOrNull().orEmpty().ifBlank { "*/*" })
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val destination = request.url
                            val configuredHost = Uri.parse(url).host
                            if (destination.scheme !in setOf("http", "https")) {
                                return openExternally(context, destination)
                            }
                            if (destination.host != configuredHost && destination.host?.endsWith("github.com") == true) {
                                return openExternally(context, destination)
                            }
                            return false
                        }

                        override fun onReceivedHttpAuthRequest(
                            view: WebView?, handler: HttpAuthHandler, host: String?, realm: String?,
                        ) {
                            if (basicAuth == null) {
                                super.onReceivedHttpAuthRequest(view, handler, host, realm)
                            } else {
                                scope.launch {
                                    basicAuth()?.let { handler.proceed("opencode", it) } ?: handler.cancel()
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            finishedUrl?.let { currentUrl = it }
                            progress = 1f
                            if (applyStyling) view?.let { WorkspaceStyleInjector.apply(it) }
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?, historyUrl: String?, isReload: Boolean,
                        ) {
                            super.doUpdateVisitedHistory(view, historyUrl, isReload)
                            historyUrl?.let { currentUrl = it }
                            // The frontend is a single-page app: route changes do not
                            // trigger onPageFinished, so re-assert the styling here too.
                            if (applyStyling) view?.let { WorkspaceStyleInjector.apply(it) }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame == true) progress = 1f
                        }
                    }
                    setDownloadListener(WorkspaceDownloadListener(viewContext))
                    loadUrl(url)
                }
            },
            update = { view ->
                if (view.url == null) view.loadUrl(url)
            },
        )
        if (progress < 1f) CircularProgressIndicator(progress = { progress })
      }

      diagnostics?.let { report ->
          val clipboard = LocalClipboardManager.current
          AlertDialog(
              onDismissRequest = { diagnostics = null },
              title = { Text("Page diagnostics") },
              text = {
                  Text(
                      text = report,
                      style = MaterialTheme.typography.bodySmall.copy(
                          fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                      ),
                      modifier = Modifier.verticalScroll(rememberScrollState()),
                  )
              },
              confirmButton = {
                  TextButton(onClick = {
                      clipboard.setText(AnnotatedString(report))
                      diagnostics = null
                  }) { Text("Copy") }
              },
              dismissButton = {
                  TextButton(onClick = { diagnostics = null }) { Text("Close") }
              },
          )
      }

      if (showCommandBar) {
          WorkspaceCommandBar(
              currentUrl = currentUrl,
              onEffect = { effect ->
                  when (effect) {
                      is WorkspaceEffect.Navigate -> webView?.loadUrl(effect.url)
                      WorkspaceEffect.Reload -> webView?.reload()
                      is WorkspaceEffect.OpenExternally ->
                          openExternally(context, Uri.parse(effect.url))
                      WorkspaceEffect.RunDiagnostics -> webView?.evaluateJavascript(
                          WorkspaceDiagnostics.PROBE_SCRIPT,
                      ) { result ->
                          diagnostics = WorkspaceDiagnostics.report(
                              probeJson = result,
                              appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                          )
                      }
                  }
              },
          )
      }
    }
}

@Composable
private fun SystemBrowserWorkspace(
    url: String,
    title: String,
    onReloadAvailable: ((() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val open: () -> Unit = remember(url) {
        {
            openExternally(context, Uri.parse(url))
            Unit
        }
    }
    LaunchedEffect(url) {
        onReloadAvailable(open)
        open()
    }
    DisposableEffect(url) {
        onDispose { onReloadAvailable(null) }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
        Text("$title opens in Chrome", style = MaterialTheme.typography.titleLarge)
        Text(
            "Chrome is used because this service's modern web interface does not render " +
                "reliably in Android's embedded WebView.",
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = { open() }) {
            Text("Open $title")
        }
    }
}

private fun openExternally(context: Context, uri: Uri): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    true
}.getOrDefault(false)

private class WorkspaceDownloadListener(
    private val context: Context,
) : DownloadListener {
    override fun onDownloadStart(
        url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long,
    ) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.enqueue(request) }
            .onSuccess { Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Download failed", Toast.LENGTH_LONG).show() }
    }
}
