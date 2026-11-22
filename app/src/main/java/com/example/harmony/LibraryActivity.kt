package com.example.harmony

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture

class LibraryActivity : AppCompatActivity() {

    private val allSongs = mutableListOf<Song>()
    private val displayedSongs = mutableListOf<Song>()
    private val activeQueue = mutableListOf<Song>()
    private lateinit var songAdapter: SongAdapter

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var currentIndex = -1

    private lateinit var miniSongTitle: TextView
    private lateinit var miniSongArtist: TextView
    private lateinit var playPauseButton: Button
    private lateinit var searchInput: EditText

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPauseButton.text = if (isPlaying) "Pause" else "Play"
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = mediaController ?: return
            val index = controller.currentMediaItemIndex
            if (index in activeQueue.indices) {
                currentIndex = index
                updateMiniPlayerUI(activeQueue[index])
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        miniSongTitle = findViewById(R.id.miniSongTitle)
        miniSongArtist = findViewById(R.id.miniSongArtist)
        playPauseButton = findViewById(R.id.playPauseButton)
        val prevButton = findViewById<Button>(R.id.prevButton)
        val nextButton = findViewById<Button>(R.id.nextButton)
        val queueButton = findViewById<Button>(R.id.queueButton)
        searchInput = findViewById(R.id.searchInput)

        val recyclerView = findViewById<RecyclerView>(R.id.songsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(displayedSongs) { selectedSong ->
            val isSearching = searchInput.text.toString().trim().isNotEmpty()
            if (isSearching) {
                // Searched song: start a queue with ONLY this song
                startQueue(listOf(selectedSong), startIndex = 0)
            } else {
                // Browsing full list: start queue from this song and continue through the library
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
                filterSongs(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) {}
        })

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
            controller?.addListener(playerListener)
            controller?.let {
                playPauseButton.text = if (it.isPlaying) "Pause" else "Play"
                if (it.currentMediaItemIndex in activeQueue.indices) {
                    currentIndex = it.currentMediaItemIndex
                    updateMiniPlayerUI(activeQueue[currentIndex])
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
    }

    private fun startQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        activeQueue.clear()
        activeQueue.addAll(songs)
        currentIndex = startIndex

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

    private fun filterSongs(query: String) {
        val trimmed = query.trim().lowercase()
        val matches = if (trimmed.isEmpty()) {
            allSongs
        } else {
            allSongs.filter { song ->
                song.title.lowercase().contains(trimmed) ||
                        song.artist.lowercase().contains(trimmed)
            }
        }
        displayedSongs.clear()
        displayedSongs.addAll(matches)
        songAdapter.updateSongs(displayedSongs)
    }

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
            MediaStore.Audio.Media.DURATION
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown Title"
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val duration = cursor.getLong(durationCol)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                allSongs.add(Song(id, title, artist, duration, contentUri))
            }
        }

        displayedSongs.addAll(allSongs)
        songAdapter.updateSongs(displayedSongs)
    }
}