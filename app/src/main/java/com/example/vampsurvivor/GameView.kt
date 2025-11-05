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
import com.example.vampsurvivor.entities.PickupItem
import com.example.vampsurvivor.entities.Player
import com.example.vampsurvivor.entities.PlayerSnapshot
import com.example.vampsurvivor.entities.Projectile
import com.example.vampsurvivor.systems.GameLoopController
import com.example.vampsurvivor.systems.VirtualJoystick
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
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

    private var arenaWidth = 0
    private var arenaHeight = 0

    private var spawnTimer = 0f
    private var waveTimer = 0f
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
        val centerX = if (arenaWidth > 0) arenaWidth / 2f else width / 2f
        val centerY = if (arenaHeight > 0) arenaHeight / 2f else height / 2f
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
            wave = wave,
            isRunning = running && !paused
        )
        return lastSnapshot
    }

    fun applyUpgrade(choice: String) {
        val player = player ?: return
        when {
            choice.contains("damage", ignoreCase = true) -> {
                player.damage *= 1.25f
            }
            choice.contains("attack", ignoreCase = true) -> {
                player.attackCooldown *= 0.85f
            }
            choice.contains("move", ignoreCase = true) -> {
                player.moveSpeed *= 1.2f
            }
            choice.contains("cooldown", ignoreCase = true) -> {
                player.attackCooldown *= 0.8f
            }
            choice.contains("heal", ignoreCase = true) -> {
                player.heal(0.2f)
            }
        }
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
            val maxX = (arenaWidth.takeIf { it > 0 } ?: width) - player.radius
            val maxY = (arenaHeight.takeIf { it > 0 } ?: height) - player.radius
            val minX = player.radius
            val minY = player.radius
            player.x = (player.x + joystickX * speed).coerceIn(minX, maxX)
            player.y = (player.y + joystickY * speed).coerceIn(minY, maxY)
        }

        spawnTimer -= delta
        waveTimer += delta
        if (spawnTimer <= 0f) {
            spawnEnemies()
            spawnTimer = max(0.5f - wave * 0.02f, 0.15f)
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
            enemy.x += cos(angle) * enemy.moveSpeed * delta
            enemy.y += sin(angle) * enemy.moveSpeed * delta
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
            projectiles.add(
                Projectile(
                    x = player.x,
                    y = player.y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    radius = 16f,
                    damage = player.damage,
                    lifetime = 2f
                )
            )
        }
    }

    private fun onLevelUp(level: Int) {
        if (awaitingUpgrade) return
        awaitingUpgrade = true
        pauseLoop()
        val options = listOf(
            resources.getString(R.string.upgrade_damage),
            resources.getString(R.string.upgrade_attack_speed),
            resources.getString(R.string.upgrade_move_speed),
            resources.getString(R.string.upgrade_cooldown)
        ).shuffled().take(3)
        callbacks?.onUpgradeChoices(options) { choice ->
            applyUpgrade(choice)
        }
    }

    private fun spawnEnemies() {
        val count = 1 + wave / 2
        repeat(count) {
            val angle = Random.nextFloat() * 360f
            val distance = max(width, height) * 0.6f
            val spawnX = width / 2f + cos(Math.toRadians(angle.toDouble())).toFloat() * distance
            val spawnY = height / 2f + sin(Math.toRadians(angle.toDouble())).toFloat() * distance
            enemies.add(
                Enemy(
                    x = spawnX,
                    y = spawnY,
                    radius = 32f,
                    hp = 30f + wave * 6f,
                    moveSpeed = 100f + wave * 5f,
                    damage = 6f + wave
                )
            )
        }
    }

    private fun spawnBoss() {
        enemies.add(
            Enemy(
                x = Random.nextInt(width).toFloat(),
                y = 0f,
                radius = 64f,
                hp = 400f + wave * 40,
                moveSpeed = 80f,
                damage = 20f,
                isBoss = true
            )
        )
    }

    private fun draw() {
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            player?.let { player ->
                canvas.drawCircle(player.x, player.y, player.radius, playerPaint)
            }
            enemies.forEach { enemy ->
                val paint = if (enemy.isBoss) bossPaint else enemyPaint
                canvas.drawCircle(enemy.x, enemy.y, enemy.radius, paint)
            }
            projectiles.forEach { projectile ->
                canvas.drawCircle(projectile.x, projectile.y, projectile.radius, projectilePaint)
            }
            pickups.forEach { pickup ->
                canvas.drawCircle(pickup.x, pickup.y, pickup.radius, xpPaint)
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
        if (startRequested && thread?.isAlive != true) {
            startLoop()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        arenaWidth = width
        arenaHeight = height
        if (player == null) {
            initialize(lastSnapshot, callbacks ?: return, controller ?: return)
        } else {
            player?.let {
                it.x = width / 2f
                it.y = height / 2f
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    private fun resetState(snapshot: PlayerSnapshot) {
        wave = snapshot.wave
        lastSnapshot = snapshot
        spawnTimer = 0f
        waveTimer = 0f
        bossSpawned = false
        saveTimer = 0f
        awaitingUpgrade = false
        enemies.clear()
        projectiles.clear()
        pickups.clear()
        joystick.reset()
    }
}
