package com.example.harmony

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TopSongAdapter(
    private var items: List<TopSongStat>,
    private val onItemClick: (TopSongStat, Int) -> Unit
) : RecyclerView.Adapter<TopSongAdapter.TopSongViewHolder>() {

    class TopSongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val rankText: TextView = itemView.findViewById(R.id.rankText)
        val topSongTitle: TextView = itemView.findViewById(R.id.topSongTitle)
        val topSongArtist: TextView = itemView.findViewById(R.id.topSongArtist)
        val playCountText: TextView = itemView.findViewById(R.id.playCountText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopSongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_song, parent, false)
        return TopSongViewHolder(view)
    }

    override fun onBindViewHolder(holder: TopSongViewHolder, position: Int) {
        val item = items[position]
        holder.rankText.text = "#${position + 1}"
        holder.topSongTitle.text = item.title
        holder.topSongArtist.text = item.artist
        holder.playCountText.text = if (item.playCount == 1) "1 play" else "${item.playCount} plays"

        holder.itemView.setOnClickListener {
            onItemClick(item, position)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<TopSongStat>) {
        items = newItems
        notifyDataSetChanged()
    }
}