package com.blindreader

import android.content.Context
import android.net.Uri
import java.io.InputStream

/** Wyodrębnia czysty tekst z dokumentu (PDF, TXT, EPUB). */
object DocumentParser {

    private var pdfboxInitialized = false

    /** Wynik parsowania: zdania + numer strony (1-based) każdego zdania. */
    data class ParsedDocument(
        val sentences: List<String>,
        val pageOfSentence: List<Int>
    )

    fun parse(context: Context, uri: Uri): String {
        return parseDocument(context, uri).sentences.joinToString("\n\n")
    }

    fun parseDocument(context: Context, uri: Uri): ParsedDocument {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val stream: InputStream = if (uri.scheme == "file") {
            java.io.File(uri.path!!).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Nie można otworzyć pliku")
        }
        return stream.use {
            when {
                name.endsWith(".pdf") -> parsePdf(context, it)
                name.endsWith(".epub") -> parseEpub(it)
                else -> parseText(it)
            }
        }
    }

    private fun parseText(stream: InputStream): ParsedDocument {
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val sentences = splitSentences(TextPreprocessor.process(text))
        return ParsedDocument(sentences, List(sentences.size) { 1 })
    }

    private fun parsePdf(context: Context, stream: InputStream): ParsedDocument {
        initPdfbox(context)
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(stream)
        try {
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            val pageCount = doc.numberOfPages
            val rawPages = mutableListOf<String>()
            for (page in 1..pageCount) {
                stripper.startPage = page
                stripper.endPage = page
                rawPages.add(stripper.getText(doc))
            }
            val cleanedPages = TextPreprocessor.processPages(rawPages)
            val sentences = mutableListOf<String>()
            val pages = mutableListOf<Int>()
            for (i in cleanedPages.indices) {
                val pageSentences = splitSentences(cleanedPages[i])
                for (s in pageSentences) {
                    sentences.add(s)
                    pages.add(i + 1)
                }
            }
            return ParsedDocument(sentences, pages)
        } finally {
            doc.close()
        }
    }

    private fun splitSentences(text: String): List<String> {
        return text
            .split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun initPdfbox(context: Context) {
        if (!pdfboxInitialized) {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            pdfboxInitialized = true
        }
    }

    private fun parseEpub(stream: InputStream): ParsedDocument {
        // Wczytaj cały archiwum do pamięci (path -> treść), potem czytaj pliki
        // w kolejności "spine" z content.opf (a nie w kolejności ZIP), pomijając
        // strony nieliterackie (okładka, strona tytułowa, spis treści, redakcyjna).
        val entries = LinkedHashMap<String, String>()
        var contentOpf: String? = null
        java.util.zip.ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.lowercase()
                    val content = readEntry(zip)
                    if (name.endsWith("content.opf")) contentOpf = content
                    if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                        entries[name.substringAfterLast('/')] = content
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val order = spineOrder(contentOpf)

        val sb = StringBuilder()
        for (name in order) {
            val content = entries[name] ?: continue
            val plain = stripHtml(content).trim()
            if (plain.length < 20) continue
            if (isNonContentPage(name)) continue
            sb.append(plain).append("\n\n")
        }

        val sentences = splitSentences(TextPreprocessor.process(sb.toString()))
        return ParsedDocument(sentences, List(sentences.size) { 1 })
    }

    /** Zwraca nazwy plików HTML w kolejności spine z content.opf. */
    private fun spineOrder(contentOpf: String?): List<String> {
        if (contentOpf == null) return emptyList()
        val hrefById = HashMap<String, String>()
        // Odporny na kolejność atrybutów: wyciągnij id i href osobno z każdego <item>.
        for (m in Regex("<item\\b[^>]*/?>").findAll(contentOpf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            hrefById[id] = href
        }
        val order = mutableListOf<String>()
        for (m in Regex("<itemref\\b[^>]*/?>").findAll(contentOpf)) {
            val id = attr(m.value, "idref") ?: continue
            val href = hrefById[id] ?: continue
            order.add(href.substringAfterLast('/').lowercase())
        }
        return order
    }

    /** Wyciąga wartość atrybutu z tagu HTML (odporne na kolejność i cudzysłowy). */
    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']").find(tag) ?: return null
        return m.groupValues[1]
    }

    /** Strony nieliterackie pomijane przy czytaniu. */
    private fun isNonContentPage(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.contains("cover") || lower.contains("toc") || lower.contains("redakcy") ||
            lower.contains("colophon") || lower.contains("copyright") || lower.startsWith("index") ||
            lower.contains("title")) {
            return true
        }
        return false
    }

    private fun readEntry(zip: java.util.zip.ZipInputStream): String {
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        while (zip.read(buf).also { n = it } != -1) {
            baos.write(buf, 0, n)
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
