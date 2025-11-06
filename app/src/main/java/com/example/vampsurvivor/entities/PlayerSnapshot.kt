package com.example.vampsurvivor.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayerSnapshot(
    val archetype: PlayerArchetype = PlayerArchetype.SPEEDSTER,
    val level: Int = 1,
    val experience: Int = 0,
    val nextLevel: Int = 25,
    val hp: Float = archetype.maxHealth,
    val maxHp: Float = archetype.maxHealth,
    val damage: Float = archetype.damage,
    val attackCooldown: Float = archetype.attackCooldown,
    val moveSpeed: Float = archetype.moveSpeed,
    val skillLevels: List<Int> = emptyList(),
    val wave: Int = 1,
    val isRunning: Boolean = false
) : Parcelable {
    companion object {
        fun default() = PlayerSnapshot()
    }
}
