package com.blindreader

import android.content.Context
import android.net.Uri
import java.io.InputStream

/** Wyodrębnia czysty tekst z dokumentu (PDF, TXT, EPUB). */
object DocumentParser {

    fun parse(context: Context, uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val stream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Nie można otworzyć pliku")
        return stream.use {
            when {
                name.endsWith(".pdf") -> parsePdf(it)
                name.endsWith(".epub") -> parseEpub(it)
                else -> parseText(it)
            }
        }
    }

    private fun parseText(stream: InputStream): String {
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parsePdf(stream: InputStream): String {
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(stream)
        try {
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            return stripper.getText(doc)
        } finally {
            doc.close()
        }
    }

    private fun parseEpub(stream: InputStream): String {
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
        return sb.toString()
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
