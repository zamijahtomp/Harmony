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
import java.util.Calendar

class WrappedActivity : AppCompatActivity() {

    private lateinit var listeningTracker: ListeningTracker
    private var topSongs: List<TopSongStat> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrapped)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.wrappedMain)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        listeningTracker = ListeningTracker(this)
        val summary = listeningTracker.getYearlyWrapUp(currentYear)
        topSongs = summary.topSongs

        val backButton = findViewById<Button>(R.id.backFromWrappedButton)
        val playTopButton = findViewById<Button>(R.id.playWrappedTopButton)
        val wrappedTitle = findViewById<TextView>(R.id.wrappedTitle)
        val totalPlaysText = findViewById<TextView>(R.id.totalPlaysText)
        val peakMonthText = findViewById<TextView>(R.id.peakMonthText)
        val topArtistsText = findViewById<TextView>(R.id.wrappedTopArtistsText)
        val emptyText = findViewById<TextView>(R.id.wrappedEmptyText)
        val recyclerView = findViewById<RecyclerView>(R.id.wrappedTopSongsRecyclerView)

        backButton.setOnClickListener { finish() }

        wrappedTitle.text = "$currentYear Wrap-Up"
        totalPlaysText.text =
            "${summary.totalPlays} total plays across ${summary.uniqueSongsCount} songs"
        peakMonthText.text =
            "Peak listening month: ${summary.peakMonthName ?: "None yet"}"

        if (summary.topArtists.isNotEmpty()) {
            val artistsFormatted = summary.topArtists.joinToString(" • ") {
                "${it.artist} (${it.playCount})"
            }
            topArtistsText.text = "Top Artists: $artistsFormatted"
        } else {
            topArtistsText.text = "Top Artists: None yet"
        }

        if (topSongs.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            playTopButton.isEnabled = false
            playTopButton.alpha = 0.5f
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = TopSongAdapter(topSongs) { _, index ->
            returnPlaylistSelection(startIndex = index)
        }

        playTopButton.setOnClickListener {
            if (topSongs.isEmpty()) {
                Toast.makeText(this, "No top songs for $currentYear yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            returnPlaylistSelection(startIndex = 0)
        }
    }

    private fun returnPlaylistSelection(startIndex: Int) {
        val songIds = topSongs.map { it.songId }.toLongArray()
        val resultIntent = Intent().apply {
            putExtra(MostListenedActivity.EXTRA_PLAYLIST_SONG_IDS, songIds)
            putExtra(MostListenedActivity.EXTRA_START_INDEX, startIndex)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}