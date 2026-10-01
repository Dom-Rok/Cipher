package com.masum.cipher.core.domain.usecase

import com.masum.cipher.core.data.local.dao.AccountDao
import com.masum.cipher.core.data.local.dao.CategoryRuleDao
import com.masum.cipher.core.data.local.dao.MerchantAliasDao
import com.masum.cipher.core.data.local.dao.TransactionDao
import com.masum.cipher.core.data.local.entity.AccountEntity
import com.masum.cipher.core.data.local.entity.MerchantAliasEntity
import com.masum.cipher.core.data.local.entity.TransactionEntity
import com.masum.cipher.core.data.local.pref.UserPreferences
import com.masum.cipher.core.domain.CategorizerEngine
import com.masum.cipher.core.domain.model.AccountNumbers
import com.masum.cipher.core.domain.model.TransactionCategory
import com.masum.cipher.core.notifications.LocalNotificationManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessIncomingTransactionUseCase @Inject constructor(
    private val transactionDao: TransactionDao,
    private val merchantAliasDao: MerchantAliasDao,
    private val categoryRuleDao: CategoryRuleDao,
    private val accountDao: AccountDao?,
    private val categorizerEngine: CategorizerEngine,
    private val localNotificationManager: LocalNotificationManager?,
    private val userPreferences: UserPreferences?,
    private val widgetSyncManager: WidgetSyncManager?
) {
    internal var onSyncWidget: (suspend () -> Unit)? = null
    internal var onGetSettings: (suspend () -> com.masum.cipher.core.data.local.pref.UserSettings)? = null
    internal var onNotifyNewTransaction: ((TransactionEntity) -> Unit)? = null
    internal var onNotifyUncategorized: ((Int) -> Unit)? = null
    internal var onNotifyBudgetAlert: ((isExceeded: Boolean, amount: Double, threshold: Int) -> Unit)? = null
    suspend operator fun invoke(transaction: TransactionEntity): TransactionEntity? {
        if (transaction.rawSms != null) {
            val timeWindow = 60_000L
            val startTime = transaction.timestamp - timeWindow
            val endTime = transaction.timestamp + timeWindow

            val duplicate = transactionDao.findDuplicate(transaction.amount, transaction.isIncome, transaction.merchant.trim(), startTime, endTime)
            if (duplicate != null) {
                return null
            }
        }

        val rawMerchant = transaction.merchant.trim()
        val alias = merchantAliasDao.getAliasForRawName(rawMerchant)
        val finalMerchant: String
        val finalCategory: String

        if (alias != null) {
            finalMerchant = alias.cleanName
            val savedCategory = categoryRuleDao.getCategoryForMerchant(finalMerchant)
            finalCategory = transaction.category.ifBlank {
                savedCategory ?: categorizerEngine.categorize(finalMerchant).name
            }
        } else {
            val cleanName = categorizerEngine.cleanMerchantName(transaction.merchant)
            val savedCategory = categoryRuleDao.getCategoryForMerchant(cleanName)
            val autoCategory = categorizerEngine.categorize(cleanName)

            if (cleanName != transaction.merchant) {
                merchantAliasDao.insertAlias(MerchantAliasEntity(rawMerchant, cleanName))
            }
            finalMerchant = cleanName
            finalCategory = transaction.category.ifBlank {
                savedCategory ?: autoCategory.name
            }
        }

        val start = monthStart()
        val previousSpent = transactionDao.sumExpensesSince(start)

        val settings = onGetSettings?.invoke() ?: userPreferences?.settingsFlow?.first()
        val isPro = settings?.isPro == true

        val resolvedAccountId = transaction.accountId ?: run {
            if (accountDao != null) {
                val accounts = accountDao.getAllAccounts()
                val activeAccounts = if (isPro || accounts.size <= 2) {
                    accounts
                } else {
                    val defaultAcc = accounts.firstOrNull { it.isDefault } ?: accounts.first()
                    val secondAcc = accounts.firstOrNull { it.id != defaultAcc.id }
                    listOfNotNull(defaultAcc, secondAcc)
                }
                resolveAccount(transaction.rawSms.orEmpty(), activeAccounts)
            } else {
                null
            }
        }

        val accounts = accountDao?.getAllAccounts() ?: emptyList()
        val rawMessage = transaction.rawSms.orEmpty()
        val otherOwnAccount = findOtherMentionedAccount(rawMessage, resolvedAccountId, accounts)
        val isInternalTransfer = detectInternalTransfer(rawMessage, finalMerchant, accounts) ||
            (hasTransferKeyword(rawMessage) && (otherOwnAccount != null || mentionsSavingsAccount(rawMessage)))
        val transferCounterpart = findTransferCounterpart(transaction, resolvedAccountId)
        val effectiveCategory = if (isInternalTransfer || transferCounterpart != null) TRANSFER_CATEGORY else finalCategory

        val newTx = transaction.copy(
            merchant = if (isInternalTransfer && otherOwnAccount != null) otherOwnAccount.name else finalMerchant,
            category = effectiveCategory,
            accountId = resolvedAccountId
        )
        val insertedId = transactionDao.insertTransaction(newTx)
        val savedTx = newTx.copy(id = insertedId)

        if (transferCounterpart != null && !transferCounterpart.category.equals(TRANSFER_CATEGORY, ignoreCase = true)) {
            transactionDao.updateCategory(transferCounterpart.id, TRANSFER_CATEGORY)
        }

        onSyncWidget?.invoke() ?: widgetSyncManager?.syncWidget()
        if (settings?.notifyAllTransactions == true) {
            onNotifyNewTransaction?.invoke(savedTx) ?: localNotificationManager?.showNewTransactionNotification(savedTx)
        }
        checkBudgetAlert(previousSpent)

        if (effectiveCategory == TransactionCategory.OTHERS.name) {
            val count = transactionDao.getUncategorizedCount()
            if (count > 0) {
                onNotifyUncategorized?.invoke(count) ?: localNotificationManager?.showUncategorizedReminderNotification(count)
            }
        }

        return savedTx
    }

    private suspend fun checkBudgetAlert(previousSpent: Double) {
        val settings = onGetSettings?.invoke() ?: userPreferences?.settingsFlow?.first()
        val baseBudget = settings?.monthlyBudget ?: 0.0
        if (baseBudget <= 0) return

        val start = monthStart()
        val totalIncome = if (settings?.isDynamicBudgetEnabled == true) transactionDao.sumIncomeSince(start) else 0.0
        val budget = baseBudget + totalIncome
        val newSpent = transactionDao.sumExpensesSince(start)

        if (budget in previousSpent..<newSpent) {
            onNotifyBudgetAlert?.invoke(true, newSpent - budget, 100) ?: localNotificationManager?.showBudgetAlertNotification(isExceeded = true, amount = newSpent - budget, threshold = 100)
        } else if ((budget * 0.9) in previousSpent..<newSpent) {
            onNotifyBudgetAlert?.invoke(false, budget - newSpent, 90) ?: localNotificationManager?.showBudgetAlertNotification(isExceeded = false, amount = budget - newSpent, threshold = 90)
        } else if ((budget * 0.5) in previousSpent..<newSpent) {
            onNotifyBudgetAlert?.invoke(false, budget - newSpent, 50) ?: localNotificationManager?.showBudgetAlertNotification(isExceeded = false, amount = budget - newSpent, threshold = 50)
        }
    }

    companion object {
        private const val TRANSFER_CATEGORY = "TRANSFER"
        private const val TRANSFER_PAIR_WINDOW_MS = 10 * 60_000L
        private val TRANSFER_KEYWORDS = listOf(
            "pripísanie", "prípísanie", "internal transfer", "account to account", "transfer between",
            "vklad", "prevod", "transferred", "between accounts"
        )

        // Slovak banks' own names for a savings account, e.g. Prima banka's "Odkladací účet"
        private val SAVINGS_ACCOUNT_PHRASES = listOf("odkladac", "sporiac", "savings account", "savings vault")

        // Masked IBAN such as "SK*5600*18841*001": the last group identifies the account
        private val MASKED_ACCOUNT_PATTERN = java.util.regex.Pattern.compile("(?i)\\b[A-Z]{2}\\d{0,2}(?:\\*\\d+)+\\*(\\d{3,4})\\b")
    }

    private fun mentionsSavingsAccount(rawMessage: String): Boolean {
        return SAVINGS_ACCOUNT_PHRASES.any { rawMessage.contains(it, ignoreCase = true) }
    }

    // Another of the user's accounts named in the message by its masked number, e.g. the savings
    // account in "Prevod z Odkladacieho účtu SK*5600*18841*021".
    private fun findOtherMentionedAccount(
        rawMessage: String,
        resolvedAccountId: Long?,
        accounts: List<AccountEntity>
    ): AccountEntity? {
        val matcher = MASKED_ACCOUNT_PATTERN.matcher(rawMessage)
        while (matcher.find()) {
            val digits = matcher.group(1) ?: continue
            val account = accounts.firstOrNull { acc -> AccountNumbers.matches(acc.accountNumberLast4, digits) }
            if (account != null && account.id != resolvedAccountId) return account
        }
        return null
    }

    private fun monthStart(): Long = com.masum.cipher.core.util.DateTimeUtils.currentMonthStart()

    private fun detectInternalTransfer(
        rawMessage: String,
        merchant: String,
        accounts: List<AccountEntity>
    ): Boolean {
        if (rawMessage.isBlank() || accounts.isEmpty()) return false
        if (!hasTransferKeyword(rawMessage)) return false

        val cleanMerchant = merchant.lowercase()
        return accounts.any { acc ->
            acc.name.trim().lowercase() == cleanMerchant ||
            cleanMerchant.contains(acc.name.trim().lowercase())
        }
    }

    private fun hasTransferKeyword(rawMessage: String?): Boolean {
        if (rawMessage.isNullOrBlank()) return false
        return TRANSFER_KEYWORDS.any { rawMessage.contains(it, ignoreCase = true) }
    }

    // A transfer between two of the user's accounts usually arrives as two notifications, often
    // from different apps: money out of one account and the same amount into another shortly
    // after. Pair them when either side reads like a transfer.
    private suspend fun findTransferCounterpart(transaction: TransactionEntity, accountId: Long?): TransactionEntity? {
        if (transaction.rawSms == null || accountId == null) return null
        val candidates = transactionDao.findByAmountBetween(
            transaction.amount,
            !transaction.isIncome,
            transaction.timestamp - TRANSFER_PAIR_WINDOW_MS,
            transaction.timestamp + TRANSFER_PAIR_WINDOW_MS
        )
        return candidates.firstOrNull { other ->
            other.accountId != null &&
                other.accountId != accountId &&
                other.currency.equals(transaction.currency, ignoreCase = true) &&
                (hasTransferKeyword(transaction.rawSms) || hasTransferKeyword(other.rawSms))
        }
    }

    private fun resolveAccount(
        rawMessage: String,
        accounts: List<AccountEntity>
    ): Long? {
        if (accounts.isEmpty()) return null

        val digitPatterns = listOf(
            MASKED_ACCOUNT_PATTERN,
            java.util.regex.Pattern.compile("(?i)(?:a/c|acct|account|card|ending|ending with|ending in|no\\.?|num|xx|x{2,}|[*]+|\\.{2,})\\s*[:#.-]?\\s*[*xX.]*(\\d{3,4})\\b"),
            java.util.regex.Pattern.compile("(?i)[*xX]{2,}(\\d{3,4})\\b"),
            java.util.regex.Pattern.compile("(?i)\\b(\\d{4})\\s*(?:is debited|was debited|is credited|was credited|used at|spent on)")
        )

        var extractedDigits: String? = null
        if (rawMessage.isNotBlank()) {
            for (pattern in digitPatterns) {
                val matcher = pattern.matcher(rawMessage)
                if (matcher.find()) {
                    val candidate = matcher.group(1)?.trim()
                    if (!candidate.isNullOrBlank()) {
                        extractedDigits = candidate
                        break
                    }
                }
            }
        }

        if (extractedDigits != null) {
            val matchedByDigits = accounts.firstOrNull { acc ->
                AccountNumbers.matches(acc.accountNumberLast4, extractedDigits)
            }
            if (matchedByDigits != null) {
                return matchedByDigits.id
            }
        }

        if (rawMessage.isNotBlank()) {
            val isCreditCardText = rawMessage.contains("credit card", ignoreCase = true) ||
                rawMessage.contains("cc ", ignoreCase = true) ||
                rawMessage.contains("card ending", ignoreCase = true)
            val isWalletText = rawMessage.contains("wallet", ignoreCase = true) ||
                rawMessage.contains("paytm", ignoreCase = true) ||
                rawMessage.contains("upi", ignoreCase = true)

            val matchedByName = accounts.filter { acc ->
                val cleanAccName = acc.name.trim().lowercase()
                if (cleanAccName.length < 2) false
                else {
                    val words = cleanAccName.split(" ").filter { it.length >= 3 }
                    val matchesWhole = rawMessage.contains(cleanAccName, ignoreCase = true)
                    val matchesWord = words.any { word ->
                        rawMessage.contains(Regex("(?i)\\b${Regex.escape(word)}\\b"))
                    }
                    matchesWhole || matchesWord
                }
            }.maxByOrNull { acc ->
                var score = acc.name.length
                if (isCreditCardText && acc.type == "CREDIT_CARD") score += 10
                if (isWalletText && acc.type == "WALLET") score += 10
                if (acc.isDefault) score += 1
                score
            }

            if (matchedByName != null) {
                return matchedByName.id
            }
        }

        val defaultAcc = accounts.firstOrNull { it.isDefault } ?: accounts.firstOrNull()
        return defaultAcc?.id
    }
}
