package com.whereapp.messenger

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.webkit.JavascriptInterface

/**
 * Звук во время разговора.
 *
 * Решает две вещи, которые из браузера сделать нельзя:
 *
 * 1. Громкость. По умолчанию WebView играет звук как обычное медиа,
 *    поэтому кнопки громкости меняли уровень музыки, а не разговора.
 *    Переводим телефон в режим связи — тогда качелька регулирует
 *    громкость вызова, как в других мессенджерах.
 *
 * 2. Динамик. В этом же режиме можно осмысленно переключаться между
 *    разговорным динамиком у уха и громкой связью.
 */
object CallAudio {

    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var active = false

    private fun am(ctx: Context) =
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Звонок начался: забираем звук себе и не даём телефону заснуть. */
    fun start(ctx: Context, speaker: Boolean) {
        if (active) return
        active = true
        val a = am(ctx)
        savedMode = a.mode

        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .build()
                focus = req
                a.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                a.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
            }

            // Ключевая строка: телефон переходит в режим разговора,
            // и качелька громкости начинает управлять именно вызовом
            a.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeaker(ctx, speaker)
        } catch (e: Exception) {
        }

        // Экран может гаснуть, но процесс продолжает работать и звук не пропадает
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wake == null) {
                wake = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "whereapp:call"
                )
            }
            if (wake?.isHeld != true) wake?.acquire(4 * 60 * 60 * 1000L)
        } catch (e: Exception) {
        }
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        try {
            val a = am(ctx)
            @Suppress("DEPRECATION")
            a.isSpeakerphoneOn = on
        } catch (e: Exception) {
        }
    }

    /** Звонок закончился: возвращаем телефон в обычное состояние. */
    fun stop(ctx: Context) {
        if (!active) return
        active = false
        try {
            val a = am(ctx)
            @Suppress("DEPRECATION")
            a.isSpeakerphoneOn = false
            a.mode = savedMode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focus?.let { a.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                a.abandonAudioFocus(null)
            }
            focus = null
        } catch (e: Exception) {
        }
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (e: Exception) {
        }
    }
}

/** Мост для веб-части: сайт сообщает о начале и конце разговора. */
class CallBridge(private val ctx: Context) {

    @JavascriptInterface
    fun callStart(speaker: Boolean) {
        CallAudio.start(ctx, speaker)
    }

    @JavascriptInterface
    fun callSpeaker(on: Boolean) {
        CallAudio.setSpeaker(ctx, on)
    }

    @JavascriptInterface
    fun callEnd() {
        CallAudio.stop(ctx)
    }
}
