package helium314.keyboard.contextlm

import java.util.Locale

/**
 * Write-time capitalization hygiene guard for UserHistoryDictionary / DictionaryFacilitatorImpl.
 * Prevents mid-sentence capitalization pollution (e.g. "The", "Because", "And") from being
 * persisted into the binary n-gram history cache when the lowercase form is already a valid
 * standard dictionary word.
 */
object DictionaryHygieneGuard {

    /**
     * Returns the normalized word to persist into UserHistoryDictionary.
     *
     * @param word The candidate word being recorded.
     * @param isValidLowercaseInMainDict Callback querying DictionaryFacilitator/main dictionary for `lower`.
     * @return The normalized word (lowercased if `word` had mixed/title case and `lower` is a valid
     *         standard dictionary word, or `word` unchanged if it is an all-caps acronym or proper noun).
     */
    @JvmStatic
    fun normalizeForHistory(
        word: String,
        isValidLowercaseInMainDict: (String) -> Boolean
    ): String {
        if (word.isEmpty()) return word

        // Preserve all-caps acronyms (e.g., API, DNS, URL, USB)
        val letters = word.filter { it.isLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) {
            return word
        }

        val lower = word.lowercase(Locale.ROOT)
        if (word == lower) {
            return word
        }

        // If the lowercase form is already a valid standard dictionary word, normalize to lowercase
        return if (isValidLowercaseInMainDict(lower)) {
            lower
        } else {
            word
        }
    }
}
