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
        val zip = java.util.zip.ZipInputStream(stream)
        val sb = StringBuilder()
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name.lowercase()
            if (!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                val content = zip.bufferedReader(Charsets.UTF_8).use { it.readText() }
                sb.append(stripHtml(content)).append("\n\n")
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        zip.close()
        val sentences = splitSentences(TextPreprocessor.process(sb.toString()))
        return ParsedDocument(sentences, List(sentences.size) { 1 })
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
