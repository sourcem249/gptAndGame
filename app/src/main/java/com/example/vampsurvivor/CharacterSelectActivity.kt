package com.example.vampsurvivor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.vampsurvivor.databinding.ActivityCharacterSelectBinding
import com.example.vampsurvivor.entities.PlayerArchetype

class CharacterSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterSelectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.speedsterButton.setOnClickListener {
            GameActivity.launch(this, PlayerArchetype.SPEEDSTER.asSnapshot())
        }

        binding.tankButton.setOnClickListener {
            GameActivity.launch(this, PlayerArchetype.TANK.asSnapshot())
        }

        binding.mageButton.setOnClickListener {
            GameActivity.launch(this, PlayerArchetype.MAGE.asSnapshot())
        }
    }
}
