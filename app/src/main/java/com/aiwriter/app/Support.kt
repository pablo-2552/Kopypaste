package com.aiwriter.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.makeButton(label: String, sizeSp: Float = 14f, fill: String = "#2B4160", onClick: () -> Unit): TextView {
    val t = TextView(this)
    t.text = label
    t.textSize = sizeSp
    t.setTextColor(Color.WHITE)
    t.gravity = Gravity.CENTER
    t.setPadding(dp(8), dp(12), dp(8), dp(12))
    val bg = GradientDrawable()
    bg.setColor(Color.parseColor(fill))
    bg.cornerRadius = dp(12).toFloat()
    t.background = bg
    t.setOnClickListener { onClick() }
    return t
}

object ClipUtil {
    fun read(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!cm.hasPrimaryClip()) return null
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0).coerceToText(ctx)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun write(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AIW", text))
    }
}

object LogStore {
    private val lines = ArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun ts(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    @Synchronized
    fun add(s: String) {
        lines.add(s)
        while (lines.size > 400) lines.removeAt(0)
        main.post { for (l in listeners) l() }
    }

    @Synchronized
    fun tail(n: Int): String {
        if (lines.isEmpty()) return "(henüz kayıt yok)"
        return lines.subList(maxOf(0, lines.size - n), lines.size).joinToString("\n")
    }

    @Synchronized
    fun clear() {
        lines.clear()
        main.post { for (l in listeners) l() }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}

object Applier {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun undoDir(ctx: Context) = File(ctx.filesDir, "undo")

    private fun finish(out: Outcome, cb: (Outcome) -> Unit) {
        LogStore.add("[" + LogStore.ts() + "] " + out.summary)
        for (l in out.logs) LogStore.add("   $l")
        main.post { cb(out) }
    }

    fun apply(ctx: Context, text: String, cb: (Outcome) -> Unit) {
        val dir = undoDir(ctx.applicationContext)
        exec.execute {
            val out = try {
                FileOps.run(dir, text)
            } catch (e: Throwable) {
                Outcome(false, false, listOf("✖ Beklenmeyen hata: " + e.message), "Hata")
            }
            finish(out, cb)
        }
    }

    fun undo(ctx: Context, cb: (Outcome) -> Unit) {
        val dir = undoDir(ctx.applicationContext)
        exec.execute {
            val out = try {
                UndoStore(dir).undo()
            } catch (e: Throwable) {
                Outcome(false, false, listOf("✖ Beklenmeyen hata: " + e.message), "Geri alma hatası")
            }
            finish(out, cb)
        }
    }
}
