package com.aiwriter.app

class ProtocolError(msg: String) : Exception(msg)

sealed class Cmd {
    data class Root(val path: String) : Cmd()
    data class Mkdir(val path: String) : Cmd()
    data class Write(val path: String, val content: String) : Cmd()
    data class Patch(val path: String, val pairs: List<Pair<List<String>, List<String>>>) : Cmd()
    data class Delete(val path: String) : Cmd()
    data class Move(val from: String, val to: String) : Cmd()
}

class ParseResult(val cmds: List<Cmd>, val error: String?)

/**
 * AIW v1 komut formatı ayrıştırıcısı.
 *
 * Metin, (boş satırlar ve markdown çit satırı hariç) İLK satırda "@@AIW v1" ile başlamalı,
 * "@@AIW END" ile bitmeli. Aksi halde uygulama hiçbir şey yapmaz (yarım kalmış çıktı koruması).
 */
object Protocol {
    private val HEADER = Regex("^@@AIW\\s+v1(\\s.*)?$")

    private fun lines(text: String): List<String> {
        var t = text.replace("\r\n", "\n").replace('\r', '\n')
        if (t.startsWith("\uFEFF")) t = t.substring(1)
        return t.split("\n")
    }

    private fun isFence(l: String): Boolean {
        val t = l.trim()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    private fun findHeader(ls: List<String>): Int {
        for (i in ls.indices) {
            val t = ls[i].trim()
            if (t.isEmpty() || isFence(ls[i])) continue
            return if (HEADER.matches(t)) i else -1
        }
        return -1
    }

    fun isCommand(text: String): Boolean {
        if (!text.contains("@@AIW")) return false
        return findHeader(lines(text)) >= 0
    }

    private fun clean(s: String): String = s.trim().trim('"', '\'', '`')

    private fun unesc(l: String): String {
        var s = l
        if (s.startsWith("\\@@")) s = s.substring(1)
        if (s.contains("<<BT>>")) s = s.replace("<<BT>>", "`")
        return s
    }

    private fun trimBlank(l: List<String>): List<String> {
        var a = 0
        var b = l.size
        while (a < b && l[a].isBlank()) a++
        while (b > a && l[b - 1].isBlank()) b--
        return l.subList(a, b)
    }

    fun parse(text: String): ParseResult {
        return try {
            ParseResult(parseInternal(text), null)
        } catch (e: ProtocolError) {
            ParseResult(emptyList(), e.message ?: "Ayrıştırma hatası")
        }
    }

    private fun parseInternal(text: String): List<Cmd> {
        val ls = lines(text)
        val h = findHeader(ls)
        if (h < 0) throw ProtocolError("Başlık (@@AIW v1) bulunamadı")
        val indent = ls[h].takeWhile { it == ' ' || it == '\t' }

        fun strip(l: String): String =
            if (indent.isNotEmpty() && l.startsWith(indent)) l.substring(indent.length) else l

        fun fail(ln: Int, msg: String): Nothing = throw ProtocolError("Satır $ln: $msg")

        // @@END'e kadar ham satırları okur. Dönen ikinci değer, @@END'den sonraki satır indeksi.
        fun readBody(start: Int, cmdLine: Int): Pair<List<String>, Int> {
            val body = ArrayList<String>()
            var j = start
            while (j < ls.size) {
                val raw = strip(ls[j])
                val t = raw.trimEnd()
                if (t == "@@END") return Pair(body, j + 1)
                if (t.startsWith("@@") && t != "@@FIND" && t != "@@WITH") {
                    fail(j + 1, "Gövde içinde beklenmeyen komut satırı ($t). Önceki komutun @@END satırı eksik olabilir (komut satırı $cmdLine).")
                }
                body.add(raw)
                j++
            }
            fail(cmdLine, "@@END bulunamadı (çıktı yarım kalmış olabilir)")
        }

        val cmds = ArrayList<Cmd>()
        var ended = false
        var i = h + 1
        while (i < ls.size && !ended) {
            val line = strip(ls[i]).trimEnd()
            val ln = i + 1
            if (!line.startsWith("@@")) { i++; continue }   // blok dışı serbest metin yok sayılır
            val sp = line.indexOfFirst { it == ' ' || it == '\t' }
            val name = if (sp < 0) line else line.substring(0, sp)
            val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
            when (name) {
                "@@AIW" -> {
                    if (arg == "END") ended = true else fail(ln, "Beklenmeyen @@AIW satırı")
                    i++
                }
                "@@ROOT" -> {
                    if (arg.isEmpty()) fail(ln, "@@ROOT yol bekliyor")
                    cmds.add(Cmd.Root(clean(arg))); i++
                }
                "@@MKDIR" -> {
                    if (arg.isEmpty()) fail(ln, "@@MKDIR yol bekliyor")
                    cmds.add(Cmd.Mkdir(clean(arg))); i++
                }
                "@@DELETE" -> {
                    if (arg.isEmpty()) fail(ln, "@@DELETE yol bekliyor")
                    cmds.add(Cmd.Delete(clean(arg))); i++
                }
                "@@MOVE" -> {
                    val parts = arg.split("=>")
                    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) fail(ln, "@@MOVE eski => yeni biçiminde olmalı")
                    cmds.add(Cmd.Move(clean(parts[0]), clean(parts[1]))); i++
                }
                "@@WRITE" -> {
                    if (arg.isEmpty()) fail(ln, "@@WRITE yol bekliyor")
                    val (body, next) = readBody(i + 1, ln)
                    val b = body.map { unesc(it) }.dropLastWhile { it.isBlank() }
                    val content = if (b.isEmpty()) "" else b.joinToString("\n") + "\n"
                    cmds.add(Cmd.Write(clean(arg), content)); i = next
                }
                "@@PATCH" -> {
                    if (arg.isEmpty()) fail(ln, "@@PATCH yol bekliyor")
                    val (body, next) = readBody(i + 1, ln)
                    val pairs = ArrayList<Pair<List<String>, List<String>>>()
                    var mode = 0
                    var f = ArrayList<String>()
                    var w = ArrayList<String>()
                    for (b in body) {
                        val t = b.trimEnd()
                        if (t == "@@FIND") {
                            if (mode == 1) fail(ln, "@@FIND'dan sonra @@WITH gerekli")
                            if (mode == 2) {
                                pairs.add(Pair(trimBlank(f), trimBlank(w)))
                                f = ArrayList(); w = ArrayList()
                            }
                            mode = 1
                        } else if (t == "@@WITH") {
                            if (mode != 1) fail(ln, "@@WITH'ten önce @@FIND gerekli")
                            mode = 2
                        } else if (mode == 1) {
                            f.add(unesc(b))
                        } else if (mode == 2) {
                            w.add(unesc(b))
                        } else if (b.isNotBlank()) {
                            fail(ln, "@@PATCH içinde @@FIND'dan önce içerik olamaz")
                        }
                    }
                    if (mode != 2) fail(ln, "@@PATCH içinde @@FIND/@@WITH çifti eksik")
                    pairs.add(Pair(trimBlank(f), trimBlank(w)))
                    for (p in pairs) if (p.first.isEmpty()) fail(ln, "@@FIND boş olamaz")
                    cmds.add(Cmd.Patch(clean(arg), pairs)); i = next
                }
                else -> fail(ln, "Bilinmeyen komut: $name")
            }
        }
        if (!ended) throw ProtocolError("Komut yarım kalmış: '@@AIW END' satırı yok. Hiçbir şey uygulanmadı.")
        if (cmds.isEmpty()) throw ProtocolError("Blok hiç komut içermiyor")
        return cmds
    }
}
