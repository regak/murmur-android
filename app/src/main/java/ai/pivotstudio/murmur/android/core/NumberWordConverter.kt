package ai.pivotstudio.murmur.android.core

/**
 * Converts spoken number words to digits ("twenty five" -> "25"),
 * requested directly: "for numbers, let's say in parts where I want to
 * write numbers in contacts... it doesn't write numbers, it writes in
 * words, but I want it to write in numbers." (e.g. typing a phone number
 * into Contacts via dictation should produce digits, not words).
 *
 * Scope deliberately kept to cardinal numbers people actually say while
 * dictating short strings like phone numbers, amounts, ages, addresses:
 * - Units/teens/tens ("five" -> "5", "seventeen" -> "17", "forty" -> "40")
 * - Compounds ("twenty five" -> "25", "ninety nine" -> "99")
 * - Hundreds/thousands ("nineteen eighty four" -> "1984", "two hundred"
 *   -> "200", "three thousand five hundred" -> "3500")
 * - "oh"/"zero" as a lone digit, common in spoken phone numbers
 *   ("five five oh" -> "5 5 0")
 *
 * Deliberately does NOT touch ordinals ("first", "second"), fractions, or
 * idiomatic number-ish phrases ("a couple", "a few") — those aren't the
 * digit-entry use case being fixed here, and guessing wrong there would
 * silently corrupt text the user didn't intend to be numeric.
 */
object NumberWordConverter {

    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val MAGNITUDES = mapOf("hundred" to 100, "thousand" to 1000)

    private val ALL_NUMBER_WORDS = UNITS.keys + TEENS.keys + TENS.keys + MAGNITUDES.keys + setOf("and")

    /** Replaces every run of consecutive number-words in [text] with digits. */
    fun convert(text: String): String {
        val tokens = text.split(Regex("(?<=\\s)|(?=\\s)")) // keep whitespace as tokens
        val result = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val word = tokens[i].trim().lowercase().trimEnd(',', '.', '!', '?')
            if (word.isNotEmpty() && word in ALL_NUMBER_WORDS) {
                // Greedily consume the longest run of number-words starting here.
                var j = i
                val run = mutableListOf<String>()
                while (j < tokens.size) {
                    val w = tokens[j].trim().lowercase().trimEnd(',', '.', '!', '?')
                    if (w.isNotEmpty() && w in ALL_NUMBER_WORDS) {
                        run.add(w)
                        j++
                    } else if (tokens[j].isBlank() && j + 1 < tokens.size) {
                        // Allow a single whitespace token to pass through
                        // inside the run without breaking it.
                        val next = tokens.getOrNull(j + 1)?.trim()?.lowercase()?.trimEnd(',', '.', '!', '?')
                        if (next != null && next in ALL_NUMBER_WORDS) {
                            j++
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                }
                val digits = wordsToDigits(run)
                if (digits != null) {
                    result.append(digits)
                } else {
                    // Couldn't parse this run confidently -- leave the
                    // original words untouched rather than guessing wrong.
                    for (k in i until j) result.append(tokens[k])
                }
                i = j
            } else {
                result.append(tokens[i])
                i++
            }
        }
        return result.toString()
    }

    /**
     * Parses a run of number-words into a digit string. Phone-number-style
     * digit sequences ("five five oh three") are kept space-separated
     * digits; a single cardinal-number phrase ("twenty five" -> "25",
     * "three thousand five hundred" -> "3500") is collapsed into one
     * number; year-style back-to-back two-digit groups with no
     * hundred/thousand connector ("nineteen eighty four" -> "1984",
     * "twenty twenty five" -> "2025") are concatenated digit-group-wise,
     * not summed — summing would wrongly produce 103 for "nineteen eighty
     * four" (19+80+4) instead of the intended year 1984. Returns null if
     * the run doesn't parse as a sane number.
     */
    private fun wordsToDigits(words: List<String>): String? {
        if (words.isEmpty()) return null

        // Heuristic: if every word is a bare unit (0-9) and there are 2+ of
        // them, treat this as digit-by-digit speech (phone numbers, PINs,
        // codes) rather than one big cardinal number -- "five five oh" is a
        // phone number fragment, not the number 550 collapsed together, and
        // "five oh" is far more likely spoken digits "5 0" than the sum 5.
        if (words.size >= 2 && words.all { it in UNITS }) {
            return words.joinToString(" ") { UNITS.getValue(it).toString() }
        }

        val hasMagnitude = words.any { it == "hundred" || it == "thousand" }
        if (hasMagnitude) {
            // Standard "chunk by thousand/hundred" additive number-word
            // parsing -- these words genuinely compose one large number
            // additively ("three thousand five hundred" = 3000 + 500).
            var total = 0L
            var current = 0L
            var sawAnything = false
            for (w in words) {
                when {
                    w == "and" -> continue
                    w in UNITS -> { current += UNITS.getValue(w); sawAnything = true }
                    w in TEENS -> { current += TEENS.getValue(w); sawAnything = true }
                    w in TENS -> { current += TENS.getValue(w); sawAnything = true }
                    w == "hundred" -> { current = (if (current == 0L) 1L else current) * 100; sawAnything = true }
                    w == "thousand" -> {
                        total += (if (current == 0L) 1L else current) * 1000
                        current = 0L
                        sawAnything = true
                    }
                    else -> return null
                }
            }
            if (!sawAnything) return null
            return (total + current).toString()
        }

        // No hundred/thousand connector: parse as a sequence of complete
        // two-digit-ish "chunks" (each TEEN is its own chunk; each TENS
        // optionally absorbs a following UNIT into its chunk). A single
        // chunk is a plain cardinal ("twenty five" -> 25). Multiple chunks
        // back-to-back are concatenated digit-group-wise, matching how
        // people actually say years/codes ("nineteen eighty four",
        // "twenty twenty five") rather than summed as one cardinal.
        val chunks = mutableListOf<Int>()
        var current = 0
        var hasContent = false
        for (w in words) {
            when {
                w == "and" -> continue
                w in TEENS -> {
                    if (hasContent) { chunks.add(current); current = 0; hasContent = false }
                    chunks.add(TEENS.getValue(w))
                }
                w in TENS -> {
                    if (hasContent) { chunks.add(current); current = 0 }
                    current = TENS.getValue(w)
                    hasContent = true
                }
                w in UNITS -> {
                    current += UNITS.getValue(w)
                    hasContent = true
                }
                else -> return null
            }
        }
        if (hasContent) chunks.add(current)
        if (chunks.isEmpty()) return null

        return if (chunks.size == 1) {
            chunks[0].toString()
        } else {
            chunks.joinToString("") { it.toString().padStart(2, '0') }
        }
    }
}
