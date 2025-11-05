package com.example.vampsurvivor.systems

import com.example.vampsurvivor.GameView
import com.example.vampsurvivor.entities.PlayerSnapshot

class GameLoopController(
    private val gameView: GameView,
    snapshot: PlayerSnapshot,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onHudChanged(text: String)
        fun onGamePaused(paused: Boolean)
        fun onGameOver()
        fun onVibrate(duration: Long)
        fun onUpgradeChoices(options: List<String>, onSelected: (String) -> Unit)
        fun onSaveRequested(snapshot: PlayerSnapshot)
        fun onPlayHit()
    }

    init {
        gameView.initialize(snapshot, callbacks, this)
    }

    fun start() {
        gameView.startLoop()
        callbacks.onGamePaused(false)
    }

    fun pause() {
        gameView.pauseLoop()
        callbacks.onGamePaused(true)
    }

    fun resume() {
        gameView.resumeLoop()
        callbacks.onGamePaused(false)
    }

    fun isPaused(): Boolean = gameView.isPaused()

    fun snapshot(): PlayerSnapshot = gameView.snapshot()

    fun applyUpgrade(choice: String) {
        gameView.applyUpgrade(choice)
    }

    fun stop() {
        gameView.stopLoop()
    }
}
