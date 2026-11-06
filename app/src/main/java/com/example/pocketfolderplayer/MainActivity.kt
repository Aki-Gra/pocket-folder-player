package com.example.pocketfolderplayer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private var pickedFolder by mutableStateOf<SavedFolder?>(null)
    private var controller by mutableStateOf<MediaController?>(null)
    private var connection: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = DocumentFile.fromTreeUri(this, uri)?.name?.ifBlank { null }
                ?: Uri.decode(uri.lastPathSegment ?: "Music folder")
            val folder = SavedFolder(uri.toString(), name)
            FolderStore.add(this, folder)
            pickedFolder = folder
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open that folder. Choose another one.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        connection = future
        future.addListener({
            if (!isFinishing && !isDestroyed) {
                try {
                    controller = future.get()
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not start the audio player", Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                MusicApp(
                    controller = controller,
                    pickedFolder = pickedFolder,
                    onPickedHandled = { pickedFolder = null },
                    onPickFolder = { folderPicker.launch(null) }
                )
            }
        }
    }

    override fun onDestroy() {
        controller = null
        connection?.let { MediaController.releaseFuture(it) }
        connection = null
        super.onDestroy()
    }
}

private enum class Screen { FOLDERS, PLAYER, EQUALISER }
private data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val index: Int = -1,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_ALL,
    val filename: String = ""
)

private fun snapshot(controller: MediaController): PlaybackSnapshot {
    val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
    return PlaybackSnapshot(
        isPlaying = controller.isPlaying,
        index = controller.currentMediaItemIndex,
        position = controller.currentPosition.coerceAtLeast(0L),
        duration = duration,
        shuffle = controller.shuffleModeEnabled,
        repeat = controller.repeatMode,
        filename = controller.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
    )
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    return if (seconds >= 3600L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}

@Composable
private fun MusicApp(
    controller: MediaController?,
    pickedFolder: SavedFolder?,
    onPickedHandled: () -> Unit,
    onPickFolder: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.FOLDERS) }
    var folders by remember { mutableStateOf(FolderStore.all(context)) }
    var selectedFolder by remember { mutableStateOf<SavedFolder?>(null) }
    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var playerInfo by remember { mutableStateOf(PlaybackSnapshot()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadCounter by remember { mutableIntStateOf(0) }

    suspend fun openFolder(folder: SavedFolder, autoPlay: Boolean, restore: Boolean, refresh: Boolean = false) {
        if (controller == null) return
        val request = ++loadCounter
        loading = true
        error = null
        val result = runCatching { FolderScanner.scan(context, folder) }
        if (request != loadCounter) return
        loading = false
        val items = result.getOrElse {
            error = "Can't read ${folder.name}. Re-select it to restore access."
            return
        }
        selectedFolder = folder
        tracks = items
        val alreadyLoaded = !refresh && controller.mediaItemCount > 0 && FolderStore.selected(context) == folder.uri
        if (!alreadyLoaded) {
            // Save the folder first; selecting a different folder resets the old resume point.
            FolderStore.select(context, folder.uri)
            if (items.isEmpty()) {
                controller.pause()
                controller.clearMediaItems()
                error = "No MP3 or MP4 files in this folder. Subfolders aren't scanned."
            } else {
                val mediaItems = items.map { track ->
                    MediaItem.Builder()
                        .setMediaId(track.uri.toString())
                        .setUri(track.uri)
                        .setMimeType(if (track.filename.endsWith(".mp3", true)) "audio/mpeg" else "audio/mp4")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.filename).build())
                        .build()
                }
                val savedId = if (restore) FolderStore.savedTrack(context) else null
                val oldId = if (refresh) controller.currentMediaItem?.mediaId else null
                val chosenId = savedId ?: oldId
                val found = items.indexOfFirst { it.uri.toString() == chosenId }
                val index = found.coerceAtLeast(0)
                val position = when {
                    restore && found >= 0 -> FolderStore.savedPosition(context)
                    refresh && found >= 0 -> controller.currentPosition.coerceAtLeast(0L)
                    else -> 0L
                }
                val wasPlaying = if (refresh) controller.isPlaying else autoPlay
                controller.pause()
                controller.setMediaItems(mediaItems, index, position)
                controller.prepare()
                if (wasPlaying) controller.play()
                playerInfo = snapshot(controller)
            }
        } else if (autoPlay && items.isNotEmpty()) {
            controller.play()
        }
        screen = Screen.PLAYER
    }

    LaunchedEffect(controller) {
        if (controller != null) {
            val lastUri = FolderStore.selected(context)
            val lastFolder = FolderStore.all(context).firstOrNull { it.uri == lastUri }
            if (lastFolder != null) openFolder(lastFolder, autoPlay = false, restore = true)
        }
    }

    LaunchedEffect(pickedFolder, controller) {
        if (pickedFolder != null && controller != null) {
            folders = FolderStore.all(context)
            openFolder(pickedFolder, autoPlay = true, restore = false)
            onPickedHandled()
        }
    }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playerInfo = snapshot(controller)
            }
            override fun onPlayerError(errorValue: androidx.media3.common.PlaybackException) {
                error = "Can't play this file (${errorValue.errorCodeName}). Try the next track."
            }
        }
        controller.addListener(listener)
        playerInfo = snapshot(controller)
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(controller) {
        while (controller != null) {
            playerInfo = snapshot(controller)
            delay(500)
        }
    }

    val equalizer = if (controller != null) PlaybackService.active?.equalizer else null
    Scaffold(
        bottomBar = {
            Column {
                if (screen != Screen.PLAYER && controller != null && playerInfo.filename.isNotBlank()) {
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { screen = Screen.PLAYER }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null)
                            Text(playerInfo.filename, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { if (playerInfo.isPlaying) controller.pause() else controller.play() }) {
                                Icon(if (playerInfo.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playerInfo.isPlaying) "Pause" else "Play")
                            }
                        }
                    }
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = screen == Screen.FOLDERS, onClick = { screen = Screen.FOLDERS },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) }, label = { Text("Folders") }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.PLAYER, onClick = { screen = Screen.PLAYER },
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = null) }, label = { Text("Player") }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.EQUALISER, onClick = { screen = Screen.EQUALISER },
                        icon = { Icon(Icons.Default.Tune, contentDescription = null) }, label = { Text("Equaliser") }
                    )
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(16.dp))
            if (error != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error.orEmpty(), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { error = null }) { Text("Dismiss") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            when (screen) {
                Screen.FOLDERS -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("My folders", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Button(onClick = onPickFolder, enabled = controller != null) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                    Text("One folder, one playlist. Only MP3 and MP4 files directly inside each folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    if (controller == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (folders.isEmpty()) {
                        EmptyMessage("No music folders saved yet", "Tap Add and choose a folder containing MP3 or MP4 files.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(folders, key = { it.uri }) { folder ->
                                ElevatedCard(onClick = { scope.launch { openFolder(folder, autoPlay = true, restore = false) } }) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(folder.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                            if (folder.uri == selectedFolder?.uri) Text("Current playlist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(onClick = {
                                            FolderStore.remove(context, folder.uri)
                                            folders = FolderStore.all(context)
                                            if (folder.uri == selectedFolder?.uri) {
                                                controller?.pause()
                                                controller?.clearMediaItems()
                                                tracks = emptyList()
                                                selectedFolder = null
                                                screen = Screen.FOLDERS
                                            }
                                        }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${folder.name} from favourites")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Screen.PLAYER -> {
                    Text("Now playing", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (selectedFolder == null) {
                        EmptyMessage("No folder selected", "Select a saved folder to start your playlist.")
                        Button(onClick = { screen = Screen.FOLDERS }) { Text("Choose folder") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedFolder?.name.orEmpty(), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { selectedFolder?.let { scope.launch { openFolder(it, autoPlay = false, restore = false, refresh = true) } } }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh folder")
                            }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(18.dp))
                        Text(playerInfo.filename.ifEmpty { "No track selected" }, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        var scrubMs by remember { mutableFloatStateOf(0f) }
                        var scrubbing by remember { mutableStateOf(false) }
                        val maxPosition = playerInfo.duration.coerceAtLeast(1L).toFloat()
                        Slider(
                            value = if (scrubbing) scrubMs.coerceIn(0f, maxPosition) else playerInfo.position.toFloat().coerceIn(0f, maxPosition),
                            onValueChange = { scrubMs = it; scrubbing = true },
                            onValueChangeFinished = {
                                controller?.seekTo(scrubMs.toLong())
                                scrubbing = false
                            },
                            valueRange = 0f..maxPosition,
                            enabled = controller != null && playerInfo.duration > 0
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(if (scrubbing) scrubMs.toLong() else playerInfo.position), style = MaterialTheme.typography.labelMedium)
                            Text(formatTime(playerInfo.duration), style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = { controller?.shuffleModeEnabled = !playerInfo.shuffle }, enabled = tracks.isNotEmpty()) {
                                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (playerInfo.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                controller?.let { if (it.currentPosition > 3_000L) it.seekTo(0L) else it.seekToPreviousMediaItem() }
                            }, enabled = tracks.isNotEmpty()) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(32.dp))
                            }
                            FilledIconButton(onClick = {
                                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            }, modifier = Modifier.size(62.dp), enabled = tracks.isNotEmpty()) {
                                Icon(if (playerInfo.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playerInfo.isPlaying) "Pause" else "Play", modifier = Modifier.size(36.dp))
                            }
                            IconButton(onClick = { controller?.seekToNextMediaItem() }, enabled = tracks.isNotEmpty()) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next track", modifier = Modifier.size(32.dp))
                            }
                            IconButton(onClick = {
                                controller?.repeatMode = when (playerInfo.repeat) {
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                                    else -> Player.REPEAT_MODE_ALL
                                }
                            }, enabled = tracks.isNotEmpty()) {
                                Icon(if (playerInfo.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                    contentDescription = when (playerInfo.repeat) {
                                        Player.REPEAT_MODE_ONE -> "Repeat one"
                                        Player.REPEAT_MODE_ALL -> "Repeat all"
                                        else -> "Repeat off"
                                    }, tint = if (playerInfo.repeat == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Text("Playlist (${tracks.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        if (tracks.isEmpty()) Text("No supported music files found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            itemsIndexed(tracks, key = { _, track -> track.uri.toString() }) { index, track ->
                                val selected = index == playerInfo.index
                                ListItem(
                                    headlineContent = { Text(track.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                    leadingContent = { Icon(if (selected && playerInfo.isPlaying) Icons.Default.GraphicEq else Icons.Default.MusicNote, contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                                    modifier = Modifier.clickable {
                                        controller?.seekTo(index, 0L)
                                        controller?.play()
                                    }
                                )
                            }
                        }
                    }
                }
                Screen.EQUALISER -> {
                    Text("Equaliser", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Adjust the sound for this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    if (equalizer == null || !equalizer.supported) {
                        EmptyMessage("Equaliser not ready", "Start playing a song to activate the audio equaliser. Some devices don't support Android's equaliser effects.")
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable equaliser", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(checked = equalizer.enabled, onCheckedChange = equalizer::updateEnabled)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Presets", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            equalizer.presets.forEach { name ->
                                FilterChip(
                                    selected = equalizer.preset == name,
                                    onClick = { equalizer.choosePreset(name) },
                                    label = { Text(name) }, enabled = equalizer.enabled
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(if (equalizer.preset == "Custom") "Custom settings" else "${equalizer.preset} settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            itemsIndexed(equalizer.bandLevels) { i, value ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(equalizer.frequencies.getOrElse(i) { "Band ${i + 1}" }, modifier = Modifier.width(75.dp), style = MaterialTheme.typography.labelMedium)
                                    Slider(value = value.toFloat(), onValueChange = { equalizer.setBand(i, it.toInt()) },
                                        valueRange = 0f..100f, modifier = Modifier.weight(1f), enabled = equalizer.enabled)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Equaliser bands are provided by the device. Presets are mapped to the available frequencies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.QueueMusic, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
