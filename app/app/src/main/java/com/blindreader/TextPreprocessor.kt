package com.blindreader

/** Oczyszcza tekst: usuwa nagłówki, stopki, numery stron i zbędne białe znaki. */
object TextPreprocessor {

    fun process(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace("\r", "\n")

        // Usuń numery stron w osobnych liniach (np. "12", " 12 ", "Strona 12")
        text = text.replace(Regex("(?m)^\\s*\\d{1,4}\\s*$"), "")
        text = text.replace(Regex("(?m)^\\s*(strona|page)\\s+\\d{1,4}\\s*$", RegexOption.IGNORE_CASE), "")

        // Usuń nagłówki/stopki powtarzające się na każdej stronie (krótkie linie
        // powtarzające się wielokrotnie w całym dokumencie).
        text = removeRepeatedLines(text)

        // Znormalizuj białe znaki, zachowując podział na akapity.
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() && it.length > 1 }

        return paragraphs.joinToString("\n\n")
    }

    /**
     * Oczyszcza teksty stron PDF, wykrywając i usuwając nagłówki/stopki.
     * Linia powtarzająca się na górze lub dole wielu stron to header/footer.
     */
    fun processPages(pageTexts: List<String>): List<String> {
        if (pageTexts.size < 3) {
            return pageTexts.map { process(it) }
        }

        val headerLines = mutableSetOf<String>()
        val footerLines = mutableSetOf<String>()
        val headerCount = HashMap<String, Int>()
        val footerCount = HashMap<String, Int>()

        for (pageText in pageTexts) {
            val lines = pageText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            // Górne 3 linie = potencjalny header, dolne 3 = potencjalny footer.
            val top = lines.take(3)
            val bottom = lines.takeLast(3)
            for (line in top) headerCount[line] = (headerCount[line] ?: 0) + 1
            for (line in bottom) footerCount[line] = (footerCount[line] ?: 0) + 1
        }

        val threshold = (pageTexts.size * 0.6).toInt().coerceAtLeast(2)
        for ((line, count) in headerCount) if (count >= threshold) headerLines.add(line)
        for ((line, count) in footerCount) if (count >= threshold) footerLines.add(line)

        return pageTexts.map { pageText ->
            val lines = pageText.split("\n")
            val filtered = lines.filter { line ->
                val t = line.trim()
                t.isNotEmpty() && t !in headerLines && t !in footerLines
            }
            process(filtered.joinToString("\n"))
        }
    }

    private fun removeRepeatedLines(text: String): String {
        val lines = text.split("\n")
        val counts = HashMap<String, Int>()
        for (line in lines) {
            val t = line.trim()
            if (t.length in 2..40) {
                counts[t] = (counts[t] ?: 0) + 1
            }
        }
        // Linia powtarzająca się >= 3 razy to prawdopodobnie nagłówek/stopka.
        val repeated = counts.filter { it.value >= 3 }.keys
        if (repeated.isEmpty()) return text
        return lines.filter { it.trim() !in repeated }.joinToString("\n")
    }
}
