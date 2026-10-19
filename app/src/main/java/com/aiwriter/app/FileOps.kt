package com.aiwriter.app

import java.io.File
import java.io.IOException

class Outcome(val ok: Boolean, val changed: Boolean, val logs: List<String>, val summary: String)

/** Yol güvenliği: yalnızca /storage/... altı, Android/data ve Android/obb yasak. */
object PathGuard {
    private const val PRIMARY = "/storage/emulated/0"
    private val ALIASES = listOf(
        "/sdcard", "/mnt/sdcard", "/storage/self/primary",
        "/mnt/shell/emulated/0", "/storage/emulated/legacy"
    )

    private fun canon(raw: String): String {
        val out = ArrayList<String>()
        for (seg in raw.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1) else throw ProtocolError("Geçersiz yol: $raw")
                else -> out.add(seg)
            }
        }
        var n = "/" + out.joinToString("/")
        for (a in ALIASES) {
            if (n == a || n.startsWith("$a/")) { n = PRIMARY + n.substring(a.length); break }
        }
        return n
    }

    private fun validate(n: String) {
        if (!n.startsWith("/storage/")) {
            throw ProtocolError("İzin verilmeyen konum: $n (yalnızca /storage/ altı kullanılabilir)")
        }
        val low = ("$n/").lowercase()
        if (low.contains("/android/data/") || low.contains("/android/obb/")) {
            throw ProtocolError("Android/data ve Android/obb klasörlerine erişim engelli: $n")
        }
    }

    fun normalizeRoot(raw: String): String {
        val p = raw.trim()
        if (!p.startsWith("/")) throw ProtocolError("@@ROOT mutlak yol olmalı (/storage/... gibi): $p")
        val n = canon(p)
        validate(n)
        return n
    }

    fun resolve(root: String?, raw: String): File {
        val p = raw.trim()
        if (p.isEmpty()) throw ProtocolError("Boş yol")
        val abs = p.startsWith("/")
        val joined = if (abs) p else {
            if (root == null) throw ProtocolError("Göreli yol için önce @@ROOT verilmeli: $p")
            root.trimEnd('/') + "/" + p
        }
        val n = canon(joined)
        if (!abs && root != null && n != root && !n.startsWith("$root/")) {
            throw ProtocolError("Yol @@ROOT dışına çıkıyor: $p")
        }
        validate(n)
        return File(n)
    }

    private fun depthBelowVolume(n: String): Int {
        val segs = n.trim('/').split('/')
        val vol = if (segs.size > 1 && segs[1] == "emulated") 3 else 2
        return segs.size - vol
    }

    fun checkDelete(f: File, root: String?) {
        if (depthBelowVolume(f.path) <= 1) {
            throw ProtocolError("Üst düzey klasör/depolama silinemez: ${f.path}")
        }
        if (root != null && f.path == root) throw ProtocolError("@@ROOT klasörünün kendisi silinemez: ${f.path}")
    }
}

/** Son işlemi geri almak için yedek deposu (yalnızca son uygulanan işlem tutulur). */
class UndoStore(private val dir: File) {
    private val manifest = File(dir, "manifest.txt")
    private val entries = ArrayList<String>()
    private var counter = 0

    fun begin() { dir.deleteRecursively(); dir.mkdirs(); entries.clear(); counter = 0 }

    fun recordFile(f: File) {
        if (f.exists()) {
            val id = "b" + (counter++)
            f.copyRecursively(File(dir, id), true)
            entries.add("R\t${f.path}\t$id")
        } else {
            entries.add("N\t${f.path}")
        }
    }

    fun recordTree(f: File) = recordFile(f)
    fun recordMove(from: File, to: File) { entries.add("M\t${from.path}\t${to.path}") }
    fun commit() { dir.mkdirs(); manifest.writeText(entries.joinToString("\n")) }

    fun hasUndo(): Boolean = manifest.exists() && manifest.readText().isNotBlank()

    fun undo(): Outcome {
        if (!hasUndo()) return Outcome(false, false, listOf("Geri alınacak işlem yok"), "Geri alınacak işlem yok")
        val logs = ArrayList<String>()
        var ok = true
        val lines = manifest.readText().split("\n").filter { it.isNotBlank() }
        for (line in lines.reversed()) {
            val p = line.split("\t")
            try {
                when (p[0]) {
                    "R" -> {
                        val target = File(p[1])
                        target.deleteRecursively()
                        target.parentFile?.mkdirs()
                        File(dir, p[2]).copyRecursively(target, true)
                    }
                    "N" -> File(p[1]).deleteRecursively()
                    "M" -> FileOps.moveFile(File(p[2]), File(p[1]))
                }
                logs.add("↩ " + p[1])
            } catch (e: Exception) {
                ok = false
                logs.add("✖ geri alınamadı: " + p[1] + " (" + e.message + ")")
            }
        }
        dir.deleteRecursively()
        return Outcome(ok, true, logs, if (ok) "Son işlem geri alındı" else "Geri alma kısmen başarısız")
    }
}

private sealed class Act {
    class Write(val file: File, val content: String) : Act()
    class Del(val file: File) : Act()
    class Mk(val dir: File) : Act()
    class Mv(val from: File, val to: File) : Act()
}

object FileOps {
    fun moveFile(a: File, b: File) {
        b.parentFile?.mkdirs()
        if (!a.renameTo(b)) {
            if (!a.copyRecursively(b, false)) throw IOException("Taşınamadı: ${a.path}")
            a.deleteRecursively()
        }
    }

    private fun indexesOf(hay: List<String>, needle: List<String>): List<Int> {
        val res = ArrayList<Int>()
        if (needle.isEmpty() || needle.size > hay.size) return res
        for (s in 0..(hay.size - needle.size)) {
            var ok = true
            for (k in needle.indices) {
                if (hay[s + k].trimEnd() != needle[k].trimEnd()) { ok = false; break }
            }
            if (ok) res.add(s)
        }
        return res
    }

    /**
     * Önce TÜM komutları bellekte planlar (hata varsa hiçbir şey yazılmaz),
     * sonra sırayla uygular. Aynı komut tekrar çalıştırılırsa değişmeyenler "zaten güncel" sayılır.
     */
    fun run(undoDir: File, text: String): Outcome {
        val pr = Protocol.parse(text)
        if (pr.error != null) return Outcome(false, false, listOf("✖ " + pr.error), "Komut okunamadı")

        val logs = ArrayList<String>()
        val acts = ArrayList<Act>()
        val overlay = HashMap<String, String?>()
        var root: String? = null
        var noop = 0

        fun cur(f: File): String? {
            if (overlay.containsKey(f.path)) return overlay[f.path]
            return if (f.isFile) f.readText(Charsets.UTF_8) else null
        }

        try {
            for (c in pr.cmds) {
                when (c) {
                    is Cmd.Root -> {
                        root = PathGuard.normalizeRoot(c.path)
                        logs.add("• klasör: $root")
                    }
                    is Cmd.Mkdir -> {
                        val d = PathGuard.resolve(root, c.path)
                        if (d.isDirectory) { logs.add("= ${c.path}/ (zaten var)"); noop++ }
                        else if (d.exists() || overlay[d.path] != null) throw ProtocolError("${c.path} adında bir dosya var")
                        else { acts.add(Act.Mk(d)); logs.add("+ ${c.path}/ (klasör)") }
                    }
                    is Cmd.Write -> {
                        val f = PathGuard.resolve(root, c.path)
                        if (f.isDirectory && !overlay.containsKey(f.path)) throw ProtocolError("${c.path} bir klasör, dosya yazılamaz")
                        val old = cur(f)
                        if (old != null && old.replace("\r\n", "\n") == c.content) {
                            logs.add("= ${c.path} (zaten güncel)"); noop++
                        } else {
                            overlay[f.path] = c.content
                            acts.add(Act.Write(f, c.content))
                            logs.add(if (old == null) "+ ${c.path} (oluşturuldu)" else "~ ${c.path} (güncellendi)")
                        }
                    }
                    is Cmd.Patch -> {
                        val f = PathGuard.resolve(root, c.path)
                        val src = cur(f) ?: throw ProtocolError("${c.path} bulunamadı (@@PATCH için dosya gerekli)")
                        var ls: List<String> = src.replace("\r\n", "\n").split("\n")
                        var applied = 0
                        var already = 0
                        for ((idx, pair) in c.pairs.withIndex()) {
                            val find = pair.first
                            val repl = pair.second
                            val hits = indexesOf(ls, find)
                            val replHits = if (repl.isEmpty()) emptyList() else indexesOf(ls, repl)
                            val alreadyDone = replHits.isNotEmpty() && (hits.isEmpty() || indexesOf(repl, find).isNotEmpty())
                            if (alreadyDone) {
                                already++
                            } else if (hits.size == 1) {
                                val s = hits[0]
                                ls = ls.subList(0, s) + repl + ls.subList(s + find.size, ls.size)
                                applied++
                            } else if (hits.size > 1) {
                                throw ProtocolError("${c.path}: ${idx + 1}. blok ${hits.size} yerde eşleşti; daha fazla bağlam satırı gerekli")
                            } else {
                                throw ProtocolError("${c.path}: ${idx + 1}. blok dosyada bulunamadı")
                            }
                        }
                        if (applied > 0) {
                            val out = ls.joinToString("\n")
                            overlay[f.path] = out
                            acts.add(Act.Write(f, out))
                            logs.add("~ ${c.path} (yama: $applied blok)")
                        } else {
                            logs.add("= ${c.path} (yama zaten uygulanmış)"); noop++
                        }
                    }
                    is Cmd.Delete -> {
                        val f = PathGuard.resolve(root, c.path)
                        PathGuard.checkDelete(f, root)
                        val exists = if (overlay.containsKey(f.path)) overlay[f.path] != null else f.exists()
                        if (!exists) {
                            logs.add("= ${c.path} (zaten yok)"); noop++
                        } else {
                            overlay[f.path] = null
                            acts.add(Act.Del(f))
                            logs.add("- ${c.path} (silindi)")
                        }
                    }
                    is Cmd.Move -> {
                        val a = PathGuard.resolve(root, c.from)
                        val b = PathGuard.resolve(root, c.to)
                        if (!a.exists()) {
                            if (b.exists()) { logs.add("= ${c.from} (zaten taşınmış)"); noop++ }
                            else throw ProtocolError("Taşınacak kaynak yok: ${c.from}")
                        } else {
                            PathGuard.checkDelete(a, root)
                            if (b.exists()) throw ProtocolError("Hedef zaten var: ${c.to}")
                            acts.add(Act.Mv(a, b))
                            logs.add("→ ${c.from} => ${c.to}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return Outcome(
                false, false,
                listOf("✖ " + (e.message ?: e.toString()), "↩ Hiçbir değişiklik uygulanmadı"),
                "Hata: hiçbir şey uygulanmadı"
            )
        }

        var ok = true
        if (acts.isNotEmpty()) {
            val undo = UndoStore(undoDir)
            undo.begin()
            try {
                for (a in acts) {
                    when (a) {
                        is Act.Write -> {
                            undo.recordFile(a.file)
                            a.file.parentFile?.mkdirs()
                            a.file.writeText(a.content, Charsets.UTF_8)
                        }
                        is Act.Del -> {
                            undo.recordTree(a.file)
                            if (!a.file.deleteRecursively()) throw IOException("Silinemedi: ${a.file.path}")
                        }
                        is Act.Mk -> {
                            undo.recordFile(a.dir)
                            if (!a.dir.isDirectory && !a.dir.mkdirs()) throw IOException("Klasör oluşturulamadı: ${a.dir.path}")
                        }
                        is Act.Mv -> {
                            undo.recordMove(a.from, a.to)
                            moveFile(a.from, a.to)
                        }
                    }
                }
            } catch (e: Exception) {
                ok = false
                logs.add("✖ Yazma hatası: " + (e.message ?: e.toString()))
                logs.add("  (Geri al ile eski haline dönebilirsin)")
            }
            undo.commit()
        }

        val summary = when {
            !ok -> "Hata: yazma başarısız"
            acts.isEmpty() -> "Zaten güncel"
            else -> "${acts.size} işlem uygulandı" + (if (noop > 0) ", $noop zaten güncel" else "")
        }
        return Outcome(ok, acts.isNotEmpty(), logs, summary)
    }
}
