package com.example.vampsurvivor.entities

data class Projectile(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var radius: Float,
    var damage: Float,
    var lifetime: Float
)
