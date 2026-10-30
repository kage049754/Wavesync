package com.wavesync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent

class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = when (intent.action) {
            "com.wavesync.MEDIA_88" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "com.wavesync.MEDIA_85" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "com.wavesync.MEDIA_87" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
}
