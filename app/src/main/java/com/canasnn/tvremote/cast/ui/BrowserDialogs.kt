package com.canasnn.tvremote.cast.ui

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canasnn.tvremote.MainActivity
import com.canasnn.tvremote.R
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.browser.BrowserManager
import com.canasnn.tvremote.cast.browser.BrowserTab
import com.canasnn.tvremote.cast.browser.BrowserTab.Prompt
import com.ruterika.castcore.browser.UserAgents
import com.ruterika.castcore.library.SavedMedia
import com.ruterika.castcore.settings.CustomUserAgent
import java.util.UUID

/**
 * Questions a page asks (certificate error, JS dialogs, sign-in, permissions,
 * file upload). One at a time, oldest first.
 */
@Composable
internal fun BrowserPromptDialogs(tab: BrowserTab) {
    val context = LocalContext.current
    val prompts by tab.prompts.collectAsStateWithLifecycle()

    // Android runtime permissions behind a page's request.
    var awaitingPermission by remember { mutableStateOf<Prompt?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val prompt = awaitingPermission ?: return@rememberLauncherForActivityResult
        awaitingPermission = null
        fun granted(permission: String) = result[permission] == true || hasPermission(context, permission)
        when (prompt) {
            is Prompt.Permission -> {
                val allowed = prompt.resources.filter { resource ->
                    androidPermissionFor(resource)?.let(::granted) ?: true
                }
                if (allowed.isEmpty()) prompt.request.deny() else prompt.request.grant(allowed.toTypedArray())
            }
            is Prompt.Geolocation -> {
                val ok = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                prompt.callback.invoke(prompt.origin, ok, false)
            }
            else -> Unit
        }
        tab.done(prompt)
    }

    // File upload (<input type=file>).
    var awaitingFile by remember { mutableStateOf<Prompt.FileChooser?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val prompt = awaitingFile ?: return@rememberLauncherForActivityResult
        awaitingFile = null
        prompt.callback.onReceiveValue(chosenUris(result.resultCode, result.data))
        tab.done(prompt)
    }

    val prompt = prompts.firstOrNull() ?: return
    if (prompt === awaitingPermission || prompt === awaitingFile) return

    when (prompt) {
        is Prompt.Ssl -> SslDialog(prompt, tab)
        is Prompt.JsDialog -> JsDialog(prompt, tab)
        is Prompt.HttpAuth -> HttpAuthDialog(prompt, tab)
        is Prompt.Permission -> {
            val names = prompt.resources.map(::resourceLabel)
            AlertDialog(
                onDismissRequest = { prompt.request.deny(); tab.done(prompt) },
                title = { Text("Permission request") },
                text = { Text("${Uri.parse(prompt.origin).host ?: prompt.origin} wants to use your ${names.joinToString(" and ")}.") },
                confirmButton = {
                    TextButton(onClick = {
                        val needed = prompt.resources.mapNotNull(::androidPermissionFor).filterNot { hasPermission(context, it) }
                        if (needed.isEmpty()) {
                            prompt.request.grant(prompt.resources.toTypedArray())
                            tab.done(prompt)
                        } else {
                            awaitingPermission = prompt
                            permissionLauncher.launch(needed.toTypedArray())
                        }
                    }) { Text("Allow") }
                },
                dismissButton = { TextButton(onClick = { prompt.request.deny(); tab.done(prompt) }) { Text("Deny") } }
            )
        }
        is Prompt.Geolocation -> {
            var keep by remember(prompt) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { prompt.callback.invoke(prompt.origin, false, false); tab.done(prompt) },
                title = { Text("Location request") },
                text = {
                    Column {
                        Text("${Uri.parse(prompt.origin).host ?: prompt.origin} wants to know your location.")
                        CheckRow("Remember for this site", keep) { keep = it }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val location = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (location.any { hasPermission(context, it) }) {
                            prompt.callback.invoke(prompt.origin, true, keep && !tab.incognito)
                            tab.done(prompt)
                        } else {
                            awaitingPermission = prompt
                            permissionLauncher.launch(location.toTypedArray())
                        }
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = { prompt.callback.invoke(prompt.origin, false, keep && !tab.incognito); tab.done(prompt) }) { Text("Deny") }
                }
            )
        }
        is Prompt.FileChooser -> LaunchedEffect(prompt) {
            try {
                awaitingFile = prompt
                fileLauncher.launch(prompt.params.createIntent())
            } catch (_: ActivityNotFoundException) {
                awaitingFile = null
                prompt.cancel()
                tab.done(prompt)
                Toast.makeText(context, "No app can pick files", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun SslDialog(prompt: Prompt.Ssl, tab: BrowserTab) {
    var always by remember(prompt) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { prompt.cancel(); tab.done(prompt) },
        title = { Text("Connection not secure") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The certificate of ${prompt.host.ifBlank { prompt.url }} could not be verified.")
                Text(prompt.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (prompt.details.isNotBlank()) {
                    Text(prompt.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Continue only if you trust this site.", style = MaterialTheme.typography.bodySmall)
                if (prompt.host.isNotBlank()) CheckRow("Always ignore for this site", always) { always = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (always && prompt.host.isNotBlank()) {
                    val host = prompt.host.lowercase().removePrefix("www.")
                    CastHub.settingsStore.update { it.copy(sslIgnoredHosts = it.sslIgnoredHosts + host) }
                }
                prompt.proceed()
                tab.done(prompt)
            }) { Text("Continue anyway") }
        },
        dismissButton = { TextButton(onClick = { prompt.cancel(); tab.done(prompt) }) { Text("Go back") } }
    )
}

@Composable
private fun JsDialog(prompt: Prompt.JsDialog, tab: BrowserTab) {
    var input by remember(prompt) { mutableStateOf(prompt.defaultValue.orEmpty()) }
    var blockMore by remember(prompt) { mutableStateOf(false) }
    fun finish(ok: Boolean) {
        if (blockMore) tab.blockDialogsOnPage = true
        when {
            prompt.kind == BrowserTab.JsKind.ALERT -> prompt.result.confirm()
            !ok -> prompt.result.cancel()
            prompt.kind == BrowserTab.JsKind.PROMPT -> (prompt.result as? android.webkit.JsPromptResult)?.confirm(input) ?: prompt.result.confirm()
            else -> prompt.result.confirm()
        }
        tab.done(prompt)
    }
    AlertDialog(
        onDismissRequest = { finish(false) },
        title = { Text("${prompt.origin} says", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) { Text(prompt.message) }
                if (prompt.kind == BrowserTab.JsKind.PROMPT) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                CheckRow("Don't let this page show more dialogs", blockMore) { blockMore = it }
            }
        },
        confirmButton = { TextButton(onClick = { finish(true) }) { Text("OK") } },
        dismissButton = if (prompt.kind == BrowserTab.JsKind.ALERT) null else ({ TextButton(onClick = { finish(false) }) { Text("Cancel") } })
    )
}

@Composable
private fun HttpAuthDialog(prompt: Prompt.HttpAuth, tab: BrowserTab) {
    var user by remember(prompt) { mutableStateOf("") }
    var password by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { prompt.handler.cancel(); tab.done(prompt) },
        title = { Text("Sign in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${prompt.host} requires a user name and password." + if (prompt.realm.isNotBlank()) "\n\"${prompt.realm}\"" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { prompt.handler.proceed(user, password); tab.done(prompt) }) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = { prompt.handler.cancel(); tab.done(prompt) }) { Text("Cancel") } }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Long-press menu ─────────────────────────────────────────────────────────

/** Long press on a link or image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkContextSheet(
    tab: BrowserTab,
    target: BrowserTab.ContextTarget,
    pageUrl: String,
    pageTitle: String,
    onCast: (List<SavedMedia>, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val link = target.linkUrl
    val image = target.imageUrl
    val webImage = image?.takeIf { it.startsWith("http") }
    fun row(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit): @Composable () -> Unit = {
        ListItem(
            headlineContent = { Text(text) },
            leadingContent = { Icon(icon, null) },
            modifier = Modifier.clickable { onDismiss(); action() }
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                target.linkTitle?.takeIf { it.isNotBlank() } ?: link ?: image.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            if (link != null) {
                row("Open in new tab", Icons.Filled.Tab) {
                    BrowserManager.newTab(link, tab.incognito, select = false)
                    Toast.makeText(context, "Opened in a new tab", Toast.LENGTH_SHORT).show()
                }()
                row("Open in private tab", Icons.Filled.VisibilityOff) { BrowserManager.newTab(link, incognito = true) }()
                row("Bookmark link", Icons.Filled.BookmarkAdd) {
                    if (!CastHub.library.isBookmarked(link)) CastHub.library.toggleBookmark(link, target.linkTitle ?: link)
                    Toast.makeText(context, "Bookmarked", Toast.LENGTH_SHORT).show()
                }()
                row("Copy link", Icons.Filled.ContentCopy) { copy(context, link) }()
                row("Share link", Icons.Filled.Share) { share(context, link) }()
            }
            if (link != null && image != null) HorizontalDivider()
            if (image != null) {
                if (webImage != null) {
                    row("Cast image to TV", Icons.Filled.Cast) {
                        val ua = tab.webView.settings.userAgentString
                        val headers = buildMap {
                            put("User-Agent", ua)
                            if (pageUrl.startsWith("http")) put("Referer", pageUrl)
                        }
                        onCast(
                            listOf(
                                SavedMedia(
                                    url = webImage,
                                    title = Uri.parse(webImage).lastPathSegment ?: pageTitle.ifBlank { "Image" },
                                    mimeType = imageMime(webImage),
                                    headers = headers,
                                    pageUrl = pageUrl.takeIf { it.startsWith("http") },
                                    posterUrl = webImage
                                )
                            ),
                            0
                        )
                    }()
                    row("Open image in new tab", Icons.Filled.Image) { BrowserManager.newTab(webImage, tab.incognito) }()
                    row("Save image", Icons.Filled.Download) { saveImage(context, webImage, tab.webView.settings.userAgentString, pageUrl, tab.incognito) }()
                }
                row("Copy image link", Icons.Filled.ContentCopy) { copy(context, image) }()
                if (webImage != null) row("Share image link", Icons.Filled.Share) { share(context, webImage) }()
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }
}

internal fun imageMime(url: String): String = when (Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "avif" -> "image/avif"
    "svg" -> "image/svg+xml"
    else -> "image/*"
}

/** Saves an image with Android's download manager (Downloads folder, notification when done). */
private fun saveImage(context: Context, url: String, userAgent: String, pageUrl: String, incognito: Boolean) {
    runCatching {
        val mime = imageMime(url).takeIf { it != "image/*" }
        val name = URLUtil.guessFileName(url, null, mime)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setMimeType(mime ?: "image/*")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader("User-Agent", userAgent)
        if (pageUrl.startsWith("http")) request.addRequestHeader("Referer", pageUrl)
        if (!incognito) CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PICTURES, name)
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }.onSuccess {
        Toast.makeText(context, "Saving image…", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Couldn't save the image", Toast.LENGTH_SHORT).show()
    }
}

// ── User agent picker ───────────────────────────────────────────────────────

/**
 * WebView default, this device's Chrome,
 * the built-in list and custom ones. From a tab, [showMakeDefault] lets the
 * choice become the default for new tabs.
 */
@Composable
internal fun UserAgentDialog(
    selectedKey: String,
    showMakeDefault: Boolean,
    onDismiss: () -> Unit,
    onSelect: (key: String, makeDefault: Boolean) -> Unit
) {
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(selectedKey) }
    var makeDefault by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    @Composable
    fun option(key: String, label: String, detail: String?, onDelete: (() -> Unit)? = null) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = key == selected, onClick = { selected = key }).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = key == selected, onClick = { selected = key })
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("User agent / Desktop mode") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                option(UserAgents.NONE, "WebView default", "No override")
                option(UserAgents.DEVICE_CHROME, "Chrome (this device)", "WebView without the app markers")
                if (settings.customUserAgent.isNotBlank()) option(UserAgents.LEGACY_CUSTOM, "Custom", settings.customUserAgent)
                settings.customUserAgents.forEach { ua ->
                    val key = UserAgents.CUSTOM_PREFIX + ua.id
                    option(key, ua.name, ua.value) {
                        CastHub.settingsStore.update { s ->
                            s.copy(
                                customUserAgents = s.customUserAgents.filterNot { it.id == ua.id },
                                userAgentKey = if (s.userAgentKey == key) "" else s.userAgentKey
                            )
                        }
                        if (selected == key) selected = UserAgents.NONE
                    }
                }
                UserAgents.builtIn.forEach { option(it.key, it.name, if (it.desktop) "Desktop" else "Mobile") }
                if (adding) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newValue, onValueChange = { newValue = it }, label = { Text("User agent") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Row {
                        TextButton(onClick = { adding = false }) { Text("Cancel") }
                        TextButton(
                            enabled = newName.isNotBlank() && newValue.isNotBlank(),
                            onClick = {
                                val ua = CustomUserAgent(UUID.randomUUID().toString(), newName.trim(), newValue.trim())
                                CastHub.settingsStore.update { it.copy(customUserAgents = it.customUserAgents + ua) }
                                selected = UserAgents.CUSTOM_PREFIX + ua.id
                                adding = false
                                newName = ""
                                newValue = ""
                            }
                        ) { Text("Add") }
                    }
                } else {
                    TextButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, null); Text(" Add custom user agent") }
                }
                if (showMakeDefault) CheckRow("Make default for new tabs", makeDefault) { makeDefault = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(selected, makeDefault || !showMakeDefault) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Saves [key] as the default UA (and leaves the older desktop switch off). */
internal fun saveDefaultUserAgent(key: String) {
    CastHub.settingsStore.update { it.copy(userAgentKey = key, desktopMode = false) }
}

// ── Home screen shortcut ────────────────────────────────────────────────────

/** Pins a launcher shortcut that opens [url] in the in-app browser. */
internal fun addToHomeScreen(context: Context, url: String, title: String, favicon: Bitmap?) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "Your launcher doesn't support shortcuts", Toast.LENGTH_SHORT).show()
        return
    }
    val label = title.ifBlank { Uri.parse(url).host?.removePrefix("www.") ?: "Web page" }
    val shortLabel = if (label.length > 10) label.take(9) + "…" else label
    val icon = favicon?.let { IconCompat.createWithBitmap(shortcutIcon(it)) }
        ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
    val intent = Intent(context, MainActivity::class.java)
        .setAction(BrowserManager.ACTION_OPEN_URL)
        .putExtra(BrowserManager.EXTRA_URL, url)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val info = ShortcutInfoCompat.Builder(context, "web-" + url.hashCode())
        .setShortLabel(shortLabel)
        .setLongLabel(label)
        .setIcon(icon)
        .setIntent(intent)
        .build()
    if (!ShortcutManagerCompat.requestPinShortcut(context, info, null)) {
        Toast.makeText(context, "Couldn't add the shortcut", Toast.LENGTH_SHORT).show()
    }
}

/** The page icon centred on a round light background (favicons are tiny). */
private fun shortcutIcon(favicon: Bitmap): Bitmap {
    val size = 192
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F3F4.toInt() })
    val inner = size / 2
    val left = (size - inner) / 2
    canvas.drawBitmap(favicon, null, Rect(left, left, left + inner, left + inner), Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun resourceLabel(resource: String): String = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "protected content playback"
    PermissionRequest.RESOURCE_MIDI_SYSEX -> "MIDI devices"
    else -> resource
}

private fun androidPermissionFor(resource: String): String? = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
    else -> null
}

private fun hasPermission(context: Context, permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun chosenUris(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) return null
    val clip = data.clipData
    if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
    return WebChromeClient.FileChooserParams.parseResult(resultCode, data)
}
