package com.s0undplus.tv.ui.update

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.tv.material3.Icon
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Radius
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.BuildConfig
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.twitch.update.UpdateInfo
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUi
    data class NeedsPermission(val info: UpdateInfo) : UpdateUi
    data class SignatureMismatch(val info: UpdateInfo) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/**
 * Checks GitHub Releases for a newer APK and installs it. Installing an update only works when the new APK is signed
 * with the same key as the installed one, so the download is verified against the installed signature *before* the
 * system installer is launched, and a mismatch is explained instead of failing with a generic "App not installed".
 */
class UpdateViewModel(private val context: Context, private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state.asStateFlow()

    private var checkedThisLaunch = false

    /** Quiet startup check, honouring the "check automatically" setting. Does nothing on later calls. */
    fun checkOnLaunch() {
        if (checkedThisLaunch) return
        checkedThisLaunch = true
        viewModelScope.launch {
            if (c.settings.settings.first().autoUpdateCheck) check(manual = false)
        }
    }

    fun check(manual: Boolean) {
        if (_state.value is UpdateUi.Checking || _state.value is UpdateUi.Downloading) return
        _state.value = UpdateUi.Checking
        viewModelScope.launch {
            try {
                val info = c.updates.newerThan(BuildConfig.VERSION_NAME)
                _state.value = when {
                    info != null -> UpdateUi.Available(info)
                    manual -> UpdateUi.UpToDate
                    else -> UpdateUi.Idle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = if (manual) UpdateUi.Failed("Couldn't check for updates.") else UpdateUi.Idle
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateUi.Idle
    }

    fun install(info: UpdateInfo) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateUi.NeedsPermission(info)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(info) }
                if (!sameSigner(file)) {
                    _state.value = UpdateUi.SignatureMismatch(info)
                    return@launch
                }
                launchInstaller(file)
                _state.value = UpdateUi.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUi.Failed("Download failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun download(info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // only ever keep the one being installed
        }
        val target = File(dir, info.apkName.replace('/', '_'))
        c.http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = UpdateUi.Downloading(info, percent)
                        }
                    }
                }
            }
        }
        return target
    }

    private fun sameSigner(apk: File): Boolean {
        val pm = context.packageManager
        val downloaded = archiveInfo(pm, apk) ?: return false
        if (downloaded.packageName != context.packageName) return false
        val installed = installedInfo(pm)
        return signerBytes(downloaded).isNotEmpty() && signerBytes(downloaded) == signerBytes(installed)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun signerBytes(info: PackageInfo): Set<List<Byte>> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { it.toByteArray().toList() }.toSet()
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Status text for the Settings "Check for updates" row. */
fun UpdateUi.statusLabel(): String = when (this) {
    UpdateUi.Idle -> "Check now"
    UpdateUi.Checking -> "Checking…"
    UpdateUi.UpToDate -> "You're up to date"
    is UpdateUi.Available -> "Version ${info.version} available"
    is UpdateUi.Downloading -> "Downloading… $percent%"
    is UpdateUi.NeedsPermission -> "Allow installs, then retry"
    is UpdateUi.SignatureMismatch -> "Can't update in place"
    is UpdateUi.Failed -> "Failed — try again"
}

/** A strip at the top of Home that appears only when there is something to say about updates. */
@Composable
fun UpdateBanner(vm: UpdateViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message: String
    val actions: @Composable () -> Unit
    when (val s = state) {
        is UpdateUi.Available -> {
            val warning = if (BuildConfig.STABLE_SIGNING) "" else "  (this build uses a temporary signing key; the update may need an uninstall first)"
            message = "Update available: S0undPlus ${s.info.version} (${s.info.sizeBytes / 1_000_000} MB)$warning"
            actions = {
                PillButton("Install", { vm.install(s.info) })
                PillButton("Later", vm::dismiss)
            }
        }
        is UpdateUi.Downloading -> {
            message = "Downloading S0undPlus ${s.info.version}… ${s.percent}%"
            actions = {}
        }
        is UpdateUi.NeedsPermission -> {
            message = "Allow S0undPlus to install apps in the settings screen that just opened, then choose Install again."
            actions = {
                PillButton("Install", { vm.install(s.info) })
                PillButton("Later", vm::dismiss)
            }
        }
        is UpdateUi.SignatureMismatch -> {
            message = "This update is signed with a different key, so it can't replace the installed app. " +
                "Uninstall S0undPlus once (you'll sign in again), then install the new APK."
            actions = { PillButton("OK", vm::dismiss) }
        }
        is UpdateUi.Failed -> {
            message = s.message
            actions = { PillButton("Dismiss", vm::dismiss) }
        }
        else -> return
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.dialog))
            .background(Palette.SurfaceRaised)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = null, tint = Palette.BrandSoft, modifier = Modifier.size(22.dp))
        Text(message, style = AppType.BodyM, color = Palette.OnBackground, modifier = Modifier.weight(1f))
        actions()
    }
}
