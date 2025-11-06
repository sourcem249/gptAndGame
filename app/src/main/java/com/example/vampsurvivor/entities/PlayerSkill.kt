package com.example.vampsurvivor.entities

import androidx.annotation.StringRes
import com.example.vampsurvivor.R

enum class PlayerSkill(@StringRes val titleRes: Int, val maxLevel: Int = MAX_LEVEL) {
    DAMAGE(R.string.upgrade_damage),
    ATTACK_SPEED(R.string.upgrade_attack_speed),
    MOVE_SPEED(R.string.upgrade_move_speed),
    DOUBLE_SHOT(R.string.upgrade_double_shot),
    SHOCKWAVE(R.string.upgrade_shockwave),
    REGENERATION(R.string.upgrade_regeneration),
    LASER_BARRAGE(R.string.upgrade_laser_barrage),
    BASE_PULSE(R.string.upgrade_base_pulse),
    PIERCING_SHOT(R.string.upgrade_piercing_shot),
    ORBITAL_BLADES(R.string.upgrade_orbital_blades);

    companion object {
        const val MAX_LEVEL = 5
    }
}

data class UpgradeChoice(
    val skill: PlayerSkill,
    val displayText: String
)
