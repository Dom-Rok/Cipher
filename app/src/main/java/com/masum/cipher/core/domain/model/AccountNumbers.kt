package com.masum.cipher.core.domain.model

/**
 * An account's "last digits" field can hold several numbers separated by commas, e.g.
 * "3677, 001" for a bank account whose card (…3677) and IBAN (…001) appear in different
 * notifications.
 */
object AccountNumbers {

    private val SEPARATOR = Regex("[^\\d]+")

    fun parse(stored: String?): List<String> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(SEPARATOR).filter { it.isNotEmpty() }.map { it.takeLast(4) }.distinct()
    }

    /** Cleans user input for storage: "3677,001 " -> "3677, 001", blank -> null. */
    fun normalize(input: String): String? {
        return parse(input).joinToString(", ").ifBlank { null }
    }

    /** First number, for places that show a single "•••• 1234". */
    fun primary(stored: String?): String? = parse(stored).firstOrNull()

    fun matches(stored: String?, extractedDigits: String): Boolean {
        return parse(stored).any { number ->
            number == extractedDigits ||
                (number.length >= 3 && extractedDigits.endsWith(number)) ||
                (extractedDigits.length >= 3 && number.endsWith(extractedDigits))
        }
    }
}
