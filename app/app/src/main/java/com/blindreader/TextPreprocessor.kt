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
