package com.example.vampsurvivor.systems

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.example.vampsurvivor.R

class AudioController(context: Context) {

    private val bgm: MediaPlayer = MediaPlayer.create(context, R.raw.bgm_loop).apply {
        isLooping = true
        setVolume(0.5f, 0.5f)
    }

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val hitSoundId: Int = soundPool.load(context, R.raw.hit, 1)

    fun playBgm() {
        if (!bgm.isPlaying) bgm.start()
    }

    fun pauseBgm() {
        if (bgm.isPlaying) bgm.pause()
    }

    fun playHit() {
        soundPool.play(hitSoundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
        bgm.release()
    }
}
