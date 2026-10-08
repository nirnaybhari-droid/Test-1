package com.example.isoextractor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent?.data
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { IsoApp(initial) } } }
    }
}

private class Pick(val entry: IsoEntry?)   // entry == null -> extract everything

@Composable
fun IsoApp(initial: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reader by remember { mutableStateOf<IsoReader?>(null) }
    var title by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(listOf<IsoEntry>()) }
    var entries by remember { mutableStateOf(listOf<IsoEntry>()) }
    var status by remember { mutableStateOf("Open an ISO file to begin") }
    var busy by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<Pick?>(null) }

    fun load(uri: Uri) = scope.launch {
        status = "Reading…"
        try {
            val r = withContext(Dispatchers.IO) {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open file")
                try { IsoReader(pfd) } catch (e: Exception) { pfd.close(); throw e }
            }
            reader?.close()
            reader = r
            path = emptyList()
            title = displayName(ctx, uri)
            status = ""
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        }
    }

    fun extract(tree: Uri) = scope.launch {
        val r = reader ?: return@launch
        val target = pick?.entry
        busy = true
        try {
            withContext(Dispatchers.IO) {
                val dest = DocumentFile.fromTreeUri(ctx, tree) ?: error("Bad folder")
                val items = if (target == null) r.list(r.root()) else listOf(target)
                items.forEach { extractEntry(ctx, r, it, dest) { n -> status = "Extracting $n" } }
            }
            status = "Done ✓"
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        }
        busy = false
    }

    val openIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) extract(uri)
    }

    LaunchedEffect(Unit) { initial?.let { load(it) } }
    LaunchedEffect(reader, path) {
        entries = reader?.let { r ->
            withContext(Dispatchers.IO) { r.list(path.lastOrNull() ?: r.root()) }
        } ?: emptyList()
    }
    BackHandler(path.isNotEmpty()) { path = path.dropLast(1) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            if (title.isEmpty()) "ISO Extractor" else title,
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openIso.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Open ISO") }
            if (reader != null) OutlinedButton(
                onClick = { pick = Pick(null); pickDest.launch(null) }, enabled = !busy
            ) { Text("Extract all") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (status.isNotEmpty()) Text(status, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        if (reader != null) Text("/" + path.joinToString("/") { it.name }, style = MaterialTheme.typography.labelMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(entries) { e ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = e.isDir && !busy) { path = path + e }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (e.isDir) "📁" else "📄")
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!e.isDir) Text(fmt(e.size), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { pick = Pick(e); pickDest.launch(null) }, enabled = !busy) { Text("Extract") }
                }
            }
        }
    }
}

private fun extractEntry(ctx: Context, r: IsoReader, e: IsoEntry, dest: DocumentFile, onFile: (String) -> Unit) {
    if (e.isDir) {
        val d = dest.findFile(e.name)?.takeIf { it.isDirectory }
            ?: dest.createDirectory(e.name) ?: error("Can't create folder ${e.name}")
        r.list(e).forEach { extractEntry(ctx, r, it, d, onFile) }
    } else {
        onFile(e.name)
        val f = dest.createFile("application/octet-stream", e.name) ?: error("Can't create ${e.name}")
        ctx.contentResolver.openOutputStream(f.uri)?.use { r.copy(e, it) } ?: error("Can't write ${e.name}")
    }
}

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "disc.iso"

private fun fmt(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1048576 -> "%.1f KB".format(b / 1024.0)
    b < 1073741824 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
