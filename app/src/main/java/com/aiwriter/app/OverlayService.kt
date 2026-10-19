package com.aiwriter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Yüzen halkayı çizen basit görünüm. Halka rengi durumu gösterir. */
class BubbleView(ctx: Context) : View(ctx) {
    companion object {
        val BLUE = Color.rgb(33, 150, 243)
        val GREEN = Color.rgb(46, 204, 113)
        val RED = Color.rgb(231, 76, 60)
        val GRAY = Color.rgb(130, 140, 150)
    }

    var ringColor: Int = BLUE
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 27, 42); style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val stroke = w * 0.11f
        c.drawCircle(cx, cx, cx - stroke / 2f, fill)
        ring.strokeWidth = stroke
        ring.color = ringColor
        c.drawCircle(cx, cx, cx - stroke / 2f, ring)
        label.textSize = w * 0.30f
        val y = cx - (label.descent() + label.ascent()) / 2f
        c.drawText("AI", cx, y, label)
    }
}

class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.aiwriter.app.STOP"
        private const val CHANNEL = "aiw_channel"
        private const val NOTIF_ID = 1
        private const val BUBBLE_DP = 56

        @Volatile
        var instance: OverlayService? = null

        val running: Boolean
            get() = instance != null
    }

    private lateinit var wm: WindowManager
    private lateinit var cm: ClipboardManager
    private val main = Handler(Looper.getMainLooper())

    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelLog: TextView? = null
    private var panelScroll: ScrollView? = null
    private var pauseBtn: TextView? = null

    private var lastEvent = 0L
    private var ignoreUntil = 0L
    private var paused = false
    private var waiting = false
    private var manualPending = false

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val logListener: () -> Unit = { refreshLog() }
    private val resetColor = Runnable { bubble?.ringColor = if (paused) BubbleView.GRAY else BubbleView.BLUE }
    private val readerTimeout = Runnable {
        if (waiting) {
            waiting = false
            if (manualPending) {
                LogStore.add("[" + LogStore.ts() + "] Pano okunamadı (odak alınamadı)")
                flash(BubbleView.RED)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        try {
            startInForeground()
        } catch (e: Exception) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Yüzen pencere izni verilmemiş", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        instance = this
        addBubble()
        cm.addPrimaryClipChangedListener(clipListener)
        LogStore.addListener(logListener)
        LogStore.add("[" + LogStore.ts() + "] Servis başladı, pano dinleniyor")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { cm.removePrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        LogStore.removeListener(logListener)
        main.removeCallbacksAndMessages(null)
        removePanel()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
        instance = null
        super.onDestroy()
    }

    // ---------- bildirim / ön plan ----------

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI Yazıcı", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("AI Yazıcı çalışıyor")
            .setContentText("Pano dinleniyor. Yüzen halkaya dokunarak paneli aç.")
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(icon, "Durdur", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1 shl 30
            startForeground(NOTIF_ID, n, 1 shl 30)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
    }

    // ---------- pano ----------

    private fun onClipChanged() {
        if (paused) return
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreUntil) return
        if (now - lastEvent < 1000) return      // 1 sn aralık: çift tetiklenmeyi engeller
        lastEvent = now
        val text = ClipUtil.read(this)
        if (text != null) {
            handleText(text, false)
        } else {
            launchReader(false)                  // arka planda pano okunamaz: odak alan aktivite aç
        }
    }

    private fun launchReader(manual: Boolean) {
        try {
            val i = Intent(this, ClipReaderActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra("manual", manual)
            waiting = true
            manualPending = manual
            main.removeCallbacks(readerTimeout)
            main.postDelayed(readerTimeout, 2500)
            startActivity(i)
        } catch (e: Exception) {
            waiting = false
            if (manual) {
                LogStore.add("[" + LogStore.ts() + "] Pano okuyucu açılamadı: " + e.message)
                flash(BubbleView.RED)
            }
        }
    }

    /** ClipReaderActivity tarafından çağrılır (ana iş parçacığı). */
    fun onClipText(text: String?, manual: Boolean) {
        waiting = false
        main.removeCallbacks(readerTimeout)
        if (text == null) {
            if (manual) {
                LogStore.add("[" + LogStore.ts() + "] Pano boş veya okunamadı")
                flash(BubbleView.RED)
            }
            return
        }
        handleText(text, manual)
    }

    private fun handleText(text: String, manual: Boolean) {
        if (!Protocol.isCommand(text)) {
            if (manual) {
                LogStore.add("[" + LogStore.ts() + "] Panoda AIW komutu yok")
                flash(BubbleView.RED)
            }
            return
        }
        Applier.apply(this, text) { out ->
            flash(if (out.ok) BubbleView.GREEN else BubbleView.RED)
            Toast.makeText(this, out.summary, Toast.LENGTH_SHORT).show()
        }
    }

    private fun flash(color: Int) {
        main.removeCallbacks(resetColor)
        bubble?.ringColor = color
        main.postDelayed(resetColor, 3000)
    }

    // ---------- yüzen buton ----------

    private fun addBubble() {
        val size = dp(BUBBLE_DP)
        val prefs = getSharedPreferences("aiw", Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt("bx", dp(8))
        lp.y = prefs.getInt("by", dp(220))
        val v = BubbleView(this)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx
                        val dy = e.rawY - ty
                        if (abs(dx) > slop || abs(dy) > slop) moved = true
                        if (moved) {
                            lp.x = sx + dx.toInt()
                            lp.y = sy + dy.toInt()
                            try { wm.updateViewLayout(view, lp) } catch (ex: Exception) { }
                            positionPanel()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        else prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
                    }
                }
                return true
            }
        })
        wm.addView(v, lp)
        bubble = v
        bubbleLp = lp
    }

    // ---------- panel ----------

    private fun togglePanel() {
        if (panel != null) removePanel() else showPanel()
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panel = null
        panelLp = null
        panelLog = null
        panelScroll = null
        pauseBtn = null
    }

    private fun showPanel() {
        val m = resources.displayMetrics
        val w = minOf(m.widthPixels - dp(16), dp(340))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(10), dp(12), dp(10))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2101820"))
        bg.cornerRadius = dp(16).toFloat()
        box.background = bg

        val title = TextView(this)
        title.text = "AI Yazıcı"
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.setTypeface(title.typeface, Typeface.BOLD)
        box.addView(title)

        val tv = TextView(this)
        tv.setTextColor(Color.parseColor("#C9D6E3"))
        tv.textSize = 11f
        tv.typeface = Typeface.MONOSPACE
        tv.setPadding(dp(4), dp(4), dp(4), dp(4))
        val sv = ScrollView(this)
        sv.addView(tv)
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
        panelLog = tv
        panelScroll = sv

        val pb = makeButton(if (paused) "▶ Devam" else "⏸ Duraklat", 12f) { togglePause() }
        pauseBtn = pb
        box.addView(row(
            makeButton("📋 Panodan uygula", 12f) { launchReader(true) },
            makeButton("↩ Geri al", 12f) {
                Applier.undo(this) { out -> flash(if (out.ok) BubbleView.GREEN else BubbleView.RED) }
            }
        ))
        box.addView(row(
            pb,
            makeButton("📄 Talimatı kopyala", 12f) { copyPrompt() }
        ))
        box.addView(row(
            makeButton("▼ Küçült", 12f) { removePanel() },
            makeButton("⏻ Kapat", 12f, "#7A2E2E") { stopForegroundCompat(); stopSelf() }
        ))

        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(box, lp)
        panel = box
        panelLp = lp
        positionPanel()
        refreshLog()
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            r.addView(v, lp)
        }
        return r
    }

    private fun positionPanel() {
        val p = panel ?: return
        val lp = panelLp ?: return
        val b = bubbleLp ?: return
        val m = resources.displayMetrics
        val h = dp(300)
        lp.x = b.x.coerceIn(dp(8), maxOf(dp(8), m.widthPixels - lp.width - dp(8)))
        var y = b.y + dp(BUBBLE_DP) + dp(6)
        if (y + h > m.heightPixels) y = b.y - h - dp(6)
        lp.y = maxOf(dp(24), y)
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) { }
    }

    private fun refreshLog() {
        val tv = panelLog ?: return
        tv.text = LogStore.tail(40)
        panelScroll?.post { panelScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun togglePause() {
        paused = !paused
        pauseBtn?.text = if (paused) "▶ Devam" else "⏸ Duraklat"
        main.removeCallbacks(resetColor)
        bubble?.ringColor = if (paused) BubbleView.GRAY else BubbleView.BLUE
        LogStore.add("[" + LogStore.ts() + "] " + (if (paused) "Dinleme duraklatıldı" else "Dinleme sürüyor"))
    }

    private fun copyPrompt() {
        val root = getSharedPreferences("aiw", Context.MODE_PRIVATE).getString("root", "") ?: ""
        ignoreUntil = SystemClock.elapsedRealtime() + 2000
        ClipUtil.write(this, Prompt.build(root))
        Toast.makeText(this, "Sistem talimatı kopyalandı", Toast.LENGTH_SHORT).show()
    }
}
