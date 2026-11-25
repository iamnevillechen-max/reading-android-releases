package io.legado.app.data.entities

import androidx.room.Embedded

/** Category discovery reads labels and the menu only, never every source's parsing libraries. */
data class ExploreSourceSpec(@Embedded val source: BookSourcePart, val menu: String?)
