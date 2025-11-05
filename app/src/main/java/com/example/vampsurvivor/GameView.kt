package com.example.vampsurvivor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import com.example.vampsurvivor.entities.Enemy
import com.example.vampsurvivor.entities.Obstacle
import com.example.vampsurvivor.entities.PickupItem
import com.example.vampsurvivor.entities.Player
import com.example.vampsurvivor.entities.PlayerSnapshot
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
    private val shockwavePaint = Paint().apply {
        color = Color.argb(180, 80, 180, 255)
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
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
    private val upgradeCounts = linkedMapOf<UpgradeType, Int>()
    private var savedUpgradeLabels: List<String> = emptyList()
    private var multiShotLevel = 0
    private var shockwaveLevel = 0
    private var regenLevel = 0
    private var shockwaveTimer = 0f
    private var regenTimer = 0f
    private val shockwaveEffects = mutableListOf<ShockwaveEffect>()

    private var arenaWidth = 0
    private var arenaHeight = 0
    private val worldWidth = 6000f
    private val worldHeight = 6000f
    private var cameraX = 0f
    private var cameraY = 0f

    private var spawnTimer = 0f
    private var waveTimer = 0f
    private var enemiesToSpawnInWave = 0
    private var enemiesSpawnedInWave = 0
    private var currentSpawnInterval = 1.2f
    private var bossSpawned = false
    private var wave = 1
    private var saveTimer = 0f

    private var awaitingUpgrade = false
    private var lastSnapshot = PlayerSnapshot.default()

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
        applySavedUpgrades(player!!)
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
        callbacks?.onUpgradeSummaryChanged(buildUpgradeSummary())
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
            skillChoices = buildSkillChoiceList(),
            wave = wave,
            isRunning = running && !paused
        )
        return lastSnapshot
    }

    fun applyUpgrade(choice: String) {
        val player = player ?: return
        val type = choiceToUpgradeType(choice) ?: run {
            awaitingUpgrade = false
            resumeLoop()
            return
        }
        applyUpgradeToPlayer(type, player, fromSnapshot = false)
        awaitingUpgrade = false
        resumeLoop()
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

        spawnTimer -= delta
        waveTimer += delta

        if (enemiesSpawnedInWave < enemiesToSpawnInWave && enemies.size < maxActiveEnemies()) {
            if (spawnTimer <= 0f) {
                if (spawnEnemy()) {
                    enemiesSpawnedInWave += 1
                    spawnTimer = currentSpawnInterval
                } else {
                    spawnTimer = 0.25f
                }
            }
        }

        if (!bossSpawned && wave % 5 == 0 && enemiesSpawnedInWave >= enemiesToSpawnInWave / 2) {
            if (spawnBoss()) {
                bossSpawned = true
            } else {
                spawnTimer = spawnTimer.coerceAtLeast(0.5f)
            }
        }

        if (enemiesSpawnedInWave >= enemiesToSpawnInWave && enemies.isEmpty()) {
            setupWave(wave + 1)
        }

        val enemyIterator = enemies.iterator()
        while (enemyIterator.hasNext()) {
            val enemy = enemyIterator.next()
            val angle = atan2(player.y - enemy.y, player.x - enemy.x)
            val nextX = (enemy.x + cos(angle) * enemy.moveSpeed * delta)
                .coerceIn(enemy.radius, worldWidth - enemy.radius)
            val nextY = (enemy.y + sin(angle) * enemy.moveSpeed * delta)
                .coerceIn(enemy.radius, worldWidth - enemy.radius)
            val resolved = resolveObstacleCollision(nextX, nextY, enemy.radius)
            enemy.x = resolved.first.coerceIn(enemy.radius, worldWidth - enemy.radius)
            enemy.y = resolved.second.coerceIn(enemy.radius, worldHeight - enemy.radius)
            if (hypot((player.x - enemy.x).toDouble(), (player.y - enemy.y).toDouble()) <= player.radius + enemy.radius) {
                player.damage(enemy.damage * delta)
                callbacks?.onVibrate(30)
                if (player.hp <= 0f) {
                    callbacks?.onGameOver()
                    pauseLoop()
                    return
                }
            }
        }

        val shockwaveIterator = shockwaveEffects.iterator()
        while (shockwaveIterator.hasNext()) {
            val effect = shockwaveIterator.next()
            effect.elapsed += delta
            if (effect.elapsed >= effect.duration) {
                shockwaveIterator.remove()
            }
        }

        if (shockwaveLevel > 0) {
            shockwaveTimer += delta
            val interval = shockwaveInterval()
            while (shockwaveTimer >= interval) {
                shockwaveTimer -= interval
                emitShockwave(player)
            }
        }

        if (regenLevel > 0) {
            if (player.hp < player.maxHp) {
                regenTimer += delta
                val interval = regenInterval()
                if (regenTimer >= interval) {
                    val ticks = (regenTimer / interval).toInt()
                    regenTimer -= interval * ticks
                    repeat(ticks) {
                        player.heal(regenPercent())
                    }
                }
            } else {
                regenTimer = 0f
            }
        }

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
            val enemyIteratorForProjectile = enemies.iterator()
            while (enemyIteratorForProjectile.hasNext()) {
                val enemy = enemyIteratorForProjectile.next()
                if (hypot((projectile.x - enemy.x).toDouble(), (projectile.y - enemy.y).toDouble()) <= projectile.radius + enemy.radius) {
                    projectileIterator.remove()
                    damageEnemy(enemyIteratorForProjectile, enemy, projectile.damage, player)
                    break
                }
            }
        }

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

        player.attackTimer -= delta
        if (player.attackTimer <= 0f) {
            autoAttack(player)
            player.attackTimer = player.attackCooldown
        }

        val remainingToSpawn = (enemiesToSpawnInWave - enemiesSpawnedInWave).coerceAtLeast(0)
        val totalRemaining = enemies.size + remainingToSpawn + if (!bossSpawned && wave % 5 == 0) 1 else 0
        callbacks?.onHudChanged("HP ${player.hp.toInt()} / ${player.maxHp.toInt()} | LVL ${player.level} | Wave $wave (${totalRemaining} left)")
        saveTimer += delta
        if (saveTimer >= 2f) {
            saveTimer = 0f
            callbacks?.onSaveRequested(snapshot())
        }
    }


    private fun damageEnemy(iterator: MutableIterator<Enemy>, enemy: Enemy, amount: Float, player: Player) {
        enemy.hp -= amount
        callbacks?.onPlayHit()
        if (enemy.hp <= 0f) {
            dropPickup(enemy)
            iterator.remove()
            player.gainXp(6 + wave) { level -> onLevelUp(level) }
        }
    }

    private fun dropPickup(enemy: Enemy) {
        val roll = Random.nextFloat()
        val type = if (roll < 0.1f) PickupItem.Type.HEALING else PickupItem.Type.XP_GEM
        val xp = if (enemy.isBoss) 60 + wave * 5 else 8 + wave
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

    private fun emitShockwave(player: Player) {
        val radius = shockwaveRadius()
        val damage = shockwaveDamage(player)
        shockwaveEffects.add(ShockwaveEffect(player.x, player.y, 0f, 0.45f, radius))
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            val distance = hypot((enemy.x - player.x).toDouble(), (enemy.y - player.y).toDouble()).toFloat()
            if (distance <= radius + enemy.radius) {
                damageEnemy(iterator, enemy, damage, player)
            }
        }
    }

    private fun autoAttack(player: Player) {
        val target = enemies.minByOrNull { enemy ->
            hypot((enemy.x - player.x).toDouble(), (enemy.y - player.y).toDouble())
        } ?: return
        val angle = atan2(target.y - player.y, target.x - player.x)
        val speed = 500f
        val shots = (1 + multiShotLevel).coerceAtMost(5)
        val spread = if (shots > 1) 0.18f else 0f
        val half = (shots - 1) / 2f
        for (i in 0 until shots) {
            val offset = (i - half) * spread
            val shotAngle = angle + offset
            projectiles.add(
                Projectile(
                    x = player.x,
                    y = player.y,
                    vx = cos(shotAngle) * speed,
                    vy = sin(shotAngle) * speed,
                    radius = 16f,
                    damage = player.damage,
                    lifetime = 2f
                )
            )
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

    private fun shockwaveInterval(): Float {
        return (5.2f - (shockwaveLevel - 1) * 0.6f).coerceAtLeast(2.4f)
    }

    private fun shockwaveRadius(): Float {
        return 240f + (shockwaveLevel - 1) * 45f
    }

    private fun shockwaveDamage(player: Player): Float {
        return player.damage * (0.35f + (shockwaveLevel - 1) * 0.1f)
    }

    private fun regenInterval(): Float {
        return (1.3f - (regenLevel - 1) * 0.1f).coerceAtLeast(0.7f)
    }

    private fun regenPercent(): Float {
        return 0.0035f * regenLevel
    }

    private fun onLevelUp(level: Int) {
        if (awaitingUpgrade) return
        awaitingUpgrade = true
        pauseLoop()
        val options = UpgradeType.values()
            .map { type -> resources.getString(type.labelRes) }
            .shuffled()
            .take(3)
        callbacks?.onUpgradeChoices(options) { choice ->
            applyUpgrade(choice)
        }
    }

    private fun spawnEnemy(): Boolean {
        val player = player ?: return false
        var attempts = 0
        val radius = 32f
        while (attempts < 12) {
            attempts++
            val angle = Random.nextDouble(0.0, PI * 2)
            val distance = Random.nextDouble(420.0, 880.0)
            val offsetX = (cos(angle) * distance).toFloat()
            val offsetY = (sin(angle) * distance).toFloat()
            val spawnX = (player.x + offsetX).coerceIn(radius, worldWidth - radius)
            val spawnY = (player.y + offsetY).coerceIn(radius, worldHeight - radius)
            val tooClose = hypot((player.x - spawnX).toDouble(), (player.y - spawnY).toDouble()) < 260
            if (tooClose || collidesWithObstacle(spawnX, spawnY, radius)) continue
            enemies.add(
                Enemy(
                    x = spawnX,
                    y = spawnY,
                    radius = radius,
                    hp = 28f + wave * 5.5f,
                    moveSpeed = 48f + wave * 2.3f,
                    damage = 4.5f + wave * 0.9f
                )
            )
            return true
        }
        return false
    }

    private fun spawnBoss(): Boolean {
        val player = player ?: return false
        var attempts = 0
        val radius = 64f
        while (attempts < 16) {
            attempts++
            val angle = Random.nextDouble(0.0, PI * 2)
            val distance = Random.nextDouble(900.0, 1500.0)
            val offsetX = (cos(angle) * distance).toFloat()
            val offsetY = (sin(angle) * distance).toFloat()
            val spawnX = (player.x + offsetX).coerceIn(radius, worldWidth - radius)
            val spawnY = (player.y + offsetY).coerceIn(radius, worldHeight - radius)
            val farEnough = hypot((player.x - spawnX).toDouble(), (player.y - spawnY).toDouble()) >= 520
            if (!farEnough || collidesWithObstacle(spawnX, spawnY, radius)) continue
            enemies.add(
                Enemy(
                    x = spawnX,
                    y = spawnY,
                    radius = radius,
                    hp = 420f + wave * 45f,
                    moveSpeed = 42f,
                    damage = 18f,
                    isBoss = true
                )
            )
            return true
        }
        return false
    }

    private fun setupWave(newWave: Int) {
        wave = newWave.coerceAtLeast(1)
        enemiesToSpawnInWave = 6 + (wave - 1) * 4
        enemiesSpawnedInWave = 0
        currentSpawnInterval = (1.9f - (wave - 1) * 0.12f).coerceAtLeast(0.6f)
        spawnTimer = 0.4f
        waveTimer = 0f
        bossSpawned = wave % 5 != 0
    }

    private fun maxActiveEnemies(): Int {
        return (8 + (wave - 1) * 3).coerceAtMost(36)
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
            shockwaveEffects.forEach { effect ->
                val progress = (effect.elapsed / effect.duration).coerceIn(0f, 1f)
                val radius = effect.maxRadius * progress
                shockwavePaint.alpha = (180f * (1f - progress)).toInt().coerceIn(0, 180)
                canvas.drawCircle(effect.x - cameraX, effect.y - cameraY, radius, shockwavePaint)
            }
            shockwavePaint.alpha = 180
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
        lastSnapshot = snapshot
        wave = snapshot.wave.coerceAtLeast(1)
        savedUpgradeLabels = snapshot.skillChoices
        spawnTimer = 0f
        waveTimer = 0f
        saveTimer = 0f
        awaitingUpgrade = false
        enemies.clear()
        projectiles.clear()
        pickups.clear()
        obstacles.clear()
        upgradeCounts.clear()
        multiShotLevel = 0
        shockwaveLevel = 0
        regenLevel = 0
        shockwaveTimer = 0f
        regenTimer = 0f
        shockwaveEffects.clear()
        generateObstacles()
        joystick.reset()
        setupWave(wave)
        callbacks?.onUpgradeSummaryChanged(buildUpgradeSummary())
        updateCamera()
    }

    private fun applySavedUpgrades(player: Player) {
        if (savedUpgradeLabels.isEmpty()) {
            callbacks?.onUpgradeSummaryChanged(buildUpgradeSummary())
            return
        }
        savedUpgradeLabels.mapNotNull { choiceToUpgradeType(it) }
            .forEach { type -> applyUpgradeToPlayer(type, player, fromSnapshot = true) }
        savedUpgradeLabels = emptyList()
        callbacks?.onUpgradeSummaryChanged(buildUpgradeSummary())
    }

    private fun applyUpgradeToPlayer(type: UpgradeType, player: Player, fromSnapshot: Boolean) {
        val newCount = (upgradeCounts[type] ?: 0) + 1
        upgradeCounts[type] = newCount
        when (type) {
            UpgradeType.DAMAGE -> {
                player.damage *= 1.2f
            }
            UpgradeType.ATTACK_SPEED -> {
                player.attackCooldown = (player.attackCooldown * 0.85f).coerceAtLeast(0.18f)
                player.attackTimer = player.attackTimer.coerceAtMost(player.attackCooldown)
            }
            UpgradeType.MOVE_SPEED -> {
                player.moveSpeed *= 1.15f
            }
            UpgradeType.MULTI_SHOT -> {
                multiShotLevel = newCount
            }
            UpgradeType.SHOCKWAVE -> {
                shockwaveLevel = newCount
                shockwaveTimer = 0f
            }
            UpgradeType.REGENERATION -> {
                regenLevel = newCount
                regenTimer = 0f
            }
        }
        if (!fromSnapshot) {
            callbacks?.onUpgradeSummaryChanged(buildUpgradeSummary())
        }
    }

    private fun buildUpgradeSummary(): List<String> {
        return upgradeCounts.map { (type, count) ->
            val label = resources.getString(type.labelRes)
            if (count > 1) "$label x$count" else label
        }
    }

    private fun buildSkillChoiceList(): List<String> {
        return upgradeCounts.flatMap { (type, count) ->
            val label = resources.getString(type.labelRes)
            List(count) { label }
        }
    }

    private fun choiceToUpgradeType(choice: String): UpgradeType? {
        return UpgradeType.values().firstOrNull { type ->
            resources.getString(type.labelRes) == choice
        } ?: UpgradeType.values().firstOrNull { type ->
            choice.contains(resources.getString(type.labelRes).substringBefore(":"), ignoreCase = true)
        }
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

    private data class ShockwaveEffect(
        val x: Float,
        val y: Float,
        var elapsed: Float,
        val duration: Float,
        val maxRadius: Float
    )

    private enum class UpgradeType(@StringRes val labelRes: Int) {
        DAMAGE(R.string.upgrade_damage),
        ATTACK_SPEED(R.string.upgrade_attack_speed),
        MOVE_SPEED(R.string.upgrade_move_speed),
        MULTI_SHOT(R.string.upgrade_multi_shot),
        SHOCKWAVE(R.string.upgrade_shockwave),
        REGENERATION(R.string.upgrade_regeneration)
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
