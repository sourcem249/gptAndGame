package com.example.vampsurvivor.systems

import android.content.Context
import androidx.core.content.edit
import com.example.vampsurvivor.entities.PlayerArchetype
import com.example.vampsurvivor.entities.PlayerSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GameStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("game_state", Context.MODE_PRIVATE)

    suspend fun persist(snapshot: PlayerSnapshot) = withContext(Dispatchers.IO) {
        prefs.edit {
            putString(KEY_ARCHETYPE, snapshot.archetype.name)
            putInt(KEY_LEVEL, snapshot.level)
            putInt(KEY_XP, snapshot.experience)
            putInt(KEY_NEXT_LEVEL, snapshot.nextLevel)
            putFloat(KEY_HP, snapshot.hp)
            putFloat(KEY_MAX_HP, snapshot.maxHp)
            putFloat(KEY_DAMAGE, snapshot.damage)
            putFloat(KEY_COOLDOWN, snapshot.attackCooldown)
            putFloat(KEY_MOVE_SPEED, snapshot.moveSpeed)
            putInt(KEY_WAVE, snapshot.wave)
            putBoolean(KEY_RUNNING, snapshot.isRunning)
            putString(KEY_SKILLS, snapshot.skillLevels.joinToString(separator = ","))
        }
    }

    suspend fun loadSnapshot(): PlayerSnapshot? = withContext(Dispatchers.IO) {
        val archetypeName = prefs.getString(KEY_ARCHETYPE, null) ?: return@withContext null
        val archetype = runCatching { PlayerArchetype.valueOf(archetypeName) }.getOrElse { PlayerArchetype.SPEEDSTER }
        val skillLevels = prefs.getString(KEY_SKILLS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.toList()
            ?: emptyList()
        PlayerSnapshot(
            archetype = archetype,
            level = prefs.getInt(KEY_LEVEL, 1),
            experience = prefs.getInt(KEY_XP, 0),
            nextLevel = prefs.getInt(KEY_NEXT_LEVEL, 25),
            hp = prefs.getFloat(KEY_HP, archetype.maxHealth),
            maxHp = prefs.getFloat(KEY_MAX_HP, archetype.maxHealth),
            damage = prefs.getFloat(KEY_DAMAGE, archetype.damage),
            attackCooldown = prefs.getFloat(KEY_COOLDOWN, archetype.attackCooldown),
            moveSpeed = prefs.getFloat(KEY_MOVE_SPEED, archetype.moveSpeed),
            skillLevels = skillLevels,
            wave = prefs.getInt(KEY_WAVE, 1),
            isRunning = prefs.getBoolean(KEY_RUNNING, false)
        )
    }

    companion object {
        private const val KEY_ARCHETYPE = "archetype"
        private const val KEY_LEVEL = "level"
        private const val KEY_XP = "xp"
        private const val KEY_NEXT_LEVEL = "nextLevel"
        private const val KEY_HP = "hp"
        private const val KEY_MAX_HP = "maxHp"
        private const val KEY_DAMAGE = "damage"
        private const val KEY_COOLDOWN = "cooldown"
        private const val KEY_MOVE_SPEED = "moveSpeed"
        private const val KEY_WAVE = "wave"
        private const val KEY_RUNNING = "running"
        private const val KEY_SKILLS = "skills"
    }
}
