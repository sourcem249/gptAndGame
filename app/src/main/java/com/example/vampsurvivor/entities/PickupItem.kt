package com.example.vampsurvivor.entities

data class PickupItem(
    var x: Float,
    var y: Float,
    var radius: Float,
    val xp: Int,
    val type: Type
) {
    enum class Type { XP_GEM, HEALING }
}
