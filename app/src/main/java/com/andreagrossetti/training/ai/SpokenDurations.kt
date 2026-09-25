package com.andreagrossetti.training.ai

/**
 * Rewrites spoken Italian durations as plain seconds ("un minuto e mezzo" -> "90 secondi")
 * before the text reaches Nano, which is unreliable at this kind of arithmetic.
 */
object SpokenDurations {
    private val units = listOf(
        "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci",
        "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove",
    )
    private val tens = listOf("venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta")

    /** Italian number words 0-99, plus the articles "un"/"una" for 1. */
    private val words: Map<String, Int> = buildMap {
        units.forEachIndexed { i, w -> put(w, i) }
        put("un", 1); put("una", 1)
        tens.forEachIndexed { i, t ->
            val value = (i + 2) * 10
            put(t, value)
            for (u in 1..9) {
                // "venti" + "uno" -> "ventuno", "venti" + "otto" -> "ventotto"; "tre" is often written "tré".
                val stem = if (u == 1 || u == 8) t.dropLast(1) else t
                put(stem + units[u], value + u)
                if (u == 3) put(stem + "tré", value + u)
            }
        }
    }

    private val number = "(\\d+|" + words.keys.sortedByDescending { it.length }.joinToString("|") + ")"
    private val minute = "minut[oi]"
    private val second = "second[oi]"

    private fun value(token: String): Int = token.toIntOrNull() ?: words.getValue(token.lowercase())

    private class Rule(pattern: String, val seconds: (MatchResult) -> Int) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /** Applied in order: the longer forms first, so "un minuto e mezzo" is not read as "un minuto". */
    private val rules = listOf(
        Rule("\\b$number $minute e mezzo\\b") { value(it.groupValues[1]) * 60 + 30 },
        Rule("\\b$number $minute e un quarto\\b") { value(it.groupValues[1]) * 60 + 15 },
        Rule("\\b$number $minute e $number(?: $second)?\\b") { value(it.groupValues[1]) * 60 + value(it.groupValues[2]) },
        Rule("\\b$number e mezzo $minute\\b") { value(it.groupValues[1]) * 60 + 30 },
        Rule("\\bmezzo $minute\\b") { 30 },
        Rule("\\b(\\d+):([0-5]\\d) $minute\\b") { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() },
        Rule("\\b$number $minute\\b") { value(it.groupValues[1]) * 60 },
        Rule("\\b$number $second\\b") { value(it.groupValues[1]) },
    )

    fun normalize(text: String): String = rules.fold(text) { acc, rule ->
        rule.regex.replace(acc) { m -> "${rule.seconds(m)} secondi" }
    }
}
