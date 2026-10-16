package com.songshu.sstv.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.songshu.sstv.R
import com.songshu.sstv.ThemeStore
import com.songshu.sstv.themeColor

object Motion {
    fun enabled(v: View) = ValueAnimator.areAnimatorsEnabled() && !ThemeStore.reducedMotion(v.context)

    fun enter(v: View) {
        if (!enabled(v)) return
        v.alpha = 0f
        v.translationY = 10f * v.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun interactive(v: View) {
        v.isFocusable = true
        val tint = v.context.themeColor(R.attr.appAccent)
        v.foreground = RippleDrawable(ColorStateList.valueOf((tint and 0xFFFFFF) or 0x28000000), null, null)
    }

    fun bind(v: View) {
        if (v.isClickable) interactive(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) bind(v.getChildAt(i))
    }
}
