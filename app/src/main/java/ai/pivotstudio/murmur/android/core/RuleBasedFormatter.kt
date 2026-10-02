package ai.pivotstudio.murmur.android.core

/**
 * Lightweight rule-based cleanup pass between raw ASR output and text
 * injection (Phase 2) — ports the macOS app's `RuleBasedFormatter` role
 * (filler stripping, punctuation, capitalization) rather than its exact
 * Swift implementation, which isn't available in this environment; the
 * macOS README documents the role, these are a from-scratch Kotlin rules
 * doing the same job.
 *
 * Deliberately conservative: strips a short, well-known list of filler
 * words/phrases (only when they stand alone as whole words, not inside
 * other words), collapses whitespace, capitalizes the first letter, and
 * adds trailing punctuation if the model didn't produce any. Does NOT do
 * aggressive rewriting — ASR mistakes should surface as visibly-wrong
 * text, not get silently "corrected" into something the user didn't say.
 */
object RuleBasedFormatter {

    // Longest phrases first, so "you know what i mean" doesn't leave stray
    // words behind by matching "you know" and "i mean" separately mid-strip.
    private val FILLER_PHRASES = listOf(
        "you know what i mean",
        "if that makes sense",
        "you know",
        "i mean",
        "kind of",
        "sort of",
    )

    private val FILLER_WORDS = listOf("um", "umm", "uh", "uhh", "er", "erm")

    fun format(raw: String): String {
        if (raw.isBlank()) return raw

        var text = raw.trim()

        for (phrase in FILLER_PHRASES) {
            text = Regex("(?i)\\b${Regex.escape(phrase)}\\b[,]?\\s*").replace(text, " ")
        }
        for (word in FILLER_WORDS) {
            text = Regex("(?i)\\b${Regex.escape(word)}\\b[,]?\\s*").replace(text, " ")
        }

        // Collapse whitespace left behind by the stripping above.
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return text

        // Convert spoken numbers to digits ("twenty five" -> "25"), e.g.
        // for typing phone numbers/addresses into Contacts via dictation.
        text = NumberWordConverter.convert(text)

        // Capitalize first letter.
        text = text[0].uppercaseChar() + text.substring(1)

        // Add trailing punctuation only if the model produced none at all —
        // never override a question/exclamation mark the model already chose.
        if (text.last().isLetterOrDigit()) {
            text += "."
        }

        return text
    }
}
