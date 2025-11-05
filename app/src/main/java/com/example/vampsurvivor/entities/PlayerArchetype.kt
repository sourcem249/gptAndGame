package com.example.vampsurvivor.entities

enum class PlayerArchetype(
    val maxHealth: Float,
    val damage: Float,
    val moveSpeed: Float,
    val attackCooldown: Float
) {
    SPEEDSTER(maxHealth = 80f, damage = 10f, moveSpeed = 260f, attackCooldown = 0.6f),
    TANK(maxHealth = 140f, damage = 12f, moveSpeed = 200f, attackCooldown = 0.8f),
    MAGE(maxHealth = 90f, damage = 16f, moveSpeed = 220f, attackCooldown = 0.7f);

    fun asSnapshot() = PlayerSnapshot(archetype = this)
}
