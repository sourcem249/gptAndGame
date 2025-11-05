package com.example.vampsurvivor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.vampsurvivor.databinding.ActivityMainBinding
import com.example.vampsurvivor.systems.GameStateStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val store by lazy { GameStateStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val snapshot = store.loadSnapshot()
            binding.resumeButton.visibility = if (snapshot != null && snapshot.isRunning) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        binding.startButton.setOnClickListener {
            val intent = Intent(this, CharacterSelectActivity::class.java)
            startActivity(intent)
        }

        binding.resumeButton.setOnClickListener {
            lifecycleScope.launch {
                val snapshot = store.loadSnapshot()
                if (snapshot != null) {
                    GameActivity.launch(this@MainActivity, snapshot)
                }
            }
        }

        binding.loadButton.setOnClickListener {
            lifecycleScope.launch {
                val snapshot = store.loadSnapshot()
                if (snapshot == null) {
                    Toast.makeText(this@MainActivity, R.string.no_save_found, Toast.LENGTH_SHORT).show()
                } else {
                    GameActivity.launch(this@MainActivity, snapshot.copy(isRunning = false))
                }
            }
        }
    }
}
