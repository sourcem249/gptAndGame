package com.example.vampsurvivor.entities

data class Enemy(
    var x: Float,
    var y: Float,
    var radius: Float,
    var hp: Float,
    var moveSpeed: Float,
    var damage: Float,
    var isBoss: Boolean = false
)
