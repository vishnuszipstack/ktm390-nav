package dev.ktmnav.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.ktmnav.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ReleaseInfo(
    val buildNumber: Int,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: Int) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    /** Android needs "Install unknown apps" allowed for this app first. */
    data class NeedsInstallPermission(val release: ReleaseInfo) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks the GitHub repo's latest release (tagged `build-N`) and, if N is
 * newer than this build's versionCode, downloads the APK and hands it to the
 * system installer. Every build is signed with the same key, so it installs
 * as an update and keeps settings and bike pairing.
 */
class Updater(private val context: Context) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentBuild: Int = BuildConfig.VERSION_CODE
    val currentVersionName: String = BuildConfig.VERSION_NAME

    suspend fun check() {
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = fetchLatest()
            when {
                release == null -> UpdateState.Failed("No APK found in the latest release")
                release.buildNumber > currentBuild -> UpdateState.Available(release)
                else -> UpdateState.UpToDate(currentBuild)
            }
        } catch (e: Exception) {
            UpdateState.Failed(friendly(e))
        }
    }

    suspend fun downloadAndInstall(release: ReleaseInfo) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsInstallPermission(release)
            return
        }
        _state.value = UpdateState.Downloading(release, 0f)
        try {
            val apk = download(release)
            _state.value = UpdateState.Installing
            install(apk)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(friendly(e))
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun onInstallFinished(message: String?) {
        _state.value = if (message == null) UpdateState.Idle else UpdateState.Failed(message)
    }

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> throw IOException("No releases found (is the repo public?)")
                403, 429 -> throw IOException("GitHub rate limit reached, try again in a while")
                else -> throw IOException("GitHub returned ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            parseRelease(json)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update-${release.buildNumber}.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            var done = 0L
            var lastReported = 0f
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done.toFloat() / total).coerceIn(0f, 1f)
                            if (p - lastReported >= 0.02f) {
                                lastReported = p
                                _state.value = UpdateState.Downloading(release, p)
                            }
                        }
                    }
                }
            }
            out
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "KtmNav/${BuildConfig.VERSION_NAME}")
        }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "GitHub didn't respond, try again"
        else -> e.message ?: "Update failed"
    }

    companion object {
        /** Release tags look like `build-42`; 42 is the build's versionCode. */
        fun parseRelease(json: JSONObject): ReleaseInfo? {
            val tag = json.optString("tag_name")
            val build = tag.removePrefix("build-").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk")) {
                    return ReleaseInfo(
                        buildNumber = build,
                        name = json.optString("name").ifBlank { tag },
                        notes = json.optString("body"),
                        apkUrl = a.getString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                    )
                }
            }
            return null
        }
    }
}

/** Receives the system installer's progress for an update session. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as dev.ktmnav.KtmNavApp).container.updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { updater.onInstallFinished("Couldn't open the installer") }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstallFinished(null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallFinished("Update cancelled")
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            -> updater.onInstallFinished(
                "Couldn't install over this version (different signing key). Uninstall the app once and install the new APK from GitHub.",
            )
            else -> updater.onInstallFinished(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed",
            )
        }
    }
}
