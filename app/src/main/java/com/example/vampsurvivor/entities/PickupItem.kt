package com.example.vampsurvivor.entities

data class PickupItem(
    var x: Float,
    var y: Float,
    var radius: Float,
    val xp: Int,
    val type: Type,
    var vx: Float = 0f,
    var vy: Float = 0f
) {
    enum class Type { XP_GEM, HEALING }
}
