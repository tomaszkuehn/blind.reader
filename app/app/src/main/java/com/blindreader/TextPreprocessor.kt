package com.blindreader

/** Oczyszcza tekst: usuwa nagłówki, stopki, numery stron i zbędne białe znaki. */
object TextPreprocessor {

    fun process(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace("\r", "\n")

        // Usuń numery stron w osobnych liniach (np. "12", " 12 ", "Strona 12")
        text = text.replace(Regex("(?m)^\\s*\\d{1,4}\\s*$"), "")
        text = text.replace(Regex("(?m)^\\s*(strona|page)\\s+\\d{1,4}\\s*$", RegexOption.IGNORE_CASE), "")

        // Usuń odnośniki do przypisów (superscript) zaraz po słowie lub po
        // zamykającym nawiasie/cudzysłowie, np. "słowo1", "(1939–1945)2",
        // "słowo*", "tekst”3" — to nie część słowa, tylko odnośnik.
        text = text.replace(Regex("(?<=[\\p{L})\\]\u201D\u2019\u00BB\"])\\d{1,2}(?=\\s|\\p{P}|$)"), "")
        text = text.replace(Regex("(?<=\\p{L})\\[\\d{1,3}\\](?=\\s|\\p{P}|$)"), "")
        text = text.replace(Regex("(?<=[\\p{L})\\]\u201D\u2019\u00BB\"])[*†‡§¶](?=\\s|\\p{P}|$)"), "")

        // Usuń znaki superscript Unicode (¹²³⁴⁵⁶⁷⁸⁹⁰) oraz znaki
        // indeksu dolnego — to odnośniki do przypisów, nie czyta się ich.
        text = text.replace(Regex("[\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079]"), "")
        text = text.replace(Regex("[\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089]"), "")

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

        // Próg wystąpień: linia powtarzająca się na górze/dole ~4% stron to
        // nagłówek/stopka. Nagłówki rozdziałów obejmują tylko strony swojego
        // rozdziału (np. ~5–15 stron z 221), więc próg musi być niski.
        val threshold = (pageTexts.size * 0.04).toInt().coerceAtLeast(3)
        for ((line, count) in headerCount) if (count >= threshold) headerLines.add(line)
        for ((line, count) in footerCount) if (count >= threshold) footerLines.add(line)

        return pageTexts.map { pageText ->
            val lines = pageText.split("\n")
            val filtered = lines.filter { line ->
                val t = line.trim()
                t.isNotEmpty() && t !in headerLines && t !in footerLines
            }
            process(removeAnnotations(filtered.joinToString("\n")))
        }
    }

    /**
     * Usuwa adnotacje na dole strony:
     * 1) od linii separatora (np. "────", "-----", "___") do końca strony,
     * 2) blok przypisów: od pierwszej linii przypisu (numer + 2+ spacje + tekst)
     *    do końca strony — nawet bez separatora. Normalne listy (np. "2. tekst")
     *    są pomijane, bo mają kropkę i pojedynczą spację.
     */
    private fun removeAnnotations(text: String): String {
        var lines = text.split("\n")

        val separatorRegex = Regex("^\\s*[-_─=*·]{3,}\\s*$")
        val sepIdx = lines.indexOfFirst { separatorRegex.matches(it) }
        if (sepIdx != -1) {
            lines = lines.take(sepIdx)
        }

        // Pierwsza linia przypisu: numer superscript + 2+ spacje + tekst.
        // Odcinamy od niej wszystko do końca strony.
        val footnoteStart = Regex("^\\s*\\d{1,3}\\s{2,}\\S")
        val fnIdx = lines.indexOfFirst { footnoteStart.containsMatchIn(it) }
        if (fnIdx != -1) {
            lines = lines.take(fnIdx)
        }

        return lines.joinToString("\n")
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
