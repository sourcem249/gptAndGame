package com.example.vampsurvivor.systems

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import com.example.vampsurvivor.R

class AudioController(context: Context) {

    private val bgm: MediaPlayer? = runCatching {
        MediaPlayer.create(context, R.raw.bgm_loop)?.apply {
            isLooping = true
            setVolume(0.5f, 0.5f)
        }
    }.getOrElse { error ->
        Log.w(TAG, "Unable to initialise background music", error)
        null
    }

    private val soundPool: SoundPool? = runCatching {
        SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }.getOrElse { error ->
        Log.w(TAG, "Unable to initialise sound effects", error)
        null
    }

    private val hitSoundId: Int? = soundPool?.load(context, R.raw.hit, 1)?.let { id ->
        if (id == 0) {
            Log.w(TAG, "Failed to load hit sound")
            null
        } else {
            id
        }
    }

    fun playBgm() {
        val player = bgm ?: return
        if (!player.isPlaying) {
            runCatching { player.start() }.onFailure { error ->
                Log.w(TAG, "Unable to start background music", error)
            }
        }
    }

    fun pauseBgm() {
        val player = bgm ?: return
        if (player.isPlaying) {
            runCatching { player.pause() }.onFailure { error ->
                Log.w(TAG, "Unable to pause background music", error)
            }
        }
    }

    fun playHit() {
        val pool = soundPool ?: return
        val soundId = hitSoundId ?: return
        pool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        bgm?.release()
    }

    private companion object {
        const val TAG = "AudioController"
    }
}
