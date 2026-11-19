package com.dzaky.strafunk.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.dzaky.strafunk.R
import com.dzaky.strafunk.data.Store

/** Efek suara pendek (disintesis sendiri), diputar lewat SoundPool biar instan. */
object Sfx {
    const val TAP = 0; const val POP = 1; const val TICK = 2; const val GO = 3; const val KM = 4
    const val SUCCESS = 5; const val PAUSE = 6; const val RESUME = 7; const val ERROR = 8
    private var pool: SoundPool? = null
    private val ids = IntArray(9)

    fun init(ctx: Context) {
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        val res = intArrayOf(R.raw.sfx_tap, R.raw.sfx_pop, R.raw.sfx_tick, R.raw.sfx_go, R.raw.sfx_km,
            R.raw.sfx_success, R.raw.sfx_pause, R.raw.sfx_resume, R.raw.sfx_error)
        res.forEachIndexed { i, r -> ids[i] = p.load(ctx, r, 1) }
        pool = p
    }

    fun play(i: Int, vol: Float = 1f) {
        if (!Store.sound) return
        pool?.play(ids[i], vol, vol, 1, 0, 1f)
    }
}
