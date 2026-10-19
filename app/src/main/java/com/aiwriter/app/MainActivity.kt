package com.aiwriter.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val BG = Color.parseColor("#0D1B2A")
    private val MUTED = Color.parseColor("#9FB3C8")

    private lateinit var statusTv: TextView
    private lateinit var toggleBtn: TextView
    private lateinit var rootEt: EditText
    private lateinit var promptTv: TextView
    private lateinit var logTv: TextView
    private val main = Handler(Looper.getMainLooper())
    private val logListener: () -> Unit = { logTv.text = LogStore.tail(120) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        sv.clipToPadding = false
        sv.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                top = b.top
                bottom = b.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(32))
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(label("AI Yazıcı", 26f, Color.WHITE, true))
        col.addView(label("Yapay zekanın verdiği komut bloğunu kopyalayınca dosyaları otomatik oluşturur, düzenler ve siler.", 13f, MUTED, false))

        col.addView(section("1) İzinler"))
        statusTv = label("", 14f, Color.WHITE, false)
        col.addView(statusTv)
        col.addView(btn("Yüzen pencere izni") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(btn("Tüm dosyalara erişim izni") { requestFiles() })
        col.addView(btn("Bildirim izni") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        })
        col.addView(btn("Pil kısıtlamasını kaldır (önerilir)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        col.addView(section("2) Servis"))
        toggleBtn = btn("▶ Başlat") { toggleService() }
        col.addView(toggleBtn)

        col.addView(section("3) Proje klasörü (isteğe bağlı)"))
        col.addView(label("Yazarsan sistem talimatının sonuna eklenir. Boş bırakırsan klasörü sohbette yapay zekaya sen yazarsın.", 12f, MUTED, false))
        rootEt = EditText(this)
        rootEt.hint = "/storage/emulated/0/Download/proje"
        rootEt.setHintTextColor(Color.parseColor("#5C7089"))
        rootEt.setTextColor(Color.WHITE)
        rootEt.textSize = 14f
        rootEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        rootEt.setText(getSharedPreferences("aiw", Context.MODE_PRIVATE).getString("root", ""))
        col.addView(rootEt)

        col.addView(section("4) Yapay zeka için sistem talimatı"))
        col.addView(btn("📄 Sistem talimatını kopyala", "#1E6FD9") {
            saveRoot()
            ClipUtil.write(this, Prompt.build(rootEt.text.toString()))
            Toast.makeText(this, "Kopyalandı. Yapay zekanın sistem talimatı alanına yapıştır.", Toast.LENGTH_LONG).show()
        })
        promptTv = label("", 11f, MUTED, false)
        promptTv.typeface = Typeface.MONOSPACE
        promptTv.setTextIsSelectable(true)
        promptTv.visibility = View.GONE
        col.addView(btn("👁 Talimatı göster / gizle") {
            if (promptTv.visibility == View.GONE) {
                promptTv.text = Prompt.build(rootEt.text.toString())
                promptTv.visibility = View.VISIBLE
            } else {
                promptTv.visibility = View.GONE
            }
        })
        col.addView(promptTv)

        col.addView(section("5) İşlemler"))
        col.addView(btn("📋 Panodan şimdi uygula") { applyFromClipboard() })
        col.addView(btn("↩ Son işlemi geri al") {
            Applier.undo(this) { out -> Toast.makeText(this, out.summary, Toast.LENGTH_SHORT).show() }
        })

        col.addView(section("6) Kayıt"))
        logTv = label("", 12f, Color.parseColor("#C9D6E3"), false)
        logTv.typeface = Typeface.MONOSPACE
        logTv.setTextIsSelectable(true)
        col.addView(logTv)
        col.addView(btn("Kaydı temizle") { LogStore.clear() })

        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        logTv.text = LogStore.tail(120)
        LogStore.addListener(logListener)
    }

    override fun onPause() {
        LogStore.removeListener(logListener)
        saveRoot()
        super.onPause()
    }

    private fun saveRoot() {
        getSharedPreferences("aiw", Context.MODE_PRIVATE).edit().putString("root", rootEt.text.toString().trim()).apply()
    }

    // ---------- yardımcılar ----------

    private fun label(t: String, size: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        v.setPadding(0, dp(4), 0, dp(4))
        return v
    }

    private fun section(t: String): TextView {
        val v = label(t, 16f, Color.parseColor("#4FA3FF"), true)
        v.setPadding(0, dp(20), 0, dp(4))
        return v
    }

    private fun btn(t: String, fill: String = "#2B4160", onClick: () -> Unit): TextView {
        val b = makeButton(t, 14f, fill, onClick)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(4), 0, dp(4))
        b.layoutParams = lp
        return b
    }

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

    private fun mark(ok: Boolean) = if (ok) "✔ " else "✖ "

    private fun refresh() {
        statusTv.text = mark(hasOverlay()) + "Yüzen pencere izni\n" +
            mark(hasFiles()) + "Tüm dosyalara erişim\n" +
            mark(hasNotif()) + "Bildirim izni (isteğe bağlı)"
        toggleBtn.text = if (OverlayService.running) "■ Durdur" else "▶ Başlat"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun requestFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1
            )
        }
    }

    private fun toggleService() {
        val i = Intent(this, OverlayService::class.java)
        if (OverlayService.running) {
            stopService(i)
        } else {
            if (!hasOverlay()) { Toast.makeText(this, "Önce yüzen pencere iznini ver", Toast.LENGTH_LONG).show(); return }
            if (!hasFiles()) { Toast.makeText(this, "Önce dosya erişim iznini ver", Toast.LENGTH_LONG).show(); return }
            saveRoot()
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        main.postDelayed({ refresh() }, 600)
    }

    private fun applyFromClipboard() {
        if (!hasFiles()) { Toast.makeText(this, "Önce dosya erişim iznini ver", Toast.LENGTH_LONG).show(); return }
        val t = ClipUtil.read(this)
        when {
            t == null -> Toast.makeText(this, "Pano boş", Toast.LENGTH_SHORT).show()
            !Protocol.isCommand(t) -> {
                Toast.makeText(this, "Panoda geçerli AIW komutu yok", Toast.LENGTH_LONG).show()
                LogStore.add("[" + LogStore.ts() + "] Panoda AIW komutu yok (ilk satır @@AIW v1 olmalı)")
            }
            else -> Applier.apply(this, t) { out -> Toast.makeText(this, out.summary, Toast.LENGTH_SHORT).show() }
        }
    }
}
