package com.laoyang.jingying.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.laoyang.jingying.CacheActivity
import com.laoyang.jingying.R
import com.laoyang.jingying.cache.DlHub
import com.laoyang.jingying.cache.DlState
import com.laoyang.jingying.cache.DlTask
import com.laoyang.jingying.fmtSize
import kotlin.math.hypot

/**
 * 桌面悬浮窗（App **外面**那个）。
 *
 * 平时就是一颗**蜘蛛**（[SpiderView]）：开始几乎透明，随进度被淡蓝色从下往上填满，
 * 下完了整只变蓝并像呼吸灯一样明灭；长按可以切成信息更全的胶囊。
 *
 * 为什么必须是**前台服务**：Android 8 起，App 退到后台后进程随时会被回收，
 * 挂在里面的悬浮窗也跟着没。前台服务是唯一能把进程稳稳按住的合法手段 ——
 * 代价是必须挂一条常驻通知，这是系统规矩，不是我们想加广告。
 *
 * 什么时候收工：所有任务都结束、且结果已经展示够（下完 [HOLD_OK_MS] /
 * 下挂 [HOLD_FAIL_MS]）之后，撤窗口 → 收通知 → 停自己。
 * 绝不留一个空壳挂在桌面上。下完这次刻意给了 12 秒 —— 呼吸灯得让人看见。
 */
class DlOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private val h = Handler(Looper.getMainLooper())

    /**
     * 「想」收成蜘蛛吗。默认 true —— 打开就是一颗蜘蛛。
     * 长按 / 点胶囊上那个「—」都是改它。
     */
    private var folded = true

    /**
     * 实际已经刷到 view 上的折叠状态（null = 还没刷过）。
     *
     * 为什么不用「读 pill 当前的 visibility 来判断要不要切」那种写法：
     * 那个可见性是**推导出来**的值，只有在「XML 里的默认形态」恰好等于
     * `folded` 的初值时才对得上，纯属巧合 —— 一旦哪天只改了一边，
     * 表现就是「打开先闪一下另一种形态」，而编译器完全不会提醒。
     *
     * 这里改成记「上次真做了什么」：`folded` 是唯一的事实来源，
     * [paint] 每 400ms 喊一次 `setFold(folded)` 也保证是纯 no-op，
     * 不会没事就去重排一次窗口。
     */
    private var appliedFold: Boolean? = null

    /** 上次靠的是哪一边。用户把它摆哪儿，下次浮出来还在老地方 —— 不记的话每次都得重拖 */
    private var preferLeft = false

    /** 上次刷通知的时间，别每 400ms 就去闹一次通知栏 */
    private var notiAt = 0L

    /** 「下完了」那条通知是替哪个任务发的 —— 发过就不再刷，省得 12 秒里刷 30 遍 */
    private var notiDoneFor = -1L

    /** 状态栏高度：小球拖到最上面时别钻到状态栏底下卡住 */
    private val topInset: Int by lazy {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id)
        else (26 * resources.displayMetrics.density).toInt()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (paint()) h.postDelayed(this, TICK_MS) else shutdown()
        }
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        makeChannel()
        // 转正必须在启动后几秒内完成，否则系统直接判我们 ANR 崩掉。
        // 所以先挂一条「正在缓存…」，等第一拍轮询跑完再换成真进度。
        //
        // 这一下是会失败的：Android 12 起，App 在后台时不允许起前台服务，
        // 系统会直接抛异常。真抛了**不能让它把进程带崩** —— 用户看到的就是
        // 「FXXK 已停止运行」，比没有悬浮窗严重得多。起不来就安静撤退。
        val up = runCatching {
            ServiceCompat.startForeground(
                this, NOTI_ID, buildNoti(getString(R.string.downloading), 0, false),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        }.isSuccess

        if (!up) {
            h.removeCallbacks(ticker)
            stopSelf()
            return
        }

        addOverlay()
        h.postDelayed(ticker, TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务可能还活着又被拉起来一次（连着下了两个片子）
        if (root == null) addOverlay()
        h.removeCallbacks(ticker)
        h.post(ticker)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        h.removeCallbacks(ticker)
        removeOverlay()
        super.onDestroy()
    }

    // ───────────────────────── 窗口 ─────────────────────────

    private fun addOverlay() {
        if (root != null) return
        // 注意：Service 不是 Activity，没有 layoutInflater 这个属性，得自己取
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_dl, null)
        val dm = resources.displayMetrics

        // 先按上次的位置摆个大概，等布局量完真实尺寸再靠边吸准
        val sp = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        preferLeft = sp.getBoolean(KEY_LEFT, false)
        val keptY = sp.getInt(KEY_Y, -1)

        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点（不然底下的 App 没法点）、允许铺到刘海/挖孔区域
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认形态是 64dp 的蜘蛛，不是 214dp 的胶囊，起始 x 要按蜘蛛的宽度让
            val w = (76 * dm.density).toInt()
            x = if (preferLeft) (6 * dm.density).toInt() else dm.widthPixels - w
            y = if (keptY > 0) keptY else (dm.heightPixels * 0.62f).toInt()
        }

        runCatching { wm.addView(v, l) }.onFailure { return }

        lp = l
        root = v
        // XML 里就是「蜘蛛显示、胶囊 GONE」，跟 folded=true 一致，直接记上，
        // 省得开局先白做一次 visibility 切换 + 重新吸边
        folded = true
        appliedFold = true

        bindTouch(v)
        v.findViewById<View>(R.id.oFold).setOnClickListener { setFold(true) }
        v.post { snapToEdge() }
    }

    private fun removeOverlay() {
        val v = root ?: return
        runCatching { wm.removeView(v) }
        root = null
        lp = null
        appliedFold = null
    }

    /**
     * 触摸：拖动挪位置、松手吸边、单击回软件、长按收成小球。
     *
     * 单击 / 长按交给 [GestureDetector]；一旦手指移动超过 touchSlop 就判定成
     * 「拖」，不再喂给它 —— 否则拖完一松手会被当成单击，人刚挪完位置就跳回 App。
     */
    private fun bindTouch(v: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var baseX = 0
        var baseY = 0
        var dragging = false

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                openApp()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                setFold(!folded)
            }
        })

        v.setOnTouchListener { _, e ->
            val l = lp
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    baseX = l?.x ?: 0
                    baseY = l?.y ?: 0
                    dragging = false
                    gd.onTouchEvent(e)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > slop) dragging = true
                    if (dragging && l != null) {
                        l.x = baseX + dx.toInt()
                        l.y = baseY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, l) }
                    } else {
                        gd.onTouchEvent(e)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge() else gd.onTouchEvent(e)
                }
            }
            true
        }
    }

    /** 松手后靠到最近的左右边，并保证不出屏 */
    private fun snapToEdge() {
        val v = root ?: return
        val l = lp ?: return
        if (v.width == 0 || v.height == 0) return

        val dm = resources.displayMetrics
        val m = (6 * dm.density).toInt()
        val centerX = l.x + v.width / 2f
        preferLeft = centerX < dm.widthPixels / 2f
        l.x = if (preferLeft) m else dm.widthPixels - v.width - m
        l.y = l.y.coerceIn(m + topInset, (dm.heightPixels - v.height - m).coerceAtLeast(m + topInset))
        runCatching { wm.updateViewLayout(v, l) }

        // 把位置记下来 —— 下次浮出来还在老地方，不用重拖
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LEFT, preferLeft)
            .putInt(KEY_Y, l.y)
            .apply()
    }

    /** 蜘蛛（folded=true）↔ 胶囊（folded=false） */
    private fun setFold(f: Boolean) {
        folded = f
        val v = root ?: return
        if (appliedFold == f) return
        appliedFold = f
        v.findViewById<View>(R.id.ovPill).visibility = if (f) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.ovBall).visibility = if (f) View.VISIBLE else View.GONE
        // 两种形态宽高差很多，得让系统重新量一遍，再重新吸边
        runCatching { lp?.let { wm.updateViewLayout(v, it) } }
        v.post { snapToEdge() }
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, CacheActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    // ───────────────────────── 刷新 ─────────────────────────

    /**
     * 把当前状态刷到悬浮窗上。
     * @return 还要继续跑吗 —— false 表示活干完了，可以收工
     */
    private fun paint(): Boolean {
        val v = root ?: return false
        val all = DlHub.list()

        // ① 还有在下的 —— 显示第一个，后面跟个「（还有 N 个）」
        val running = all.filter { it.isRunning }
        if (running.isNotEmpty()) {
            setFold(folded)
            bindRunning(v, running[0], running.size)
            pushNoti(running[0], done = false)
            return true
        }

        // ② 刚结束的 —— 取**最近**结束的那一个。
        //
        // 注意不能写成「先找 OK、找不到再找 FAIL」：下完那次要亮 12 秒，
        // 这 12 秒里要是另一条挂了，先按状态挑就会一直显示旧的成功态，
        // 新失败被盖住。按 finishAt 比时间才准。
        val now = System.currentTimeMillis()
        val last = all.filter {
            val hold = if (it.state == DlState.OK) HOLD_OK_MS else HOLD_FAIL_MS
            (it.state == DlState.OK || it.state == DlState.FAIL) && now - it.finishAt in 0..hold
        }.maxByOrNull { it.finishAt }

        if (last != null) {
            setFold(folded)
            if (last.state == DlState.OK) {
                bindDone(v, last)
                pushNoti(last, done = true)
            } else {
                bindFail(v)
            }
            return true
        }

        // ③ 取消的、或者已经亮够了 —— 收工（窗口、通知、服务一起撤）
        return false
    }

    /**
     * 在跑。蜘蛛先戳上 —— 那是默认形态，每 400ms 都得跟上；
     * 胶囊上的那几行字只有展开时才有人看，所以在 [folded] 为真时直接跳过，
     * 省掉每秒两次的半字符串拼接。
     */
    private fun bindRunning(v: View, t: DlTask, n: Int) {
        setBall(v, t.percent, ok = false, bad = false)
        if (folded) return

        val title = v.findViewById<TextView>(R.id.oTitle)
        val pct = v.findViewById<TextView>(R.id.oPercent)
        val bar = v.findViewById<ProgressBar>(R.id.oBar)
        val detail = v.findViewById<TextView>(R.id.oDetail)

        title.setTextColor(TEXT)
        title.text = buildString {
            if (t.stage.isNotEmpty()) append(t.stage).append(" · ")
            append(t.epName.ifBlank { t.title })
            if (n > 1) append("（还有 ").append(n - 1).append(" 个）")
        }

        pct.setTextColor(TEXT)
        pct.text = "${t.percent}%"
        switchBar(v, bar, ok = false)
        if (bar.progress != t.percent) bar.setProgress(t.percent, true)

        detail.text = buildString {
            append("已下 ").append(fmtSize(t.doneBytes))
            if (t.totalBytes > 0) append(" / ").append(fmtSize(t.totalBytes))
            if (t.speed > 0) append(" · ").append(fmtSize(t.speed)).append("/s")
            else append(" · 连接中…")
        }
    }

    private fun bindDone(v: View, t: DlTask) {
        // 整只淡蓝 + 呼吸灯。这一句是本次改动的重点，一定要执行
        setBall(v, 100, ok = true, bad = false)
        if (folded) return

        val title = v.findViewById<TextView>(R.id.oTitle)
        val pct = v.findViewById<TextView>(R.id.oPercent)
        val bar = v.findViewById<ProgressBar>(R.id.oBar)
        val detail = v.findViewById<TextView>(R.id.oDetail)

        title.setTextColor(OK_GREEN)
        title.text = "✓ 下载成功"

        pct.setTextColor(OK_GREEN)
        pct.text = "100%"
        switchBar(v, bar, ok = true)
        if (bar.progress != 100) bar.setProgress(100, true)

        detail.text = if (t.epCount > 1) {
            "共 ${t.okCount} 集已存好，去「我的缓存」就能看"
        } else {
            "已经存好了，去「我的缓存」就能看 · ${fmtSize(t.doneBytes)}"
        }
    }

    /** 下挂了。不再需要任务本身 —— 这里说的话跟是哪一集无关 */
    private fun bindFail(v: View) {
        setBall(v, 0, ok = false, bad = true)
        if (folded) return

        val title = v.findViewById<TextView>(R.id.oTitle)
        val pct = v.findViewById<TextView>(R.id.oPercent)
        val bar = v.findViewById<ProgressBar>(R.id.oBar)
        val detail = v.findViewById<TextView>(R.id.oDetail)

        title.setTextColor(RUN_RED)
        title.text = "✗ 下载没成"

        pct.setTextColor(RUN_RED)
        pct.text = "—"
        switchBar(v, bar, ok = false)

        detail.text = "换个源，回影片页再点一次缓存试试"
    }

    /** 把状态戳到那颗蜘蛛上。三种长相由 SpiderView 自己负责画 */
    private fun setBall(v: View, p: Int, ok: Boolean, bad: Boolean) {
        val b = v.findViewById<SpiderView>(R.id.ovBall)
        b.percent = p
        b.ok = ok
        b.bad = bad
    }

    /** 红（在下）/ 绿（下完）两套进度条换着用；只在真变了才换，免得每帧都重设一次 */
    private fun switchBar(v: View, bar: ProgressBar, ok: Boolean) {
        if (v.tag == ok) return
        bar.progressDrawable =
            getDrawable(if (ok) R.drawable.progress_bar_ok else R.drawable.progress_bar)
        v.tag = ok
    }

    private fun shutdown() {
        h.removeCallbacks(ticker)
        removeOverlay()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ───────────────────────── 通知 ─────────────────────────

    private fun makeChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CH_ID,
                getString(R.string.overlay_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.overlay_channel_desc) }
        )
    }

    private fun pushNoti(t: DlTask, done: Boolean) {
        if (done) {
            // 「下完了」只发一次。不拦着的话，收工前的 12 秒里每 400ms 就刷一遍通知
            if (notiDoneFor == t.id) return
            notiDoneFor = t.id
        } else {
            val now = System.currentTimeMillis()
            if (now - notiAt < 900) return
            notiAt = now
        }
        val name = buildString {
            if (t.stage.isNotEmpty()) append(t.stage).append(" · ")
            append(t.epName.ifBlank { t.title })
        }
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTI_ID, buildNoti(name, if (done) 100 else t.percent, done))
        }
    }

    private fun buildNoti(title: String, percent: Int, done: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, CacheActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_dl_notify)
            .setContentTitle(title)
            .setContentText(if (done) "已经存好了，去「我的缓存」就能看" else "点一下回到软件")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {

        private const val CH_ID = "jy_overlay"

        /** 通知 id，固定值 —— 免得每下一集就多攒一条通知 */
        private const val NOTI_ID = 0x4A59

        /** 记悬浮窗位置的偏好文件（故意和 DlOverlay 那个分开，别串了） */
        private const val PREF = "jy_overlay_pos"
        private const val KEY_LEFT = "left"
        private const val KEY_Y = "y"

        /** 轮询间隔。400ms 够跟手，又不至于把 CPU 烧起来 */
        private const val TICK_MS = 400L

        /**
         * 「下完了」的呼吸灯亮多久。
         *
         * 比下挂那次长得多是故意的：呼吸灯是给「瞄一眼手机才发现下好了」用的，
         * 3 秒太容易错过。12 秒之后再收工，桌面上不留东西。
         */
        private const val HOLD_OK_MS = 12000L

        /** 「下挂了」亮多久 */
        private const val HOLD_FAIL_MS = 5000L

        private const val TEXT = 0xFFF2F4F7.toInt()
        private const val RUN_RED = 0xFFE62429.toInt()
        private const val OK_GREEN = 0xFF3FB950.toInt()

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DlOverlayService::class.java))
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, DlOverlayService::class.java)) }
        }
    }
}
