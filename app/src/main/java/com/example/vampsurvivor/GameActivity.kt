package com.example.vampsurvivor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.vampsurvivor.databinding.ActivityGameBinding
import com.example.vampsurvivor.entities.PlayerSnapshot
import com.example.vampsurvivor.systems.AudioController
import com.example.vampsurvivor.systems.GameLoopController
import com.example.vampsurvivor.systems.GameStateStore
import kotlinx.coroutines.launch

class GameActivity : AppCompatActivity(), GameLoopController.Callbacks {

    private lateinit var binding: ActivityGameBinding
    private lateinit var gameLoop: GameLoopController
    private val store by lazy { GameStateStore(this) }
    private val audio by lazy { AudioController(this) }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val snapshot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SNAPSHOT, PlayerSnapshot::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_SNAPSHOT) as? PlayerSnapshot
        } ?: PlayerSnapshot.default()

        val gameView = binding.gameView
        gameView.setGameCallbacks(this)
        gameLoop = GameLoopController(gameView, snapshot, this)

        binding.pauseButton.setOnClickListener { togglePause() }
        binding.pauseResumeButton.setOnClickListener { togglePause(false) }
        binding.pauseQuitButton.setOnClickListener { finish() }
        binding.returnMenuButton.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!gameLoop.isPaused()) togglePause(true) else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        audio.playBgm()
        gameLoop.start()
    }

    override fun onPause() {
        super.onPause()
        audio.pauseBgm()
        gameLoop.pause()
        lifecycleScope.launch {
            store.persist(gameLoop.snapshot())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gameLoop.stop()
        audio.release()
    }

    override fun onHudChanged(text: String) {
        runOnUiThread { binding.hudText.text = text }
    }

    override fun onGamePaused(paused: Boolean) {
        runOnUiThread {
            binding.pauseMenu.visibility = if (paused) android.view.View.VISIBLE else android.view.View.GONE
            binding.pauseButton.text = if (paused) getString(R.string.resume) else getString(R.string.pause)
        }
    }

    override fun onGameOver() {
        runOnUiThread {
            binding.gameOverOverlay.visibility = android.view.View.VISIBLE
            gameLoop.pause()
            binding.pauseMenu.visibility = View.GONE
        }
    }

    override fun onVibrate(duration: Long) {
        vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onUpgradeChoices(options: List<String>, onSelected: (String) -> Unit) {
        runOnUiThread {
            binding.upgradePanel.visibility = android.view.View.VISIBLE
            val buttons = listOf(binding.upgradeOption1, binding.upgradeOption2, binding.upgradeOption3)
            buttons.forEach { it.visibility = View.GONE }
            buttons.zip(options).forEach { (button, text) ->
                button.visibility = View.VISIBLE
                button.text = text
                button.setOnClickListener {
                    binding.upgradePanel.visibility = android.view.View.GONE
                    onSelected(text)
                }
            }
        }
    }

    override fun onPlayHit() {
        audio.playHit()
    }

    override fun onSaveRequested(snapshot: PlayerSnapshot) {
        lifecycleScope.launch {
            store.persist(snapshot)
        }
    }

    private fun togglePause(force: Boolean? = null) {
        val shouldPause = force ?: !gameLoop.isPaused()
        if (shouldPause) {
            gameLoop.pause()
        } else {
            gameLoop.resume()
        }
    }

    companion object {
        private const val EXTRA_SNAPSHOT = "snapshot"

        fun launch(context: Context, snapshot: PlayerSnapshot) {
            val intent = Intent(context, GameActivity::class.java)
            intent.putExtra(EXTRA_SNAPSHOT, snapshot)
            context.startActivity(intent)
        }
    }
}
