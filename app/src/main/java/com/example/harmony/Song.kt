package com.example.harmony

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val folderName: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    val releaseYear: Int,
    val sizeBytes: Long,
    val uri: Uri
)