package com.example.harmony

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.ImageButton
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture

enum class SortField(val label: String) {
    TITLE("Song Name"),
    ARTIST("Artist Name"),
    ALBUM("Album Name"),
    FOLDER("Folder Name"),
    DATE_ADDED("Added Time"),
    PLAY_COUNT("Play Count"),
    YEAR("Year"),
    DURATION("Duration"),
    SIZE("File Size"),
    RANDOM("Random")
}
class LibraryActivity : AppCompatActivity() {

    private val allSongs = mutableListOf<Song>()
    private val displayedSongs = mutableListOf<Song>()
    private val activeQueue = mutableListOf<Song>()
    private lateinit var songAdapter: SongAdapter
    private lateinit var listeningTracker: ListeningTracker

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var currentIndex = -1
    private var countedCurrentItem = false
    private var pendingQueue: Pair<List<Song>, Int>? = null
    private var currentSortField = SortField.TITLE
    private var isAscending = true
    private lateinit var miniSongTitle: TextView
    private lateinit var miniSongArtist: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var searchInput: EditText

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            if (isPlaying) {
                recordCurrentSongPlayIfNeeded()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = mediaController ?: return
            val index = controller.currentMediaItemIndex
            countedCurrentItem = false
            if (index in activeQueue.indices) {
                currentIndex = index
                updateMiniPlayerUI(activeQueue[index])
                if (controller.isPlaying) {
                    recordCurrentSongPlayIfNeeded()
                }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffleButton.alpha = if (shuffleModeEnabled) 1.0f else 0.5f
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateRepeatButtonUI(repeatMode)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                loadLocalAudio()
            } else {
                Toast.makeText(this, "Audio permission is needed to find your songs", Toast.LENGTH_LONG).show()
            }
        }

    private val mostListenedLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val ids = result.data!!.getLongArrayExtra(MostListenedActivity.EXTRA_PLAYLIST_SONG_IDS)
                    ?: return@registerForActivityResult
                val startIndex = result.data!!.getIntExtra(MostListenedActivity.EXTRA_START_INDEX, 0)
                val songById = allSongs.associateBy { it.id }
                val resolvedSongs = ids.asSequence().mapNotNull { songById[it] }.toList()
                if (resolvedSongs.isNotEmpty()) {
                    val safeIndex = startIndex.coerceIn(0, resolvedSongs.lastIndex)
                    if (mediaController != null) {
                        startQueue(resolvedSongs, safeIndex)
                    } else {
                        pendingQueue = resolvedSongs to safeIndex
                        activeQueue.clear()
                        activeQueue.addAll(resolvedSongs)
                        currentIndex = safeIndex
                        countedCurrentItem = false
                        updateMiniPlayerUI(resolvedSongs[safeIndex])
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        listeningTracker = ListeningTracker(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.libraryMain)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        miniSongTitle = findViewById(R.id.miniSongTitle)
        miniSongArtist = findViewById(R.id.miniSongArtist)
        playPauseButton = findViewById(R.id.playPauseButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        val prevButton = findViewById<ImageButton>(R.id.prevButton)
        val nextButton = findViewById<ImageButton>(R.id.nextButton)
        val queueButton = findViewById<ImageButton>(R.id.queueButton)
        val sortButton = findViewById<Button>(R.id.sortButton)
        sortButton.setOnClickListener { showSortDialog() }
        searchInput = findViewById(R.id.searchInput)
        repeatButton = findViewById(R.id.repeatButton)

        repeatButton.setOnClickListener {
            val controller = mediaController ?: return@setOnClickListener
            val nextMode = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            controller.repeatMode = nextMode
            updateRepeatButtonUI(nextMode)

            val label = when (nextMode) {
                Player.REPEAT_MODE_ALL -> "Repeat queue"
                Player.REPEAT_MODE_ONE -> "Repeat current song"
                else -> "Repeat off"
            }
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.songsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(displayedSongs) { selectedSong ->
            val isSearching = searchInput.text.toString().trim().isNotEmpty()
            if (isSearching) {
                startQueue(listOf(selectedSong), startIndex = 0)
            } else {
                val indexInAll = allSongs.indexOfFirst { it.id == selectedSong.id }
                if (indexInAll != -1) {
                    startQueue(allSongs, startIndex = indexInAll)
                }
            }
        }
        recyclerView.adapter = songAdapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFiltersAndSort()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        val mostListenedButton = findViewById<Button>(R.id.mostListenedButton)
        mostListenedButton.setOnClickListener {
            mostListenedLauncher.launch(Intent(this, MostListenedActivity::class.java))
        }

        val wrapUpButton = findViewById<Button>(R.id.wrapUpButton)
        wrapUpButton.setOnClickListener {
            mostListenedLauncher.launch(Intent(this, WrappedActivity::class.java))
        }

        playPauseButton.setOnClickListener {
            val controller = mediaController ?: return@setOnClickListener
            if (activeQueue.isEmpty() && allSongs.isNotEmpty()) {
                startQueue(allSongs, startIndex = 0)
            } else if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
        }

        shuffleButton.setOnClickListener {
            val controller = mediaController ?: return@setOnClickListener
            val newShuffleState = !controller.shuffleModeEnabled
            controller.shuffleModeEnabled = newShuffleState
            shuffleButton.alpha = if (newShuffleState) 1.0f else 0.5f

            if (newShuffleState && activeQueue.isEmpty() && allSongs.isNotEmpty()) {
                val randomStart = allSongs.indices.random()
                startQueue(allSongs, startIndex = randomStart)
            }

            Toast.makeText(
                this,
                if (newShuffleState) "Shuffle on" else "Shuffle off",
                Toast.LENGTH_SHORT
            ).show()
        }

        prevButton.setOnClickListener {
            mediaController?.seekToPrevious()
        }

        nextButton.setOnClickListener {
            mediaController?.seekToNext()
        }

        queueButton.setOnClickListener {
            showQueueDialog()
        }

        checkAudioPermission()
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            mediaController = controller
            val queued = pendingQueue
            if (queued != null && controller != null) {
                pendingQueue = null
                startQueue(queued.first, queued.second)
            }
            controller?.addListener(playerListener)
            controller?.let {
                playPauseButton.setImageResource(
                    if (it.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                shuffleButton.alpha = if (it.shuffleModeEnabled) 1.0f else 0.5f
                if (it.currentMediaItemIndex in activeQueue.indices) {
                    currentIndex = it.currentMediaItemIndex
                    updateMiniPlayerUI(activeQueue[currentIndex])
                }
                updateRepeatButtonUI(it.repeatMode)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
    }

    private fun recordCurrentSongPlayIfNeeded() {
        if (countedCurrentItem) return
        if (currentIndex in activeQueue.indices) {
            listeningTracker.recordPlay(activeQueue[currentIndex])
            countedCurrentItem = true
        }
    }

    private fun startQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        activeQueue.clear()
        activeQueue.addAll(songs)
        currentIndex = startIndex
        countedCurrentItem = false

        val song = activeQueue[startIndex]
        updateMiniPlayerUI(song)

        val controller = mediaController ?: return
        val mediaItems = activeQueue.map { MediaItem.fromUri(it.uri) }
        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    private fun addSongToQueue(song: Song) {
        activeQueue.add(song)
        mediaController?.addMediaItem(MediaItem.fromUri(song.uri))
        Toast.makeText(this, "Added \"${song.title}\" to queue", Toast.LENGTH_SHORT).show()
    }

    private fun updateMiniPlayerUI(song: Song) {
        miniSongTitle.text = song.title
        miniSongArtist.text = song.artist
    }

    private fun showQueueDialog() {
        val labels = if (activeQueue.isEmpty()) {
            emptyArray()
        } else {
            activeQueue.mapIndexed { idx, song ->
                val prefix = if (idx == currentIndex) "▶ " else "${idx + 1}. "
                "$prefix${song.title} — ${song.artist}"
            }.toTypedArray()
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Current Queue (${activeQueue.size})")

        if (labels.isNotEmpty()) {
            builder.setItems(labels) { _, which ->
                currentIndex = which
                countedCurrentItem = false
                val selected = activeQueue[which]
                updateMiniPlayerUI(selected)
                mediaController?.seekTo(which, 0L)
                mediaController?.play()
            }
        } else {
            builder.setMessage("Your queue is empty. Tap Add Song to queue a track.")
        }

        builder
            .setPositiveButton("Add Song") { _, _ -> showAddSongToQueueDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateRepeatButtonUI(repeatMode: Int) {
        when (repeatMode) {
            Player.REPEAT_MODE_OFF -> {
                repeatButton.setImageResource(R.drawable.ic_repeat)
                repeatButton.alpha = 0.5f
            }
            Player.REPEAT_MODE_ALL -> {
                repeatButton.setImageResource(R.drawable.ic_repeat)
                repeatButton.alpha = 1.0f
            }
            Player.REPEAT_MODE_ONE -> {
                repeatButton.setImageResource(R.drawable.ic_repeat_one)
                repeatButton.alpha = 1.0f
            }
        }
    }

    private fun showAddSongToQueueDialog() {
        if (allSongs.isEmpty()) {
            Toast.makeText(this, "No songs available to add", Toast.LENGTH_SHORT).show()
            return
        }

        val songChoices = allSongs.map { "${it.title} — ${it.artist}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Add Song to Queue")
            .setItems(songChoices) { _, which ->
                addSongToQueue(allSongs[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

//    private fun filterSongs(query: String) {
//        val trimmed = query.trim().lowercase()
//        val matches = if (trimmed.isEmpty()) {
//            allSongs
//        } else {
//            allSongs.filter { song ->
//                song.title.lowercase().contains(trimmed) ||
//                        song.artist.lowercase().contains(trimmed)
//            }
//        }
//        displayedSongs.clear()
//        displayedSongs.addAll(matches)
//        songAdapter.updateSongs(displayedSongs)
//    }

    private fun checkAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadLocalAudio()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun loadLocalAudio() {
        allSongs.clear()
        displayedSongs.clear()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.SIZE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown Title"
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val duration = cursor.getLong(durationCol)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val album = cursor.getString(albumCol) ?: "Unknown Album"
                val rawRelPath = cursor.getString(relPathCol).orEmpty().trim('/')
                val folderName = rawRelPath.substringAfterLast('/', missingDelimiterValue = "")
                    .ifEmpty { rawRelPath.ifEmpty { "Music" } }
                val dateAddedSec = cursor.getLong(dateAddedCol)
                val releaseYear = cursor.getInt(yearCol)
                val sizeBytes = cursor.getLong(sizeCol)
                allSongs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        folderName = folderName,
                        durationMs = duration,
                        dateAddedSec = dateAddedSec,
                        releaseYear = releaseYear,
                        sizeBytes = sizeBytes,
                        uri = contentUri
                    )
                )
            }
        }

        displayedSongs.addAll(allSongs)
        songAdapter.updateSongs(displayedSongs)
        applyFiltersAndSort()
    }

    private fun showSortDialog() {
        val options = SortField.entries.map { field ->
            if (field == currentSortField) "✓ ${field.label}" else field.label
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Sort Library By")
            .setItems(options) { _, which ->
                val selected = SortField.entries[which]
                if (selected == SortField.RANDOM) {
                    currentSortField = SortField.RANDOM
                    applyFiltersAndSort()
                } else {
                    showSortDirectionDialog(selected)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSortDirectionDialog(field: SortField) {
        val (ascText, descText) = when (field) {
            SortField.TITLE, SortField.ARTIST, SortField.ALBUM, SortField.FOLDER ->
                "A → Z" to "Z → A"
            SortField.DATE_ADDED, SortField.YEAR ->
                "Old → New" to "New → Old"
            SortField.PLAY_COUNT, SortField.DURATION, SortField.SIZE ->
                "Low → High" to "High → Low"
            SortField.RANDOM -> return
        }

        val directions = arrayOf(descText, ascText)
        AlertDialog.Builder(this)
            .setTitle("Order (${field.label})")
            .setItems(directions) { _, which ->
                currentSortField = field
                isAscending = (which == 1)
                applyFiltersAndSort()
            }
            .show()
    }

    private fun applyFiltersAndSort() {
        val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (query.isEmpty()) {
            allSongs.toList()
        } else {
            allSongs.filter { song ->
                song.title.lowercase().contains(query) ||
                        song.artist.lowercase().contains(query) ||
                        song.album.lowercase().contains(query)
            }
        }

        val playCounts = if (currentSortField == SortField.PLAY_COUNT) {
            listeningTracker.getMostListenedSongs(limit = Int.MAX_VALUE)
                .associate { it.songId to it.playCount }
        } else {
            emptyMap()
        }

        val sorted = when (currentSortField) {
            SortField.RANDOM -> filtered.shuffled()
            SortField.TITLE -> if (isAscending) filtered.sortedBy { it.title.lowercase() } else filtered.sortedByDescending { it.title.lowercase() }
            SortField.ARTIST -> if (isAscending) filtered.sortedBy { it.artist.lowercase() } else filtered.sortedByDescending { it.artist.lowercase() }
            SortField.ALBUM -> if (isAscending) filtered.sortedBy { it.album.lowercase() } else filtered.sortedByDescending { it.album.lowercase() }
            SortField.FOLDER -> if (isAscending) filtered.sortedBy { it.folderName.lowercase() } else filtered.sortedByDescending { it.folderName.lowercase() }
            SortField.DATE_ADDED -> if (isAscending) filtered.sortedBy { it.dateAddedSec } else filtered.sortedByDescending { it.dateAddedSec }
            SortField.PLAY_COUNT -> if (isAscending) filtered.sortedBy { playCounts[it.id] ?: 0 } else filtered.sortedByDescending { playCounts[it.id] ?: 0 }
            SortField.YEAR -> if (isAscending) filtered.sortedBy { it.releaseYear } else filtered.sortedByDescending { it.releaseYear }
            SortField.DURATION -> if (isAscending) filtered.sortedBy { it.durationMs } else filtered.sortedByDescending { it.durationMs }
            SortField.SIZE -> if (isAscending) filtered.sortedBy { it.sizeBytes } else filtered.sortedByDescending { it.sizeBytes }
        }

        displayedSongs.clear()
        displayedSongs.addAll(sorted)
        songAdapter.updateSongs(displayedSongs)
    }
}