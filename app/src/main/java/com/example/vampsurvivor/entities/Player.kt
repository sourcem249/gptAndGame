package com.example.vampsurvivor.entities

data class Player(
    var x: Float,
    var y: Float,
    var radius: Float,
    var hp: Float,
    var maxHp: Float,
    var damage: Float,
    var moveSpeed: Float,
    var attackCooldown: Float,
    var attackTimer: Float = 0f,
    var experience: Int = 0,
    var level: Int = 1,
    var nextLevel: Int = 25
) {
    fun center() = Pair(x, y)

    fun gainXp(amount: Int, onLevel: (Int) -> Unit) {
        experience += amount
        while (experience >= nextLevel) {
            experience -= nextLevel
            level += 1
            nextLevel = (nextLevel * 1.25f).toInt().coerceAtLeast(25)
            onLevel(level)
        }
    }

    fun damage(amount: Float) {
        hp = (hp - amount).coerceAtLeast(0f)
    }

    fun heal(percent: Float) {
        hp = (hp + maxHp * percent).coerceAtMost(maxHp)
    }
}
