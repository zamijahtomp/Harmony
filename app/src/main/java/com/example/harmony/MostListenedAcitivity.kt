package com.example.harmony

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MostListenedActivity : AppCompatActivity() {

    private lateinit var listeningTracker: ListeningTracker
    private lateinit var adapter: TopSongAdapter
    private var topSongs: List<TopSongStat> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_most_listened)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mostListenedMain)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        listeningTracker = ListeningTracker(this)
        topSongs = listeningTracker.getMostListenedSongs(limit = 20)
        val topArtists = listeningTracker.getMostListenedArtists(limit = 3)

        val backButton = findViewById<Button>(R.id.backToLibraryButton)
        val playAllTopButton = findViewById<Button>(R.id.playAllTopButton)
        val topArtistsSummary = findViewById<TextView>(R.id.topArtistsSummary)
        val emptyStatsText = findViewById<TextView>(R.id.emptyStatsText)
        val recyclerView = findViewById<RecyclerView>(R.id.mostListenedRecyclerView)

        backButton.setOnClickListener { finish() }

        if (topArtists.isNotEmpty()) {
            val artistLine = topArtists.joinToString(" • ") { "${it.artist} (${it.playCount})" }
            topArtistsSummary.text = "Top Artists: $artistLine"
        } else {
            topArtistsSummary.text = "Top Artists: None yet"
        }

        if (topSongs.isEmpty()) {
            emptyStatsText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            playAllTopButton.isEnabled = false
            playAllTopButton.alpha = 0.5f
        } else {
            emptyStatsText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TopSongAdapter(topSongs) { _, index ->
            returnPlaylistSelection(startIndex = index)
        }
        recyclerView.adapter = adapter

        playAllTopButton.setOnClickListener {
            if (topSongs.isEmpty()) {
                Toast.makeText(this, "No top songs to queue yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            returnPlaylistSelection(startIndex = 0)
        }
    }

    private fun returnPlaylistSelection(startIndex: Int) {
        val songIds = topSongs.map { it.songId }.toLongArray()
        val resultIntent = Intent().apply {
            putExtra(EXTRA_PLAYLIST_SONG_IDS, songIds)
            putExtra(EXTRA_START_INDEX, startIndex)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_PLAYLIST_SONG_IDS = "extra_playlist_song_ids"
        const val EXTRA_START_INDEX = "extra_start_index"
    }
}