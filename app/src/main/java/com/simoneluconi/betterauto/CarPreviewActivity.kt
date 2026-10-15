package com.simoneluconi.betterauto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.ui.launcher.LauncherRoot

/** Development aid: the car launcher full screen on the phone, to iterate on the UI without a head unit. */
class CarPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { LauncherRoot() }
    }

    override fun onStart() {
        super.onStart()
        NowPlaying.acquire(this)
    }

    override fun onStop() {
        NowPlaying.release()
        super.onStop()
    }
}
