package com.example.harmony

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class PlayEvent(
    val songId: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val timestampMs: Long
) {
    val year: Int
        get() = Calendar.getInstance().apply { timeInMillis = timestampMs }.get(Calendar.YEAR)

    val month: Int
        get() = Calendar.getInstance().apply { timeInMillis = timestampMs }.get(Calendar.MONTH) // 0..11
}

data class TopSongStat(
    val songId: Long,
    val title: String,
    val artist: String,
    val playCount: Int
)

data class TopArtistStat(
    val artist: String,
    val playCount: Int
)

data class YearlyWrapUpSummary(
    val year: Int,
    val totalPlays: Int,
    val uniqueSongsCount: Int,
    val topSongs: List<TopSongStat>,
    val topArtists: List<TopArtistStat>,
    val peakMonthName: String?
)

class ListeningTracker(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordPlay(song: Song, timestampMs: Long = System.currentTimeMillis()) {
        val events = getAllEvents().toMutableList()
        events.add(
            PlayEvent(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                durationMs = song.durationMs,
                timestampMs = timestampMs
            )
        )
        saveEvents(events)
    }

    fun getAllEvents(): List<PlayEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        PlayEvent(
                            songId = obj.optLong("songId", -1L),
                            title = obj.optString("title", "Unknown Title"),
                            artist = obj.optString("artist", "Unknown Artist"),
                            durationMs = obj.optLong("durationMs", 0L),
                            timestampMs = obj.optLong("timestampMs", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getMostListenedSongs(limit: Int = 10): List<TopSongStat> {
        return getAllEvents()
            .groupBy { it.songId }
            .map { (songId, plays) ->
                val latest = plays.last()
                TopSongStat(
                    songId = songId,
                    title = latest.title,
                    artist = latest.artist,
                    playCount = plays.size
                )
            }
            .sortedByDescending { it.playCount }
            .take(limit)
    }

    fun getMostListenedArtists(limit: Int = 10): List<TopArtistStat> {
        return getAllEvents()
            .groupBy { it.artist }
            .map { (artist, plays) ->
                TopArtistStat(
                    artist = artist,
                    playCount = plays.size
                )
            }
            .sortedByDescending { it.playCount }
            .take(limit)
    }

    fun getYearlyWrapUp(year: Int): YearlyWrapUpSummary {
        val yearEvents = getAllEvents().filter { it.year == year }
        val topSongs = yearEvents
            .groupBy { it.songId }
            .map { (songId, plays) ->
                val latest = plays.last()
                TopSongStat(
                    songId = songId,
                    title = latest.title,
                    artist = latest.artist,
                    playCount = plays.size
                )
            }
            .sortedByDescending { it.playCount }
            .take(5)

        val topArtists = yearEvents
            .groupBy { it.artist }
            .map { (artist, plays) ->
                TopArtistStat(artist = artist, playCount = plays.size)
            }
            .sortedByDescending { it.playCount }
            .take(5)

        val peakMonthIndex = yearEvents
            .groupingBy { it.month }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val monthNames = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        return YearlyWrapUpSummary(
            year = year,
            totalPlays = yearEvents.size,
            uniqueSongsCount = yearEvents.map { it.songId }.distinct().size,
            topSongs = topSongs,
            topArtists = topArtists,
            peakMonthName = peakMonthIndex?.let { monthNames.getOrElse(it) { null } }
        )
    }

    private fun saveEvents(events: List<PlayEvent>) {
        val array = JSONArray()
        for (event in events) {
            array.put(
                JSONObject().apply {
                    put("songId", event.songId)
                    put("title", event.title)
                    put("artist", event.artist)
                    put("durationMs", event.durationMs)
                    put("timestampMs", event.timestampMs)
                }
            )
        }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "harmony_listening_stats"
        private const val KEY_EVENTS = "play_events_json"
    }
}