package com.laoyang.jingying.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/**
 * 桌面悬浮窗那颗「蜘蛛」。
 *
 * 全自绘，没有用任何图片资源 —— 一张 png 打进包里要占几 KB，而这里需要的
 * 只是「随进度涨水」和「呼吸」，都是画笔层面的事，画出来反而更干净。
 *
 * 一、形状（[buildShape]）
 * 一只蜘蛛：一个头 + 一个腹，加左右各四条腿，腿用二次贝塞尔画成圆弧，
 * 这样看上去是「弯的」而不是一段段直线拼的。
 * 所有坐标都写成 **0~1 的归一化值**再乘 `min(w,h)` —— 不管这块 view
 * 被父布局给成多大，画出来永远是同一个比例，不会被拉扁。
 *
 * 二、进度（[onDraw] ②）
 * 不是画一圈弧，而是**从下往上「涨水」**：
 * 先把整只蜘蛛用很淡的淡蓝画一遍（[GHOST_A]，约 10% 透明度 ——
 * 所以进度是 0 的时候它看上去基本是「透明」的），
 * 再把画布裁到「水位线以下」重画一遍实心淡蓝。
 * 水线往上走，蜘蛛就被一格一格染蓝。
 *
 * 三、呼吸灯（[startPulse]）
 * `ok = true` 之后整只已经全蓝了，再让它明暗往复：
 * 透明度 32% ↔ 100% 来回摆，同时背后垫一层跟着明灭的淡蓝光晕
 * （[glowPaint] 的 RadialGradient）。这就成了「呼吸灯」。
 *
 * 四、下挂了（`bad = true`）
 * 整只转暗红、不呼吸 —— 一眼能跟「下完了」区分开。
 *
 * 【为什么要处理 onDetachedFromWindow】
 * 呼吸动画是个 INFINITE 的 ValueAnimator，它持有本 view 的引用。
 * 悬浮窗被撤（服务停了 / 用户关掉）时 view 会 detach，必须在这里把动画停了，
 * 否则动画会一直跑下去 —— 白耗电，而且 view 回收不掉。
 */
class SpiderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density

    // ───────────────────────── 对外状态 ─────────────────────────

    /** 0-100。ok 为真时忽略它（那时整只都是满的） */
    var percent: Int = 0
        set(v) {
            val n = v.coerceIn(0, 100)
            if (field != n) {
                field = n
                invalidate()
            }
        }

    /** 下完了 —— 整只满蓝 + 开始呼吸 */
    var ok: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (v) startPulse() else stopPulse()
        }

    /** 下挂了 —— 整只暗红，不呼吸 */
    var bad: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    // ───────────────────────── 画笔 ─────────────────────────

    /** 还没被「水」淹到的部分 —— 淡到几乎看不见 */
    private val ghostLeg = strokePaint(GHOST_A)
    private val ghostBody = fillPaint(GHOST_A)

    /** 已经被淹到的部分 —— 实心淡蓝 */
    private val solidLeg = strokePaint(255)
    private val solidBody = fillPaint(255)

    /** 呼吸时的光晕（RadialGradient 在 onSizeChanged 里建） */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ───────────────────────── 形状 ─────────────────────────

    private val legs = Path()
    private val body = Path()
    private val ovalRect = RectF()

    /** 呼吸相位 0..1。非 ok 状态恒为 0（表示「不衰减」由 [onDraw] 里换算） */
    private var phase = 0f
    private var pulse: ValueAnimator? = null

    // ───────────────────────── 尺寸 ─────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 定死成正方形。腿是左右对称的，被拉成椭圆就难看了
        val size = (SIZE_DP * d).toInt()
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShape(w.toFloat(), h.toFloat())
        if (w > 0 && h > 0 && min(w, h) > 0) {
            val r = min(w, h) / 2f
            glowPaint.shader = RadialGradient(
                w / 2f, h / 2f, r,
                intArrayOf(GLOW, GLOW and 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    /**
     * 把归一化坐标摊到实际像素上。只在这里做一次乘法 —— 每帧重算纯属浪费。
     *
     * 腿的写法：`moveTo(起点) → quadTo(控制点 → 终点)`。
     * 控制点刻意甩在起点和终点连线的外侧，这样腿是「外弓」的，
     * 一眼看过去像关节，而不是一根斜杠。
     */
    private fun buildShape(w: Float, h: Float) {
        val s = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        fun px(n: Float) = cx + n * s
        fun py(n: Float) = cy + n * s

        // startX, startY, ctrlX, ctrlY, tipX, tipY —— 都是右半边，左边镜像过去
        val half = arrayOf(
            floatArrayOf(0.020f, -0.160f, 0.250f, -0.300f, 0.300f, -0.455f), // 前腿，斜向外上
            floatArrayOf(0.070f, -0.075f, 0.330f, -0.115f, 0.440f, -0.235f), // 次前腿，往外略上
            floatArrayOf(0.070f, 0.075f, 0.330f, 0.115f, 0.440f, 0.235f),   // 次后腿，往外略下
            floatArrayOf(0.020f, 0.160f, 0.250f, 0.300f, 0.300f, 0.455f)     // 后腿，斜向外下
        )

        legs.reset()
        for (side in intArrayOf(1, -1)) {
            for (L in half) {
                legs.moveTo(px(side * L[0]), py(L[1]))
                legs.quadTo(px(side * L[2]), py(L[3]), px(side * L[4]), py(L[5]))
            }
        }

        body.reset()
        // 头：小圆，压在腹的上方
        body.addCircle(cx, py(HEAD_Y), HEAD_R * s, Path.Direction.CW)
        // 腹：竖着的椭圆。两个子路径同向 → WINDING 填充时自动并成一块
        ovalRect.set(cx - ABDO_RX * s, py(ABDO_TOP), cx + ABDO_RX * s, py(ABDO_BOTTOM))
        body.addOval(ovalRect, Path.Direction.CW)

        val sw = LEG_W * s
        ghostLeg.strokeWidth = sw
        solidLeg.strokeWidth = sw
    }

    // ───────────────────────── 绘制 ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 呼吸时整只一起明暗：最暗压到 32%，最亮全开
        val breathe = if (ok) PULSE_MIN + (1f - PULSE_MIN) * phase else 1f

        // ① 呼吸灯的光晕垫在最底下
        if (ok) {
            glowPaint.alpha = (GLOW_MAX_A * phase).toInt().coerceIn(0, 255)
            canvas.drawCircle(w / 2f, h / 2f, min(w, h) / 2f, glowPaint)
        }

        // ② 下挂了：整只暗红，静态。到此为止
        if (bad) {
            solidLeg.color = FAIL_RED
            solidBody.color = FAIL_RED
            solidLeg.alpha = FAIL_A
            solidBody.alpha = FAIL_A
            canvas.drawPath(legs, solidLeg)
            canvas.drawPath(body, solidBody)
            return
        }

        // ③ 底：整只淡淡的一层。进度 0 的时候看上去就是这个 —— 基本透明
        ghostLeg.alpha = GHOST_A
        ghostBody.alpha = GHOST_A
        canvas.drawPath(legs, ghostLeg)
        canvas.drawPath(body, ghostBody)

        // ④ 已下的部分：裁到「水位线以下」再画实心，水就涨上来了
        val filled = if (ok) 1f else percent / 100f
        if (filled > 0f) {
            val alpha = if (ok) (255 * breathe).toInt().coerceIn(0, 255) else 255
            solidLeg.color = FILL_BLUE
            solidBody.color = FILL_BLUE
            solidLeg.alpha = alpha
            solidBody.alpha = alpha

            val save = canvas.save()
            canvas.clipRect(0f, h - h * filled, w, h)
            canvas.drawPath(legs, solidLeg)
            canvas.drawPath(body, solidBody)
            canvas.restoreToCount(save)
        }
    }

    // ───────────────────────── 呼吸动画 ─────────────────────────

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            // 加速-减速：看起来像「吸一口气再吐出来」，线性会像闪灯
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        phase = 0f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 窗口重建（悬浮窗被移除后再挂）时，如果已经是完成态就把呼吸接上
        if (ok) startPulse()
    }

    override fun onDetachedFromWindow() {
        // 必须停 —— 无限循环的动画会一直持着这个 view 不放
        stopPulse()
        super.onDetachedFromWindow()
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun strokePaint(a: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = FILL_BLUE
        alpha = a
    }

    private fun fillPaint(a: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = FILL_BLUE
        alpha = a
    }

    private companion object {
        /** 悬浮窗上那颗蜘蛛的边长 */
        const val SIZE_DP = 64f

        // ── 形状。全部是「占边长 min(w,h) 的比例」，原点在 view 正中 ──
        //
        // 这几个数字不是随手写的：验收脚本 _recompute_spider.py 会把它们
        // 从这份源码里抠出来重算一遍，「最外缘 + 半个描边宽」必须 ≤ 0.5，
        // 否则腿尖会被 view 的边界切掉。

        /** 腿的粗细 */
        const val LEG_W = 0.062f

        /** 头：圆心 (0, HEAD_Y)、半径 HEAD_R */
        const val HEAD_Y = -0.175f
        const val HEAD_R = 0.080f

        /** 腹：横半宽 ABDO_RX，上端 ABDO_TOP、下端 ABDO_BOTTOM */
        const val ABDO_RX = 0.125f
        const val ABDO_TOP = -0.135f
        const val ABDO_BOTTOM = 0.255f

        // ── 颜色与动画 ──

        /** 淡蓝（浅天蓝）。这是「水」的颜色，也是呼吸灯的颜色 */
        const val FILL_BLUE = 0xFF4FC3F7.toInt()

        /** 呼吸灯最亮时那层光晕的颜色 */
        const val GLOW = 0xFF9EE7FF.toInt()

        /** 还没淹到的部分：10% 透明度 —— 就是「开始是透明」那个感觉 */
        const val GHOST_A = 26

        /** 下挂时的暗红及其透明度 */
        const val FAIL_RED = 0xFFE62429.toInt()
        const val FAIL_A = 190

        /** 呼吸最暗压到多少（1.0 = 不衰减） */
        const val PULSE_MIN = 0.32f

        /** 光晕最亮时的 alpha */
        const val GLOW_MAX_A = 150f

        /** 单程时长。来回一轮 1.1 秒，接近人喘气的节奏 */
        const val PULSE_MS = 550L
    }
}
