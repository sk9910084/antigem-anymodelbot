package ai.antigem.ui.anymodelbot

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.antigem.data.anymodelbot.AnyModelBotManager
import ai.antigem.data.anymodelbot.AnyModelBotStatus
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.GeminiTheme
import ai.antigem.theme.isSystemInDarkThemeRobust
import kotlinx.coroutines.delay

/** AnyModelBot, added to AntiGem: its Node server runs in AntiGem's own Linux environment and its web UI shows here. */
class AnyModelBotActivity : ComponentActivity() {

    private val prefs by lazy { AuthPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val initial = remember { prefs.getThemeModeSync() }
            val mode by prefs.themeMode.collectAsState(initial = initial)
            val systemDark = isSystemInDarkThemeRobust()
            GeminiTheme(darkTheme = when (mode) { "DARK" -> true; "LIGHT" -> false; else -> systemDark }) {
                AnyModelBotScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnyModelBotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val status by AnyModelBotManager.status.collectAsState()
    val token by AnyModelBotManager.token.collectAsState()
    var apiKey by remember { mutableStateOf(AnyModelBotManager.getApiKey(context)) }
    var baseUrl by remember { mutableStateOf(AnyModelBotManager.getBaseUrl(context)) }
    var model by remember { mutableStateOf(AnyModelBotManager.getModel(context)) }
    var showWebView by remember { mutableStateOf(false) }
    var webViewLoaded by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf(emptyList<String>()) }

    // show the app as soon as the server answers; drop back to setup when it stops
    LaunchedEffect(status) {
        if (status is AnyModelBotStatus.Running) showWebView = true
        if (status !is AnyModelBotStatus.Running) webViewLoaded = false
    }
    // the server log, refreshed while the screen is open
    LaunchedEffect(Unit) {
        while (true) {
            logLines = AnyModelBotManager.readLogTail(context)
            delay(2000)
        }
    }

    val running = status is AnyModelBotStatus.Running
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AnyModelBot") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (running) {
                        IconButton(onClick = { showWebView = !showWebView }) {
                            Icon(
                                if (showWebView) Icons.Outlined.Terminal else Icons.Outlined.Web,
                                if (showWebView) "Show log" else "Show app"
                            )
                        }
                        IconButton(onClick = { AnyModelBotManager.stop() }) {
                            Icon(Icons.Outlined.Stop, "Stop server")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (showWebView && running && token != null) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView ->
                    if (!webViewLoaded) {
                        webView.loadUrl("${AnyModelBotManager.SERVER_URL}/?token=$token")
                        webViewLoaded = true
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(statusText(status), style = MaterialTheme.typography.titleMedium)
                        if (status is AnyModelBotStatus.Downloading) {
                            LinearProgressIndicator(
                                progress = { (status as AnyModelBotStatus.Downloading).fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "First start downloads the app bundle once (${AnyModelBotManager.BUNDLE_SIZE_LABEL}) — use WiFi.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (status is AnyModelBotStatus.Running) {
                            Text(
                                "Server is up on this phone (127.0.0.1:3600). Tap the globe icon above to open the app.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Start
                Button(
                    onClick = { AnyModelBotManager.start(context) },
                    enabled = status is AnyModelBotStatus.Idle || status is AnyModelBotStatus.Stopped ||
                        status is AnyModelBotStatus.Error,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Text("Start AnyModelBot")
                }

                // Keys: the model brain (Xkiro, free)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Model keys (Xkiro)", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("Xkiro API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text("Model") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = {
                            AnyModelBotManager.saveKeys(context, apiKey, baseUrl, model)
                            Toast.makeText(context, "Keys saved", Toast.LENGTH_SHORT).show()
                        }) { Text("Save keys") }
                        Text(
                            "Saved in this app only. Restart the server after changing keys.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Log
                if (logLines.isNotEmpty()) {
                    Text("Log", style = MaterialTheme.typography.titleMedium)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            logLines.joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Text(
                    "The server runs on this phone in AntiGem's own Linux environment; nothing leaves it except the model calls to Xkiro. Ovis and DSH agent mode are not included — plain chat works.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun statusText(status: AnyModelBotStatus): String = when (status) {
    is AnyModelBotStatus.Idle -> "Not started"
    is AnyModelBotStatus.Downloading -> "Downloading the app bundle (${(status.fraction * 100).toInt()}%)"
    is AnyModelBotStatus.Extracting -> "Unpacking the app bundle..."
    is AnyModelBotStatus.Starting -> "Starting the server..."
    is AnyModelBotStatus.Running -> "Running on 127.0.0.1:3600"
    is AnyModelBotStatus.Stopped -> "Stopped"
    is AnyModelBotStatus.Error -> "Error: ${status.message}"
}
