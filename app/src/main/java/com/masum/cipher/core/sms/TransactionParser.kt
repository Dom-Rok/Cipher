package com.masum.cipher.core.sms

import com.masum.cipher.core.domain.model.ParsedTransaction
import com.masum.cipher.core.sms.config.TransactionPatterns
import com.masum.cipher.core.sms.region.GlobalFallbackRules
import com.masum.cipher.core.sms.region.RegionParserRules
import com.masum.cipher.core.sms.region.RegionRuleProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionParser @Inject constructor() {

    /**
     * @param header text prepended to the message that is not part of the alert itself, e.g. a
     * notification title such as "Google Pay" or "Tatra banka". The merchant is looked for in the
     * rest of the message first so the app or bank name does not become the merchant.
     */
    fun parse(message: String, preferredCurrency: String? = null, header: String? = null): ParsedTransaction? {
        val cleanMessage = message.replace(MULTI_SPACE_REGEX, " ").trim()
        val cleanHeader = header?.replace(MULTI_SPACE_REGEX, " ")?.trim().orEmpty()
        val body = if (cleanHeader.isNotEmpty() && cleanMessage.length > cleanHeader.length && cleanMessage.startsWith(cleanHeader)) {
            cleanMessage.substring(cleanHeader.length).trim()
        } else {
            cleanMessage
        }

        val ruleChain = RegionRuleProvider.getAllRules(preferredCurrency ?: "INR")

        for (rules in ruleChain) {
            val parsed = tryParseWithRules(cleanMessage, body, rules, preferredCurrency)
            if (parsed != null) return parsed
        }

        return null
    }

    private fun tryParseWithRules(
        message: String,
        body: String,
        rules: RegionParserRules,
        preferredCurrency: String?
    ): ParsedTransaction? {
        if (hasExclusionKeywords(message, rules)) return null
        if (!hasTransactionIntent(message, rules)) return null
        if (!hasTransactionEvidence(message, rules)) return null

        val amount = extractAmount(message, rules) ?: return null

        var merchant = extractMerchant(body, rules)
        if (merchant == null && body != message) merchant = extractMerchant(message, rules)

        val isDebit = TransactionPatterns.DEBIT_KEYWORDS.any { message.contains(it, ignoreCase = true) }
        val isCredit = TransactionPatterns.CREDIT_KEYWORDS.any { message.contains(it, ignoreCase = true) }
        val isIncome = isCredit && !isDebit
        val accountLast4 = extractAccountLast4(message)

        return ParsedTransaction(
            amount = amount,
            merchant = sanitizeMerchant(merchant ?: "Miscellaneous"),
            currency = if (rules == GlobalFallbackRules) {
                resolveFallbackCurrency(message, preferredCurrency) ?: rules.defaultCurrency
            } else {
                rules.defaultCurrency
            },
            isIncome = isIncome,
            accountLast4 = accountLast4
        )
    }

    // Global rules have no currency of their own: use the symbol in the message, preferring the
    // user's currency when the symbol is shared (e.g. ¥ for JPY/CNY), else the user's currency.
    private fun resolveFallbackCurrency(message: String, preferredCurrency: String?): String? {
        val preferred = preferredCurrency?.uppercase()?.takeIf { it.isNotBlank() }
        val symbol = message.firstOrNull { it in SYMBOL_CURRENCIES } ?: return preferred
        val candidates = SYMBOL_CURRENCIES.getValue(symbol)
        return if (preferred != null && preferred in candidates) preferred else candidates.first()
    }

    private fun extractMerchant(text: String, rules: RegionParserRules): String? {
        return extractP2PSender(text)
            ?: findBrandInText(text, rules)
            ?: extractMerchantStructural(text, rules)
    }

    // Keywords match whole words (plus simple inflections), so "won" does not reject
    // "Wonderla" and "data" does not reject "DATART".
    private fun hasExclusionKeywords(message: String, rules: RegionParserRules): Boolean {
        val regex = exclusionRegexCache.getOrPut(rules) {
            val alternatives = rules.exclusionKeywords.joinToString("|") { Regex.escape(it) }
            Regex("(?<!\\p{L})(?:$alternatives)(?:s|es|d|ed|ing|er|ers)?(?!\\p{L})", RegexOption.IGNORE_CASE)
        }
        return regex.containsMatchIn(message)
    }

    private fun hasTransactionIntent(message: String, rules: RegionParserRules): Boolean {
        val lower = message.lowercase()
        return rules.intentKeywords.any { lower.contains(it) }
    }

    private fun hasTransactionEvidence(message: String, rules: RegionParserRules): Boolean {
        return rules.evidencePatterns.any { pattern ->
            pattern.matcher(message).find()
        }
    }

    private fun extractAmount(message: String, rules: RegionParserRules): Double? {
        for (pattern in rules.amountPatterns) {
            val matcher = pattern.matcher(message)
            while (matcher.find()) {
                val match = matcher.group(1) ?: matcher.group(0)
                if (isPartOfAccountNumber(message, matcher.start())) continue

                val numeric = normalizeAmount(match)
                val value = numeric.toDoubleOrNull() ?: continue

                if (value <= 0) continue
                if (value > 1_000_000 && !match.contains(".")) continue

                return value
            }
        }
        return null
    }

    private fun normalizeAmount(raw: String): String {
        val trimmed = raw.trim().trimEnd('.', ',')
        // European decimal comma: "23,70" or "1.234,56"
        if (DECIMAL_COMMA_REGEX.matches(trimmed)) {
            return trimmed.replace(".", "").replace(",", ".")
        }
        return trimmed.replace(",", "").replace(NUMERIC_CLEANUP, "")
    }

    private fun isPartOfAccountNumber(message: String, matchStart: Int): Boolean {
        val matcher = TransactionPatterns.ACCOUNT_EXCLUSION_PATTERN.matcher(message)
        while (matcher.find()) {
            if (matchStart >= matcher.start() && matchStart < matcher.end()) return true
        }
        return false
    }

    companion object {
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val DECIMAL_COMMA_REGEX = Regex("^\\d{1,3}(?:\\.\\d{3})*,\\d{2}$|^\\d+,\\d{2}$")
        private val exclusionRegexCache = ConcurrentHashMap<RegionParserRules, Regex>()
        private val SYMBOL_CURRENCIES = mapOf(
            '$' to listOf("USD", "CAD", "AUD", "SGD", "NZD", "HKD", "MXN"),
            '€' to listOf("EUR"),
            '£' to listOf("GBP"),
            '¥' to listOf("JPY", "CNY"),
            '₹' to listOf("INR"),
            '₩' to listOf("KRW"),
            '₱' to listOf("PHP"),
            '₫' to listOf("VND"),
            '฿' to listOf("THB")
        )
        private val MERCHANT_PREFIX_CLEANUP = Regex("^(?:to|from|payment\\s+to|transfer\\s+to)\\s+", RegexOption.IGNORE_CASE)
        private val MERCHANT_TRAILING_CLEANUP = Regex("(?i)\\b(?:using|via|on|ref|vpa|upi|card|with|rrn|txn|id|auth|deposited|credited|in|into|for|towards|bank|account|a/c)\\b.*")
        private val NUMERIC_CLEANUP = Regex("[^\\d.]")
        private val P2P_SENDER_PATTERNS = listOf(
            java.util.regex.Pattern.compile("(?i)^([A-Za-z][A-Za-z0-9\\s&.]{1,40}?)\\s+paid\\s+you"),
            java.util.regex.Pattern.compile("(?i)^([A-Za-z][A-Za-z0-9\\s&.]{1,40}?)\\s+sent\\s+you"),
            java.util.regex.Pattern.compile("(?i)^([A-Za-z][A-Za-z0-9\\s&.]{1,40}?)\\s+transferred\\s+you"),
            java.util.regex.Pattern.compile("(?i)received\\s+(?:(?:rs\\.?|inr|₹)?\\s*[\\d,.]+\\s+)?from\\s+([A-Za-z][A-Za-z0-9\\s&.]{1,40}?)(?=\\s+deposited|\\s+credited|\\s+received|\\s+into|\\s+in\\s+your|\\s+on|\\s+using|\\s+via|\\s+ref|\\s+to|\\s+for|\\s+towards|\\s+a/c|\\s+acc|\\s+account|\\s+bank|\\.|$)")
        )
    }

    private fun extractP2PSender(message: String): String? {
        for (pattern in P2P_SENDER_PATTERNS) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                val raw = matcher.group(1)?.trim() ?: continue
                if (raw.isNotBlank()) {
                    val cleaned = raw.replace(MERCHANT_PREFIX_CLEANUP, "").trim()
                    if (cleaned.isNotBlank() && !TransactionPatterns.MERCHANT_FALSE_POSITIVE_PREFIXES.any { cleaned.lowercase().startsWith(it) }) {
                        return cleaned
                    }
                }
            }
        }
        return null
    }

    private fun findBrandInText(message: String, rules: RegionParserRules): String? {
        val upper = message.uppercase()
        return rules.brandDictionary
            .sortedByDescending { it.length }
            .find { brand ->
                upper.contains(Regex("\\b${Regex.escape(brand)}\\b"))
            }
    }

    private fun extractMerchantStructural(message: String, rules: RegionParserRules): String? {
        for (pattern in rules.structuralMerchantPatterns) {
            val matcher = pattern.matcher(message)
            while (matcher.find()) {
                val raw = matcher.group(1)?.trim() ?: continue
                if (raw.isBlank()) continue

                val lower = raw.lowercase()
                if (TransactionPatterns.MERCHANT_FALSE_POSITIVE_PREFIXES.any { lower.startsWith(it) }) continue

                val cleaned = raw.replace(MERCHANT_PREFIX_CLEANUP, "").trim()

                if (cleaned.isNotBlank()) return cleaned
            }
        }
        return null
    }

    private fun extractAccountLast4(message: String): String? {
        val pattern = java.util.regex.Pattern.compile("(?i)(?:a/c|acct|account|card|ending|ending with|ending in|xx|x{2,}|[*]+)\\s*[:#.-]?\\s*[*xX]*(\\d{3,4})\\b")
        val matcher = pattern.matcher(message)
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }
        return null
    }

    private fun sanitizeMerchant(merchant: String): String {
        return merchant
            .replace(MERCHANT_TRAILING_CLEANUP, "")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" ")
            .uppercase()
    }
}
