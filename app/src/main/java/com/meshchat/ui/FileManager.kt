package com.meshchat.ui

import android.Manifest
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.share.MediaItem
import com.meshchat.share.MediaKind
import com.meshchat.share.Storage
import com.meshchat.share.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_FOLDER_FILES = 2000
private val FmBlue = Color(0xFF1E88E5)

/**
 * In-app file manager for the Share tab: switch between internal storage and SD card / USB, open folders, tick
 * single files or whole folders (a folder sends every file inside it), select everything in the current folder.
 */
@Composable
fun FileManager(vm: MainViewModel, onSystemPicker: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selectedUris = remember(selection) { selection.map { it.uri }.toSet() }

    // re-check access after the user comes back from the system settings screen
    var resumes by remember { mutableIntStateOf(0) }
    val owner = ctx as? LifecycleOwner
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    var granted by remember(resumes) { mutableStateOf(Storage.hasAccess(ctx)) }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = Storage.hasAccess(ctx) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("To browse all your folders (including SD card and USB) MeshChat needs access to your files.", color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (!Storage.openAccessSettings(ctx)) legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }) { Text("Allow file access") }
            OutlinedButton(onClick = onSystemPicker, modifier = Modifier.padding(top = 8.dp)) { Text("Or use the system file picker") }
        }
        return
    }

    val roots = remember(resumes) { Storage.roots(ctx) }
    var rootIdx by remember { mutableIntStateOf(0) }
    val root = roots[rootIdx.coerceIn(0, roots.lastIndex)]
    var dir by remember(root.dir.path) { mutableStateOf(root.dir) }
    val atRoot = dir.path == root.dir.path
    BackHandler(enabled = !atRoot) { dir = dir.parentFile ?: root.dir }

    val entries by produceState<List<File>?>(null, dir, resumes) {
        value = withContext(Dispatchers.IO) {
            (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }
    val folderCache = remember { mutableStateMapOf<String, List<MediaItem>>() }
    val scope = rememberCoroutineScope()

    fun toggleFolder(d: File) {
        scope.launch {
            val cached = folderCache[d.path]
            val files = cached ?: withContext(Dispatchers.IO) {
                d.walkTopDown().onEnter { it.canRead() }.filter { it.isFile && !it.name.startsWith(".") }
                    .take(MAX_FOLDER_FILES + 1).map(::toItem).toList()
            }.also { folderCache[d.path] = it }
            when {
                files.isEmpty() -> onMessage("This folder has no files")
                files.size > MAX_FOLDER_FILES -> onMessage("More than $MAX_FOLDER_FILES files in this folder. Open it and choose a smaller part.")
                else -> {
                    val have = vm.selection.value.map { s -> s.uri }.toSet()
                    if (files.all { it.uri in have }) vm.removeSelected(files) else vm.addSelected(files)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // storage switch
        if (roots.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                roots.forEachIndexed { i, r ->
                    if (i == rootIdx) Button(onClick = { rootIdx = i }) { Text(r.label, maxLines = 1) }
                    else OutlinedButton(onClick = { rootIdx = i }) { Text(r.label, maxLines = 1) }
                }
            }
        }
        // path + up
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !atRoot, onClick = { dir = dir.parentFile ?: root.dir }) { Text("⬆ Up") }
            Text(
                root.label.substringBefore(" (") + dir.path.removePrefix(root.dir.path),
                Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            val filesHere = entries?.filter { it.isFile }.orEmpty()
            if (filesHere.isNotEmpty()) {
                val all = filesHere.all { Uri.fromFile(it) in selectedUris }
                OutlinedButton(onClick = {
                    val items = filesHere.map(::toItem)
                    if (all) vm.removeSelected(items) else vm.addSelected(items)
                }) { Text(if (all) "Deselect all" else "Select all") }
            }
        }
        HorizontalDivider()
        val list = entries
        when {
            list == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            list.isEmpty() -> EmptyState("This folder is empty")
            else -> LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.path }) { f ->
                    val checked = if (f.isDirectory) folderCache[f.path]?.let { l -> l.isNotEmpty() && l.all { it.uri in selectedUris } } ?: false
                    else Uri.fromFile(f) in selectedUris
                    EntryRow(
                        f, checked,
                        onOpen = { if (f.isDirectory) dir = f else vm.toggleSelected(toItem(f)) },
                        onCheck = { if (f.isDirectory) toggleFolder(f) else vm.toggleSelected(toItem(f)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EntryRow(f: File, checked: Boolean, onOpen: () -> Unit, onCheck: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (f.isDirectory) "📁" else iconFor(f), fontSize = 26.sp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (f.isDirectory) "Folder · tick to send everything inside" else formatBytes(f.length()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        Box(Modifier.clickable(onClick = onCheck).padding(16.dp)) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (checked) FmBlue else Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
        }
    }
}

private fun mimeOf(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()).orEmpty()

private fun iconFor(f: File): String = when {
    mimeOf(f).startsWith("image/") -> "🖼"
    mimeOf(f).startsWith("video/") -> "🎬"
    mimeOf(f).startsWith("audio/") -> "🎵"
    else -> "📄"
}

private fun toItem(f: File): MediaItem {
    val mime = mimeOf(f)
    val kind = when {
        mime.startsWith("image/") -> MediaKind.PHOTO
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.FILE
    }
    return MediaItem(Uri.fromFile(f), f.name, f.length(), kind, f.lastModified())
}
