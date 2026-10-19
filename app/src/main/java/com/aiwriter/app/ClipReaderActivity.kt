package com.aiwriter.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Şeffaf, anlık açılan aktivite. Android 10+ yalnızca odaktaki uygulamanın panoyu okumasına izin verir;
 * bu aktivite odağı alır, panoyu okur, servise verir ve hemen kapanır.
 */
class ClipReaderActivity : Activity() {
    private var done = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler.postDelayed({ if (!isFinishing) finishQuietly() }, 1500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            val text = ClipUtil.read(this)
            OverlayService.instance?.onClipText(text, intent.getBooleanExtra("manual", false))
            finishQuietly()
        }
    }

    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
