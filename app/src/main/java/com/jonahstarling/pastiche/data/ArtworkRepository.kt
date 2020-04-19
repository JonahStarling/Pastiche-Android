package com.jonahstarling.pastiche.data

import android.graphics.drawable.Drawable
import com.jonahstarling.pastiche.R

class ArtworkRepository {
    companion object {
        fun localArtworks(): List<Int> {
            return listOf(
                R.drawable.abstract_speed_sound,
                R.drawable.bathing_men,
                R.drawable.beheading_of_saint_paul,
                R.drawable.lego,
                R.drawable.pablo_straw_hat,
                R.drawable.red_rectangle,
                R.drawable.salad_oil,
                R.drawable.scream,
                R.drawable.sketchbook_of_greek_and_near_east,
                R.drawable.starry_night,
                R.drawable.stripes,
                R.drawable.sunday,
                R.drawable.valley_with_firs,
                R.drawable.wave_hokusai,
                R.drawable.william_morris
            )
        }
    }
}