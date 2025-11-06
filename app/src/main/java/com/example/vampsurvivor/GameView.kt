package com.example.vampsurvivor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.res.ResourcesCompat
import com.example.vampsurvivor.entities.Enemy
import com.example.vampsurvivor.entities.Obstacle
import com.example.vampsurvivor.entities.PickupItem
import com.example.vampsurvivor.entities.Player
import com.example.vampsurvivor.entities.PlayerSkill
import com.example.vampsurvivor.entities.PlayerSnapshot
import com.example.vampsurvivor.entities.UpgradeChoice
import com.example.vampsurvivor.entities.Projectile
import com.example.vampsurvivor.systems.GameLoopController
import com.example.vampsurvivor.systems.VirtualJoystick
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val playerPaint = Paint().apply { color = Color.CYAN }
    private val enemyPaint = Paint().apply { color = Color.RED }
    private val bossPaint = Paint().apply { color = Color.MAGENTA }
    private val projectilePaint = Paint().apply { color = Color.YELLOW }
    private val xpPaint = Paint().apply { color = Color.GREEN }
    private val obstaclePaint = Paint().apply {
        color = Color.argb(255, 45, 45, 45)
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(120, 70, 70, 70)
        strokeWidth = 2f
    }
    private val joystick = VirtualJoystick(
        Paint().apply {
            color = ResourcesCompat.getColor(resources, R.color.joystick_base, null)
            alpha = 128
        },
        Paint().apply {
            color = ResourcesCompat.getColor(resources, R.color.joystick_knob, null)
        },
        baseRadius = 160f,
        knobRadius = 80f
    )

    private var callbacks: GameLoopController.Callbacks? = null
    private var controller: GameLoopController? = null
    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var startRequested = false

    private var player: Player? = null
    private val enemies = mutableListOf<Enemy>()
    private val projectiles = mutableListOf<Projectile>()
    private val pickups = mutableListOf<PickupItem>()
    private val obstacles = mutableListOf<Obstacle>()

    private var arenaWidth = 0
    private var arenaHeight = 0
    private val worldWidth = 6000f
    private val worldHeight = 6000f
    private var cameraX = 0f
    private var cameraY = 0f

    private var spawnTimer = 0f
    private var waveTimer = 0f
    private var bossSpawned = false
    private var wave = 1
    private var saveTimer = 0f

    private var awaitingUpgrade = false
    private var lastSnapshot = PlayerSnapshot.default()
    private val skillLevels = IntArray(PlayerSkill.values().size)
    private var shockwaveTimer = 0f

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun initialize(snapshot: PlayerSnapshot, callbacks: GameLoopController.Callbacks, controller: GameLoopController) {
        this.callbacks = callbacks
        this.controller = controller
        resetState(snapshot)
        val radius = 48f
        val centerX = worldWidth / 2f
        val centerY = worldHeight / 2f
        player = Player(
            x = centerX,
            y = centerY,
            radius = radius,
            hp = snapshot.hp,
            maxHp = snapshot.maxHp,
            damage = snapshot.damage,
            moveSpeed = snapshot.moveSpeed,
            attackCooldown = snapshot.attackCooldown,
            level = snapshot.level,
            experience = snapshot.experience,
            nextLevel = snapshot.nextLevel
        )
        updateCamera()
    }

    fun startLoop(): Boolean {
        startRequested = true
        if (thread?.isAlive == true) {
            return resumeLoop()
        }
        if (!holder.surface.isValid) {
            return false
        }
        running = true
        paused = false
        thread = Thread(this).also { it.start() }
        return true
    }

    fun pauseLoop() {
        if (!running) return
        paused = true
    }

    fun resumeLoop(): Boolean {
        if (!running || awaitingUpgrade) {
            return false
        }
        paused = false
        return true
    }

    fun stopLoop() {
        running = false
        startRequested = false
        thread?.let { loopThread ->
            try {
                loopThread.join(500)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (loopThread.isAlive) {
                    loopThread.interrupt()
                }
            }
        }
        thread = null
    }

    fun isPaused(): Boolean = paused

    fun snapshot(): PlayerSnapshot {
        val player = player ?: return lastSnapshot
        lastSnapshot = PlayerSnapshot(
            archetype = lastSnapshot.archetype,
            level = player.level,
            experience = player.experience,
            nextLevel = player.nextLevel,
            hp = player.hp,
            maxHp = player.maxHp,
            damage = player.damage,
            attackCooldown = player.attackCooldown,
            moveSpeed = player.moveSpeed,
            skillLevels = skillLevels.toList(),
            wave = wave,
            isRunning = running && !paused
        )
        return lastSnapshot
    }

    fun applyUpgrade(skill: PlayerSkill) {
        val player = player ?: return
        val index = skill.ordinal
        if (skillLevels[index] >= skill.maxLevel) {
            awaitingUpgrade = false
            resumeLoop()
            return
        }
        skillLevels[index] += 1
        when (skill) {
            PlayerSkill.DAMAGE -> {
                player.damage *= 1.25f
            }
            PlayerSkill.ATTACK_SPEED -> {
                player.attackCooldown *= 0.85f
                player.attackTimer = player.attackTimer.coerceAtMost(player.attackCooldown)
            }
            PlayerSkill.MOVE_SPEED -> {
                player.moveSpeed *= 1.2f
            }
            PlayerSkill.DOUBLE_SHOT -> {
                // handled during auto-attack
            }
            PlayerSkill.SHOCKWAVE -> {
                shockwaveTimer = 0f
            }
            PlayerSkill.REGENERATION -> {
                // handled in update loop
            }
        }
        awaitingUpgrade = false
        resumeLoop()
    }

    fun skillSummaries(): List<String> {
        return PlayerSkill.values().mapIndexedNotNull { index, skill ->
            val level = skillLevels[index]
            if (level <= 0) {
                null
            } else {
                val name = resources.getString(skill.titleRes)
                if (skill.maxLevel > 1) {
                    "$name Lv $level/${skill.maxLevel}"
                } else {
                    name
                }
            }
        }
    }

    private fun formatUpgradeChoice(skill: PlayerSkill): String {
        val name = resources.getString(skill.titleRes)
        val current = skillLevels[skill.ordinal]
        return if (skill.maxLevel > 1) {
            val nextLevel = (current + 1).coerceAtMost(skill.maxLevel)
            "$name (Lv $nextLevel/${skill.maxLevel})"
        } else {
            name
        }
    }

    override fun run() {
        var previous = System.nanoTime()
        while (running) {
            if (paused || !holder.surface.isValid) {
                try {
                    Thread.sleep(16)
                } catch (ignored: InterruptedException) {
                }
                previous = System.nanoTime()
                continue
            }
            val now = System.nanoTime()
            val delta = ((now - previous) / 1_000_000_000f).coerceAtMost(0.1f)
            previous = now
            update(delta)
            draw()
        }
    }

    private fun update(delta: Float) {
        val player = player ?: return
        val joystickX = joystick.normalizedX
        val joystickY = joystick.normalizedY
        val magnitude = hypot(joystickX.toDouble(), joystickY.toDouble()).toFloat()
        if (magnitude > 0f) {
            val speed = player.moveSpeed * delta
            val nextX = (player.x + joystickX * speed).coerceIn(player.radius, worldWidth - player.radius)
            val nextY = (player.y + joystickY * speed).coerceIn(player.radius, worldHeight - player.radius)
            val resolved = resolveObstacleCollision(nextX, nextY, player.radius)
            player.x = resolved.first.coerceIn(player.radius, worldWidth - player.radius)
            player.y = resolved.second.coerceIn(player.radius, worldHeight - player.radius)
        } else {
            val resolved = resolveObstacleCollision(player.x, player.y, player.radius)
            player.x = resolved.first.coerceIn(player.radius, worldWidth - player.radius)
            player.y = resolved.second.coerceIn(player.radius, worldHeight - player.radius)
        }

        updateCamera()

        val regenLevel = skillLevels[PlayerSkill.REGENERATION.ordinal]
        if (regenLevel > 0 && player.hp < player.maxHp) {
            val regenRate = player.maxHp * 0.0025f * regenLevel
            player.hp = (player.hp + regenRate * delta).coerceAtMost(player.maxHp)
        }

        val shockwaveLevel = skillLevels[PlayerSkill.SHOCKWAVE.ordinal]
        if (shockwaveLevel > 0) {
            shockwaveTimer -= delta
            if (shockwaveTimer <= 0f) {
                triggerShockwave(player, shockwaveLevel)
                shockwaveTimer = shockwaveCooldown(shockwaveLevel)
            }
        }

        spawnTimer -= delta
        waveTimer += delta
        if (spawnTimer <= 0f) {
            spawnEnemies()
            spawnTimer = (1.8f - wave * 0.08f).coerceAtLeast(0.45f)
        }

        if (waveTimer > 60f) {
            wave += 1
            waveTimer = 0f
            bossSpawned = false
        }

        if (!bossSpawned && wave % 5 == 0) {
            spawnBoss()
            bossSpawned = true
        }

        // Update enemies
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            val angle = atan2(player.y - enemy.y, player.x - enemy.x)
            val nextX = (enemy.x + cos(angle) * enemy.moveSpeed * delta)
                .coerceIn(enemy.radius, worldWidth - enemy.radius)
            val nextY = (enemy.y + sin(angle) * enemy.moveSpeed * delta)
                .coerceIn(enemy.radius, worldHeight - enemy.radius)
            val resolved = resolveObstacleCollision(nextX, nextY, enemy.radius)
            enemy.x = resolved.first.coerceIn(enemy.radius, worldWidth - enemy.radius)
            enemy.y = resolved.second.coerceIn(enemy.radius, worldHeight - enemy.radius)
            if (hypot((player.x - enemy.x).toDouble(), (player.y - enemy.y).toDouble()) <= player.radius + enemy.radius) {
                player.damage(enemy.damage * delta)
                callbacks?.onVibrate(30)
                if (player.hp <= 0f) {
                    callbacks?.onGameOver()
                    pauseLoop()
                }
            }
        }

        // Update projectiles
        val projectileIterator = projectiles.iterator()
        while (projectileIterator.hasNext()) {
            val projectile = projectileIterator.next()
            projectile.x += projectile.vx * delta
            projectile.y += projectile.vy * delta
            projectile.lifetime -= delta
            if (projectile.lifetime <= 0f) {
                projectileIterator.remove()
                continue
            }
            val enemyIterator = enemies.iterator()
            while (enemyIterator.hasNext()) {
                val enemy = enemyIterator.next()
                if (hypot((projectile.x - enemy.x).toDouble(), (projectile.y - enemy.y).toDouble()) <= projectile.radius + enemy.radius) {
                    enemy.hp -= projectile.damage
                    projectile.lifetime = 0f
                    callbacks?.onPlayHit()
                    if (enemy.hp <= 0f) {
                        dropPickup(enemy)
                        enemyIterator.remove()
                        player.gainXp(5 + wave) { level ->
                            onLevelUp(level)
                        }
                    }
                    projectileIterator.remove()
                    break
                }
            }
        }

        // Update pickups
        val pickupIterator = pickups.iterator()
        while (pickupIterator.hasNext()) {
            val pickup = pickupIterator.next()
            applyPickupMagnetism(pickup, player, delta)
            if (hypot((player.x - pickup.x).toDouble(), (player.y - pickup.y).toDouble()) <= player.radius + pickup.radius) {
                when (pickup.type) {
                    PickupItem.Type.XP_GEM -> player.gainXp(pickup.xp) { level -> onLevelUp(level) }
                    PickupItem.Type.HEALING -> player.heal(0.15f)
                }
                pickupIterator.remove()
            }
        }

        // Auto attack
        player.attackTimer -= delta
        if (player.attackTimer <= 0f) {
            autoAttack(player)
            player.attackTimer = player.attackCooldown
        }

        callbacks?.onHudChanged("HP ${player.hp.toInt()} / ${player.maxHp.toInt()} | LVL ${player.level} | Wave $wave")
        saveTimer += delta
        if (saveTimer >= 2f) {
            saveTimer = 0f
            callbacks?.onSaveRequested(snapshot())
        }
    }

    private fun dropPickup(enemy: Enemy) {
        val roll = Random.nextFloat()
        val type = if (roll < 0.1f) PickupItem.Type.HEALING else PickupItem.Type.XP_GEM
        val xp = if (enemy.isBoss) 50 else 10
        pickups.add(
            PickupItem(
                x = enemy.x,
                y = enemy.y,
                radius = if (type == PickupItem.Type.HEALING) 24f else 16f,
                xp = xp,
                type = type
            )
        )
    }

    private fun autoAttack(player: Player) {
        val target = enemies.minByOrNull { enemy ->
            hypot((enemy.x - player.x).toDouble(), (enemy.y - player.y).toDouble())
        }
        if (target != null) {
            val angle = atan2(target.y - player.y, target.x - player.x)
            val speed = 500f
            val projectileCount = if (skillLevels[PlayerSkill.DOUBLE_SHOT.ordinal] > 0) 2 else 1
            val spread = if (projectileCount > 1) 0.12f else 0f
            val center = (projectileCount - 1) / 2f
            repeat(projectileCount) { index ->
                val offset = index - center
                val finalAngle = angle + offset * spread
                projectiles.add(
                    Projectile(
                        x = player.x,
                        y = player.y,
                        vx = cos(finalAngle) * speed,
                        vy = sin(finalAngle) * speed,
                        radius = 16f,
                        damage = player.damage,
                        lifetime = 2f
                    )
                )
            }
        }
    }

    private fun applyPickupMagnetism(pickup: PickupItem, player: Player, delta: Float) {
        if (pickup.type != PickupItem.Type.XP_GEM) {
            pickup.vx *= 0.75f
            pickup.vy *= 0.75f
        } else {
            val dx = player.x - pickup.x
            val dy = player.y - pickup.y
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val magnetRadius = 280f
            if (distance <= magnetRadius && distance > 0f) {
                val pull = 240f + 320f * (1f - (distance / magnetRadius).coerceIn(0f, 1f))
                val norm = 1f / distance
                pickup.vx = dx * norm * pull
                pickup.vy = dy * norm * pull
            } else if (distance == 0f) {
                pickup.vx = 0f
                pickup.vy = 0f
            } else {
                pickup.vx *= 0.85f
                pickup.vy *= 0.85f
            }
        }
        pickup.x = (pickup.x + pickup.vx * delta).coerceIn(0f, worldWidth)
        pickup.y = (pickup.y + pickup.vy * delta).coerceIn(0f, worldHeight)
    }

    private fun triggerShockwave(player: Player, level: Int) {
        val radius = 220f + 30f * (level - 1)
        val damage = player.damage * (0.25f + 0.1f * (level - 1))
        var hit = false
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            val distance = hypot((enemy.x - player.x).toDouble(), (enemy.y - player.y).toDouble()).toFloat()
            if (distance <= radius + enemy.radius) {
                enemy.hp -= damage
                hit = true
                if (enemy.hp <= 0f) {
                    dropPickup(enemy)
                    iterator.remove()
                    player.gainXp(5 + wave) { levelUp -> onLevelUp(levelUp) }
                }
            }
        }
        if (hit) {
            callbacks?.onPlayHit()
        }
    }

    private fun shockwaveCooldown(level: Int): Float {
        return (6f - (level - 1) * 1.2f).coerceAtLeast(2.5f)
    }

    private fun onLevelUp(level: Int) {
        if (awaitingUpgrade) return
        awaitingUpgrade = true
        pauseLoop()
        val available = PlayerSkill.values().filter { skill ->
            skillLevels[skill.ordinal] < skill.maxLevel
        }
        if (available.isEmpty()) {
            awaitingUpgrade = false
            resumeLoop()
            return
        }
        val choices = available
            .shuffled()
            .take(3)
            .map { skill ->
                UpgradeChoice(skill, formatUpgradeChoice(skill))
            }
        callbacks?.onUpgradeChoices(choices) { selected ->
            applyUpgrade(selected)
        }
    }

    private fun spawnEnemies() {
        val player = player ?: return
        val baseCount = 1 + (wave - 1) / 3
        val ramp = (waveTimer / 20f).toInt().coerceAtMost(3)
        val count = (baseCount + ramp).coerceAtLeast(1).coerceAtMost(6 + wave / 2)
        repeat(count) {
            var attempts = 0
            var spawnX: Float
            var spawnY: Float
            val radius = 32f
            do {
                val angle = Random.nextDouble(0.0, PI * 2)
                val distance = Random.nextDouble(550.0, 1000.0)
                val offsetX = (cos(angle) * distance).toFloat()
                val offsetY = (sin(angle) * distance).toFloat()
                spawnX = (player.x + offsetX).coerceIn(radius, worldWidth - radius)
                spawnY = (player.y + offsetY).coerceIn(radius, worldHeight - radius)
                attempts++
            } while ((hypot((player.x - spawnX).toDouble(), (player.y - spawnY).toDouble()) < 280 ||
                    collidesWithObstacle(spawnX, spawnY, radius)) && attempts < 8)

            enemies.add(
                Enemy(
                    x = spawnX,
                    y = spawnY,
                    radius = radius,
                    hp = 30f + wave * 6f,
                    moveSpeed = 70f + wave * 3.5f,
                    damage = 6f + wave
                )
            )
        }
    }

    private fun spawnBoss() {
        val player = player ?: return
        var attempts = 0
        val radius = 64f
        var spawnX: Float
        var spawnY: Float
        do {
            val angle = Random.nextDouble(0.0, PI * 2)
            val distance = Random.nextDouble(900.0, 1600.0)
            val offsetX = (cos(angle) * distance).toFloat()
            val offsetY = (sin(angle) * distance).toFloat()
            spawnX = (player.x + offsetX).coerceIn(radius, worldWidth - radius)
            spawnY = (player.y + offsetY).coerceIn(radius, worldHeight - radius)
            attempts++
        } while ((hypot((player.x - spawnX).toDouble(), (player.y - spawnY).toDouble()) < 600 ||
                collidesWithObstacle(spawnX, spawnY, radius)) && attempts < 12)

        enemies.add(
            Enemy(
                x = spawnX,
                y = spawnY,
                radius = radius,
                hp = 400f + wave * 40,
                moveSpeed = 60f,
                damage = 20f,
                isBoss = true
            )
        )
    }

    private fun draw() {
        val canvas = holder.lockCanvas() ?: return
        try {
            val widthF = width.toFloat()
            val heightF = height.toFloat()
            canvas.drawRect(0f, 0f, widthF, heightF, bgPaint)
            drawGrid(canvas, widthF, heightF)
            obstacles.forEach { obstacle ->
                if (obstacle.x + obstacle.radius < cameraX ||
                    obstacle.x - obstacle.radius > cameraX + widthF ||
                    obstacle.y + obstacle.radius < cameraY ||
                    obstacle.y - obstacle.radius > cameraY + heightF
                ) {
                    return@forEach
                }
                canvas.drawCircle(
                    obstacle.x - cameraX,
                    obstacle.y - cameraY,
                    obstacle.radius,
                    obstaclePaint
                )
            }
            player?.let { player ->
                canvas.drawCircle(player.x - cameraX, player.y - cameraY, player.radius, playerPaint)
            }
            enemies.forEach { enemy ->
                val paint = if (enemy.isBoss) bossPaint else enemyPaint
                canvas.drawCircle(enemy.x - cameraX, enemy.y - cameraY, enemy.radius, paint)
            }
            projectiles.forEach { projectile ->
                canvas.drawCircle(projectile.x - cameraX, projectile.y - cameraY, projectile.radius, projectilePaint)
            }
            pickups.forEach { pickup ->
                canvas.drawCircle(pickup.x - cameraX, pickup.y - cameraY, pickup.radius, xpPaint)
            }
            joystick.draw(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = joystick.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        arenaWidth = width
        arenaHeight = height
        updateCamera()
        if (startRequested && thread?.isAlive != true) {
            startLoop()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        arenaWidth = width
        arenaHeight = height
        if (player == null) {
            initialize(lastSnapshot, callbacks ?: return, controller ?: return)
        }
        updateCamera()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    private fun resetState(snapshot: PlayerSnapshot) {
        wave = snapshot.wave
        lastSnapshot = snapshot
        val snapshotSkills = snapshot.skillLevels
        PlayerSkill.values().forEachIndexed { index, _ ->
            skillLevels[index] = snapshotSkills.getOrNull(index) ?: 0
        }
        spawnTimer = 0f
        waveTimer = 0f
        bossSpawned = false
        saveTimer = 0f
        awaitingUpgrade = false
        val shockwaveLevel = skillLevels[PlayerSkill.SHOCKWAVE.ordinal]
        shockwaveTimer = if (shockwaveLevel > 0) shockwaveCooldown(shockwaveLevel) else 0f
        enemies.clear()
        projectiles.clear()
        pickups.clear()
        obstacles.clear()
        generateObstacles()
        joystick.reset()
        updateCamera()
    }

    private fun drawGrid(canvas: Canvas, widthF: Float, heightF: Float) {
        val spacing = 256f
        val offsetX = ((-cameraX) % spacing + spacing) % spacing
        var x = offsetX - spacing
        while (x < widthF + spacing) {
            canvas.drawLine(x, 0f, x, heightF, gridPaint)
            x += spacing
        }
        val offsetY = ((-cameraY) % spacing + spacing) % spacing
        var y = offsetY - spacing
        while (y < heightF + spacing) {
            canvas.drawLine(0f, y, widthF, y, gridPaint)
            y += spacing
        }
    }

    private fun generateObstacles() {
        val target = 45
        val margin = 200f
        val safeRadius = 420f
        val centerX = worldWidth / 2f
        val centerY = worldHeight / 2f
        var attempts = 0
        while (obstacles.size < target && attempts < target * 25) {
            attempts++
            val radius = Random.nextFloat() * 90f + 60f
            val x = Random.nextFloat() * (worldWidth - margin * 2f) + margin
            val y = Random.nextFloat() * (worldHeight - margin * 2f) + margin
            if (hypot((x - centerX).toDouble(), (y - centerY).toDouble()) < safeRadius + radius) continue
            if (x - radius < 0f || x + radius > worldWidth || y - radius < 0f || y + radius > worldHeight) continue
            if (obstacles.any { existing ->
                    hypot((existing.x - x).toDouble(), (existing.y - y).toDouble()) < existing.radius + radius + 80f
                }
            ) continue
            obstacles.add(Obstacle(x, y, radius))
        }
    }

    private fun resolveObstacleCollision(targetX: Float, targetY: Float, radius: Float): Pair<Float, Float> {
        var resolvedX = targetX
        var resolvedY = targetY
        obstacles.forEach { obstacle ->
            val dx = resolvedX - obstacle.x
            val dy = resolvedY - obstacle.y
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val minDistance = radius + obstacle.radius
            if (distance < minDistance && distance > 0.0001f) {
                val overlap = minDistance - distance
                val norm = 1f / distance
                resolvedX += dx * norm * overlap
                resolvedY += dy * norm * overlap
            } else if (distance == 0f) {
                resolvedX += minDistance
            }
        }
        return resolvedX to resolvedY
    }

    private fun collidesWithObstacle(x: Float, y: Float, radius: Float): Boolean {
        return obstacles.any { obstacle ->
            hypot((obstacle.x - x).toDouble(), (obstacle.y - y).toDouble()) < obstacle.radius + radius
        }
    }

    private fun updateCamera() {
        val widthF = arenaWidth.takeIf { it > 0 }?.toFloat() ?: width.toFloat()
        val heightF = arenaHeight.takeIf { it > 0 }?.toFloat() ?: height.toFloat()
        val player = player
        val maxX = (worldWidth - widthF).coerceAtLeast(0f)
        val maxY = (worldHeight - heightF).coerceAtLeast(0f)
        if (player != null) {
            cameraX = (player.x - widthF / 2f).coerceIn(0f, maxX)
            cameraY = (player.y - heightF / 2f).coerceIn(0f, maxY)
        } else {
            cameraX = maxX / 2f
            cameraY = maxY / 2f
        }
    }
}
