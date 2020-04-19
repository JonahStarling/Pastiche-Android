package com.jonahstarling.pastiche

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.cell_artwork.view.*

class ArtworkAdapter(private val context: Context, private val artworks: List<Int>): RecyclerView.Adapter<ArtworkAdapter.ArtworkViewHolder>() {
    var artAdapterListener: OnArtSelectedListener? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtworkViewHolder {
        return ArtworkViewHolder(LayoutInflater.from(context).inflate(R.layout.cell_artwork, parent, false))
    }

    override fun getItemCount(): Int = artworks.size

    override fun onBindViewHolder(holder: ArtworkViewHolder, position: Int) {
        holder.image.setImageResource(artworks[position])
        holder.itemView.setOnClickListener {
            artAdapterListener?.onArtworkSelected(artworks[position])
        }
    }

    interface OnArtSelectedListener {
        fun onArtworkSelected(id: Int)
    }

    class ArtworkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.image
    }
}