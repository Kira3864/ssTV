package com.songshu.sstv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import com.songshu.sstv.ThemeStore
import kotlin.math.abs
import kotlin.math.roundToInt

/** A fixed-center ruler. Dragging previews; releasing commits and settles once. */
class PlaybackSpeedSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val rates = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f)
    private val labels = rates.map { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val density = resources.displayMetrics.density
    private val valueTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
    private val labelTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    private val spacing = 48f * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var position = 2f
    private var committed = 2
    private var downX = 0f
    private var downPosition = 2f
    private var activePointer = -1
    private var dragging = false
    private var animator: ValueAnimator? = null
    var onSpeedSelected: ((Float) -> Unit)? = null
    val selectedSpeed get() = rates[committed]

    init { isFocusable = true; isClickable = true; updateDescription() }

    fun setSpeed(rate: Float) {
        animator?.cancel()
        committed = rates.indices.minByOrNull { abs(rates[it] - rate) } ?: 2
        position = committed.toFloat()
        updateDescription(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val center = width / 2f
        val preview = position.roundToInt().coerceIn(rates.indices)
        paint.typeface = medium
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE; paint.textSize = valueTextSize
        canvas.drawText(labels[preview] + "×", center, 27f * density, paint)
        paint.strokeWidth = density; paint.strokeCap = Paint.Cap.ROUND
        for (quarter in 0..rates.lastIndex * 4) {
            val tick = quarter / 4f
            val x = center + (tick - position) * spacing
            if (x < 12f * density || x > width - 12f * density) continue
            val edge = ((width / 2f - abs(x - center)) / (36f * density)).coerceIn(0f, 1f)
            val major = quarter % 4 == 0
            paint.color = Color.argb((edge * if (major) 165 else 65).toInt(), 255, 255, 255)
            canvas.drawLine(x, (if (major) 42f else 47f) * density, x, 55f * density, paint)
            if (major) {
                paint.typeface = regular
                paint.textSize = labelTextSize
                canvas.drawText(labels[quarter / 4], x, 76f * density, paint)
            }
        }
        paint.color = Color.WHITE; paint.strokeWidth = 2.5f * density
        canvas.drawLine(center, 39f * density, center, 57f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel(); activePointer = event.getPointerId(0)
                downX = event.x; downPosition = position; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointer = event.findPointerIndex(activePointer)
                if (pointer < 0) return true
                val delta = event.getX(pointer) - downX
                if (abs(delta) > slop) dragging = true
                if (dragging) {
                    position = (downPosition - delta / spacing).coerceIn(0f, rates.lastIndex.toFloat())
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activePointer == -1) return true
                val index = if (dragging) position.roundToInt()
                    else (position + (event.x - width / 2f) / spacing).roundToInt()
                finishTouch(); select(index); performClick()
            }
            MotionEvent.ACTION_CANCEL -> { finishTouch(); settle(committed) }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == activePointer) {
                finishTouch(); settle(committed)
            }
        }
        return true
    }

    private fun finishTouch() {
        activePointer = -1; dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun select(index: Int) {
        committed = index.coerceIn(rates.indices)
        updateDescription(); settle(committed); onSpeedSelected?.invoke(selectedSpeed)
    }

    private fun settle(index: Int) {
        animator?.cancel()
        if (ThemeStore.reducedMotion(context) || !ValueAnimator.areAnimatorsEnabled()) {
            position = index.toFloat(); invalidate(); return
        }
        animator = ValueAnimator.ofFloat(position, index.toFloat()).apply {
            duration = 180; interpolator = DecelerateInterpolator()
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun updateDescription() { contentDescription = "播放速度 ${labels[committed]}倍" }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, rates.lastIndex.toFloat(), committed.toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isEnabled) return false
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> select(committed + 1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> select(committed - 1)
            android.R.id.accessibilityActionSetProgress -> select(arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.roundToInt() ?: committed)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { select(committed - 1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { select(committed + 1); true }
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onDetachedFromWindow() { animator?.cancel(); finishTouch(); super.onDetachedFromWindow() }
}
