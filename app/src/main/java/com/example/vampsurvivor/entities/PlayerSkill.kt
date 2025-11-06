package com.example.vampsurvivor.entities

import androidx.annotation.StringRes
import com.example.vampsurvivor.R

enum class PlayerSkill(@StringRes val titleRes: Int, val maxLevel: Int) {
    DAMAGE(R.string.upgrade_damage, 5),
    ATTACK_SPEED(R.string.upgrade_attack_speed, 5),
    MOVE_SPEED(R.string.upgrade_move_speed, 5),
    DOUBLE_SHOT(R.string.upgrade_double_shot, 1),
    SHOCKWAVE(R.string.upgrade_shockwave, 3),
    REGENERATION(R.string.upgrade_regeneration, 3);
}

data class UpgradeChoice(
    val skill: PlayerSkill,
    val displayText: String
)
