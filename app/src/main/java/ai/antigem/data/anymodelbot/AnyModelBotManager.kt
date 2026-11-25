package ai.antigem.data.anymodelbot

import android.content.Context
import android.util.Log
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalPtySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * AnyModelBot, added to AntiGem as a Dots-style feature: the prebuilt AnyModelBot v0.1.47 bundle is
 * downloaded once (with a SHA-256 check), then its Node server runs in this app's own Termux
 * environment (a [LocalPtySession], the same way the local server runs its bridge) and its web UI
 * is shown in [ai.antigem.ui.anymodelbot.AnyModelBotActivity]. AntiGem's own UI and data are not
 * changed by this feature; it only adds the AnyModelBot entry point.
 *
 * The model brain is the Xkiro endpoint (free): the key, base URL and model are entered on the
 * AnyModelBot screen and stored in this app's own prefs; the start script reads them from files
 * under the Termux home (never from the command line).
 */
sealed class AnyModelBotStatus {
    object Idle : AnyModelBotStatus()
    data class Downloading(val fraction: Float) : AnyModelBotStatus()
    object Extracting : AnyModelBotStatus()
    object Starting : AnyModelBotStatus()
    data class Running(val sinceMs: Long = System.currentTimeMillis()) : AnyModelBotStatus()
    object Stopped : AnyModelBotStatus()
    data class Error(val message: String) : AnyModelBotStatus()
}

object AnyModelBotManager {

    private const val TAG = "AnyModelBot"

    /** The prebuilt AnyModelBot v0.1.47 bundle (built on the VM, smoke-tested; same file the Termux app uses). */
    const val BUNDLE_URL =
        "https://github.com/sk9910084/termux-app/releases/download/anymodelbot-bundle-v0.1.47/anymodelbot-bundle-v0.1.47.tgz"
    const val BUNDLE_SHA256 =
        "f61abe3f42da9b96d08a7d1cba22e6202700c35147fbe1e99253443a2831c515"
    const val BUNDLE_FILE = "anymodelbot-bundle-v0.1.47.tgz"
    const val BUNDLE_SIZE_LABEL = "~96 MB"
    const val SERVER_PORT = 3600
    const val SERVER_URL = "http://127.0.0.1:3600"
    const val HEALTH_URL = "$SERVER_URL/amb/healthz"

    /** Xkiro (free) defaults for the Keys card. */
    const val DEFAULT_BASE_URL = "https://api.xkiro.com/v1"
    const val DEFAULT_MODEL = "mistralai/mistral-small-2603"

    private const val PREFS = "anymodelbot_prefs"
    private const val KEY_API_KEY = "xkiro_key"
    private const val KEY_BASE_URL = "xkiro_base_url"
    private const val KEY_MODEL = "xkiro_model"

    private val _status = MutableStateFlow<AnyModelBotStatus>(AnyModelBotStatus.Idle)
    val status: StateFlow<AnyModelBotStatus> = _status.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var healthJob: Job? = null
    private var session: LocalPtySession? = null

    /** The bundle lives in the app's Termux home, like a user's project: ~/anymodelbot. */
    fun bundleDir(context: Context): File = File(LocalEnvironmentManager.getHomeDir(context), "anymodelbot")
    fun serverLogFile(context: Context): File = File(bundleDir(context), "server.log")
    fun tokenFile(context: Context): File = File(bundleDir(context), "data/ui-token")
    private fun startScriptFile(context: Context): File = File(bundleDir(context), "start-anymodelbot.sh")
    private fun keyDir(context: Context): File = File(LocalEnvironmentManager.getHomeDir(context), ".anymodelbot")

    fun isBundleReady(context: Context): Boolean {
        val dir = bundleDir(context)
        return File(dir, "src/main.ts").exists() && File(dir, "node_modules/tsx/dist/cli.mjs").exists()
    }

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, "") ?: ""

    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BASE_URL, DEFAULT_BASE_URL)
            ?: DEFAULT_BASE_URL

    fun getModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, DEFAULT_MODEL)
            ?: DEFAULT_MODEL

    fun saveKeys(context: Context, apiKey: String, baseUrl: String, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_BASE_URL, baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }

    /** The last lines of the server log, ANSI escapes stripped, for the screen's log view. */
    fun readLogTail(context: Context, maxLines: Int = 200): List<String> {
        val file = serverLogFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val lines = file.readText().lines()
            val tail = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            tail.map { it.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "") }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The UI token the server wrote (data/ui-token); the WebView passes it as ?token=. */
    fun readToken(context: Context): String? {
        val file = tokenFile(context)
        return try {
            if (!file.exists()) null else file.readText().trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun start(context: Context) {
        val current = _status.value
        if (current is AnyModelBotStatus.Downloading || current is AnyModelBotStatus.Extracting ||
            current is AnyModelBotStatus.Starting || current is AnyModelBotStatus.Running
        ) return
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val appContext = context.applicationContext
                if (!LocalEnvironmentManager.isInstalled(appContext)) {
                    _status.value = AnyModelBotStatus.Error(
                        "AntiGem's Linux environment is not installed yet — finish the app setup first, then start AnyModelBot."
                    )
                    return@launch
                }
                if (!ensureBundle(appContext)) {
                    _status.value = AnyModelBotStatus.Error("Bundle is not ready — check the log.")
                    return@launch
                }
                writeKeyFiles(appContext)
                writeStartScript(appContext)
                _status.value = AnyModelBotStatus.Starting
                stopSession()
                val dir = bundleDir(appContext)
                val sess = withContext(Dispatchers.Main) {
                    LocalPtySession(
                        id = "anymodelbot-pty",
                        initialTitle = "AnyModelBot",
                        context = appContext,
                        initialWorkingDir = dir.absolutePath,
                        initialCommand = "sh start-anymodelbot.sh",
                        initialCols = 80,
                        initialRows = 24
                    )
                }
                session = sess
                healthJob?.cancel()
                healthJob = scope.launch { pollHealth(appContext) }
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                _status.value = AnyModelBotStatus.Error(e.message ?: "start failed")
            }
        }
    }

    fun stop() {
        activeJob?.cancel()
        healthJob?.cancel()
        scope.launch {
            try {
                session?.write("\u0003")
            } catch (_: Exception) {
            }
            delay(800)
            stopSession()
            _status.value = AnyModelBotStatus.Stopped
        }
    }

    private fun stopSession() {
        val s = session
        session = null
        if (s == null) return
        try {
            s.terminalSession.finishIfRunning()
        } catch (_: Exception) {
        }
        try {
            s.close()
        } catch (_: Exception) {
        }
    }

    private suspend fun pollHealth(appContext: Context) {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val deadline = System.currentTimeMillis() + 150_000L
        var up = false
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            val tok = readToken(appContext)
            val req = Request.Builder().url(HEALTH_URL)
                .apply { if (tok != null) header("x-amb-token", tok) }
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) up = true
                }
            } catch (_: Exception) {
            }
            if (up) break
            delay(2000)
        }
        if (up) {
            _token.value = readToken(appContext)
            _status.value = AnyModelBotStatus.Running()
        } else {
            _status.value = AnyModelBotStatus.Error(
                "Server did not answer on 127.0.0.1:3600 — see the log below."
            )
        }
    }

    /** Download (with progress) + SHA-256 check + extract, reusing a good download if it is already there. */
    private suspend fun ensureBundle(context: Context): Boolean {
        if (isBundleReady(context)) return true
        val home = LocalEnvironmentManager.getHomeDir(context)
        if (!home.exists()) home.mkdirs()
        val tmpFile = File(home, BUNDLE_FILE)
        if (!(tmpFile.exists() && sha256Of(tmpFile) == BUNDLE_SHA256)) {
            _status.value = AnyModelBotStatus.Downloading(0f)
            downloadBundle(tmpFile)
            if (sha256Of(tmpFile) != BUNDLE_SHA256) {
                tmpFile.delete()
                throw IllegalStateException("bundle checksum mismatch — download again")
            }
        }
        _status.value = AnyModelBotStatus.Extracting
        extractBundle(tmpFile, bundleDir(context))
        return isBundleReady(context)
    }

    private suspend fun downloadBundle(dest: File) = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(BUNDLE_URL).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("bundle download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("bundle download failed: empty body")
            val total = body.contentLength()
            var read = 0L
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) _status.value = AnyModelBotStatus.Downloading(read.toFloat() / total)
                    }
                }
            }
        }
    }

    /** Extracts the .tgz, stripping its top-level "anymodelbot/" folder, keeping executable bits. */
    private fun extractBundle(tgz: File, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(tgz)))).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val name = entry.name.removePrefix("anymodelbot/")
                if (name.isNotBlank()) {
                    val out = File(destDir, name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { output -> tar.copyTo(output) }
                        if ((entry.mode and 0x40) != 0) out.setExecutable(true, false)
                    }
                }
                entry = tar.nextTarEntry
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var n = input.read(buf)
            while (n >= 0) {
                digest.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The Xkiro key / endpoint / model, as files the start script reads (never on a command line). */
    private fun writeKeyFiles(context: Context) {
        val dir = keyDir(context)
        if (!dir.exists()) dir.mkdirs()
        File(dir, "xkiro-key.txt").writeText(getApiKey(context))
        File(dir, "xkiro-base-url.txt").writeText(getBaseUrl(context))
        File(dir, "xkiro-model.txt").writeText(getModel(context))
    }

    /** Repairs Node the Termux way (the phone's Termux node can be broken by a stale C++ library), then starts the server. */
    private fun writeStartScript(context: Context) {
        val dir = bundleDir(context)
        val script = """
            #!/bin/sh
            # AnyModelBot (AntiGem) — generated by AnyModelBotManager. Do not edit by hand.
            cd "${dir.absolutePath}"
            if ! node --version >/dev/null 2>&1; then
              echo "[anymodelbot] node missing or broken - installing nodejs..."
              pkg install -y nodejs git
            fi
            if ! node --version >/dev/null 2>&1; then
              echo "[anymodelbot] node still broken - updating libc++..."
              pkg install -y libc++
            fi
            if ! node --version >/dev/null 2>&1; then
              echo "[anymodelbot] node still broken - upgrading packages (this can take a while)..."
              pkg upgrade -y
            fi
            if ! node --version >/dev/null 2>&1; then
              echo "error: node is broken (pkg upgrade needed)"
              exit 1
            fi
            KEY_DIR="${'$'}HOME/.anymodelbot"
            if [ -s "${'$'}KEY_DIR/xkiro-key.txt" ]; then export AMB_MAGPIE_API_KEY="$(cat "${'$'}KEY_DIR/xkiro-key.txt")"; fi
            if [ -s "${'$'}KEY_DIR/xkiro-base-url.txt" ]; then export AMB_MAGPIE_BASE_URL="$(cat "${'$'}KEY_DIR/xkiro-base-url.txt")"; fi
            if [ -s "${'$'}KEY_DIR/xkiro-model.txt" ]; then export AMB_DEFAULT_MODEL="$(cat "${'$'}KEY_DIR/xkiro-model.txt")"; fi
            export AMB_LANG=en
            export AMB_ALLOW_LANDLOCK=1
            echo "[anymodelbot] starting server on 127.0.0.1:3600 ..."
            node node_modules/tsx/dist/cli.mjs src/main.ts 2>&1 | tee server.log
        """.trimIndent()
        val file = startScriptFile(context)
        file.writeText(script)
        file.setExecutable(true, false)
    }
}
