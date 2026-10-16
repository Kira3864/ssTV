package com.songshu.sstv

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.songshu.sstv.ui.Motion

/** Apply before inflation; pages underneath Settings refresh when they return. */
abstract class ThemedActivity : AppCompatActivity() {
    private var appearance = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        val style = ThemeStore.current(this)
        appearance = style.id
        setTheme(style.resource)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightNavigationBars = style.id == "paper"
        if (android.os.Build.VERSION.SDK_INT < 27 && style.id == "paper") {
            window.navigationBarColor = style.ink
        }
    }

    override fun onResume() {
        super.onResume()
        window.setWindowAnimations(if (ThemeStore.reducedMotion(this)) 0 else R.style.AppWindowAnimation)
        if (appearance != ThemeStore.current(this).id) recreate()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val content = findViewById<View>(android.R.id.content)
        Motion.bind(content)
        if (savedInstanceState == null) Motion.enter(content)
    }

    /** Ignore network callbacks after navigation away or recreation. */
    fun showResult(action: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) action() }
    }
}
