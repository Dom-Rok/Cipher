package com.masum.cipher.core.domain.usecase

import com.masum.cipher.core.data.local.dao.AccountDao
import com.masum.cipher.core.data.local.dao.CategoryRuleDao
import com.masum.cipher.core.data.local.dao.MerchantAliasDao
import com.masum.cipher.core.data.local.dao.TransactionDao
import com.masum.cipher.core.data.local.entity.AccountEntity
import com.masum.cipher.core.data.local.entity.TransactionEntity
import com.masum.cipher.core.data.local.pref.AppTheme
import com.masum.cipher.core.data.local.pref.UserSettings
import com.masum.cipher.core.domain.CategorizerEngine
import com.masum.cipher.core.notifications.NotificationTextAssembler
import com.masum.cipher.core.sms.TransactionParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Moving money into a savings account produces two notifications: money out of the current
 * account and money into the savings account. Both must end up as TRANSFER on the right account,
 * so the move is not counted as spending or income.
 *
 * Notifications go through the same path as TransactionNotificationService:
 * NotificationTextAssembler -> TransactionParser -> ProcessIncomingTransactionUseCase.
 */
class SavingsTransferNotificationTest {

    private val accounts = mutableListOf<AccountEntity>()
    private val saved = mutableListOf<TransactionEntity>()
    private val fromNotifications get() = saved.filter { it.note != ProcessIncomingTransactionUseCase.AUTO_ADDED_SIDE_NOTE }
    private val autoAdded get() = saved.filter { it.note == ProcessIncomingTransactionUseCase.AUTO_ADDED_SIDE_NOTE }
    private val parser = TransactionParser()

    private fun balanceOf(account: AccountEntity) =
        saved.filter { it.accountId == account.id }.sumOf { if (it.isIncome) it.amount else -it.amount }
    private lateinit var useCase: ProcessIncomingTransactionUseCase

    // One bank account, two numbers: card …3677 (card payments) and IBAN …001 (transfers)
    private val current = AccountEntity(id = 1, name = "Bežný účet", type = "BANK", accountNumberLast4 = "3677, 001", isDefault = true)
    private val primaSavings = AccountEntity(id = 2, name = "Odkladací účet", type = "SAVINGS", accountNumberLast4 = "021")
    private val revolut = AccountEntity(id = 3, name = "Revolut", type = "WALLET")

    @Before
    fun setup() {
        accounts += listOf(current, primaSavings, revolut)
        useCase = ProcessIncomingTransactionUseCase(
            transactionDao(), merchantAliasDao(), categoryRuleDao(), accountDao(),
            CategorizerEngine(), null, null, null
        ).apply {
            onSyncWidget = {}
            onGetSettings = {
                UserSettings(
                    theme = AppTheme.SYSTEM,
                    isBiometricEnabled = false,
                    isPrivacyModeEnabled = false,
                    isHapticsEnabled = true,
                    currency = "EUR",
                    currencyCode = "EUR",
                    currencySymbol = "€",
                    appLanguage = "sk",
                    autoLockTimeout = 0L,
                    lastStopTime = 0L,
                    monthlyBudget = 0.0,
                    notifyAllTransactions = false,
                    isPro = true
                )
            }
        }
    }

    /** Mirrors TransactionNotificationService.onNotificationPosted. */
    private suspend fun postNotification(appLabel: String, title: String, text: String, timestamp: Long) {
        val fullMessage = NotificationTextAssembler.assemble(title = title, text = text)
        val parsed = parser.parse(fullMessage, "EUR", header = title)
        assertNotNull("[$appLabel] notification was not parsed: $fullMessage", parsed)
        useCase(
            TransactionEntity(
                merchant = parsed!!.merchant,
                amount = parsed.amount,
                currency = parsed.currency,
                category = "",
                isIncome = parsed.isIncome,
                rawSms = "[$appLabel] $fullMessage",
                timestamp = timestamp
            )
        )
    }

    // Real Prima banka notification (Wallet app) for moving money from the savings account
    // ("Odkladací účet", SK*…*021) to the main account (SK*…*001).
    private val savingsToMainTitle = "Pripísanie sumy: 7,00 EUR"
    private val savingsToMainText = "Na účet SK*5600*18841*001 bola pripísaná suma 7,00 EUR, " +
        "Prevod z Odkladacieho účtu SK*5600*18841*021, DISPO: 26,76 EUR (26,76 EUR), dňa: 01.10.2026 09:35:04"

    @Test
    fun `money from savings to main account in the same bank is an incoming transfer`() = runBlocking {
        postNotification("Wallet", savingsToMainTitle, savingsToMainText, timestamp = 1_000_000L)

        val moneyIn = fromNotifications.single()
        assertEquals(7.0, moneyIn.amount, 0.001)
        assertTrue(moneyIn.isIncome)
        assertEquals(current.id, moneyIn.accountId)
        assertEquals("TRANSFER", moneyIn.category)
        assertEquals("Odkladací účet", moneyIn.merchant)

        // Only the main account was notified, so the savings side is added automatically
        val moneyOutOfSavings = autoAdded.single()
        assertEquals(7.0, moneyOutOfSavings.amount, 0.001)
        assertFalse(moneyOutOfSavings.isIncome)
        assertEquals(primaSavings.id, moneyOutOfSavings.accountId)
        assertEquals("TRANSFER", moneyOutOfSavings.category)
        assertEquals("Bežný účet", moneyOutOfSavings.merchant)
        assertEquals(7.0, balanceOf(current), 0.001)
        assertEquals(-7.0, balanceOf(primaSavings), 0.001)
    }

    @Test
    fun `a later notification for the savings side replaces the auto-added entry`() = runBlocking {
        postNotification("Wallet", savingsToMainTitle, savingsToMainText, timestamp = 1_000_000L)
        // In case the bank starts notifying the savings account as well
        postNotification(
            "Wallet",
            "Odpísanie sumy: 7,00 EUR",
            "Z účtu SK*5600*18841*021 bola odpísaná suma 7,00 EUR, Prevod na účet SK*5600*18841*001, " +
                "DISPO: 93,24 EUR (93,24 EUR), dňa: 01.10.2026 09:35:04",
            timestamp = 1_003_000L
        )

        assertEquals(2, saved.size)
        assertTrue(autoAdded.isEmpty())
        val savingsSide = saved.single { it.accountId == primaSavings.id }
        assertFalse(savingsSide.isIncome)
        assertEquals("TRANSFER", savingsSide.category)
        assertNotNull(savingsSide.rawSms)
        assertEquals(7.0, balanceOf(current), 0.001)
        assertEquals(-7.0, balanceOf(primaSavings), 0.001)
    }

    @Test
    fun `when the savings side is notified first nothing is added twice`() = runBlocking {
        postNotification(
            "Wallet",
            "Odpísanie sumy: 7,00 EUR",
            "Z účtu SK*5600*18841*021 bola odpísaná suma 7,00 EUR, Prevod na účet SK*5600*18841*001, " +
                "DISPO: 93,24 EUR (93,24 EUR), dňa: 01.10.2026 09:35:04",
            timestamp = 1_000_000L
        )
        postNotification("Wallet", savingsToMainTitle, savingsToMainText, timestamp = 1_002_000L)

        assertEquals(2, saved.size)
        assertTrue(autoAdded.isEmpty())
        saved.forEach { assertEquals("TRANSFER", it.category) }
        assertEquals(7.0, balanceOf(current), 0.001)
        assertEquals(-7.0, balanceOf(primaSavings), 0.001)
    }

    @Test
    fun `card payment and savings transfer both land on the account holding both numbers`() = runBlocking {
        // Default goes to savings so a fallback cannot make this pass by accident
        accounts.replaceAll { it.copy(isDefault = it.id == primaSavings.id) }

        postNotification(
            "Wallet",
            "Platba kartou: 12,19 EUR",
            "Platba kartou *3677 v sume 12,19 EUR, Dr.Max 529, PO Novum,Presov,SK, DISPO: 20,26 EUR (20,26 EUR), dňa: 30.09.2026 12:19:53",
            timestamp = 500_000L
        )
        postNotification("Wallet", savingsToMainTitle, savingsToMainText, timestamp = 1_000_000L)

        assertEquals(2, fromNotifications.size)
        val (cardPayment, moneyIn) = fromNotifications
        assertEquals(current.id, cardPayment.accountId)
        assertNotEquals("TRANSFER", cardPayment.category)
        assertEquals(current.id, moneyIn.accountId)
        assertEquals("TRANSFER", moneyIn.category)
    }

    @Test
    fun `money from savings is a transfer even when account numbers are not set up`() = runBlocking {
        accounts.replaceAll { it.copy(accountNumberLast4 = null) }

        postNotification("Wallet", savingsToMainTitle, savingsToMainText, timestamp = 1_000_000L)

        val moneyIn = fromNotifications.single()
        assertEquals(7.0, moneyIn.amount, 0.001)
        assertTrue(moneyIn.isIncome)
        assertEquals("TRANSFER", moneyIn.category)

        // "Odkladací účet" in the text points at the user's only savings-type account
        assertEquals(primaSavings.id, autoAdded.single().accountId)
        assertFalse(autoAdded.single().isIncome)
    }

    @Test
    fun `money from main to savings account in the same bank is an outgoing transfer`() = runBlocking {
        // Mirror of the real notification above for the opposite direction
        postNotification(
            "Wallet",
            "Odpísanie sumy: 100,00 EUR",
            "Z účtu SK*5600*18841*001 bola odpísaná suma 100,00 EUR, Prevod na Odkladací účet SK*5600*18841*021, " +
                "DISPO: 26,76 EUR (26,76 EUR), dňa: 01.10.2026 08:00:00",
            timestamp = 1_000_000L
        )

        val moneyOut = fromNotifications.single()
        assertEquals(100.0, moneyOut.amount, 0.001)
        assertFalse(moneyOut.isIncome)
        assertEquals(current.id, moneyOut.accountId)
        assertEquals("TRANSFER", moneyOut.category)
        assertEquals("Odkladací účet", moneyOut.merchant)

        val moneyIntoSavings = autoAdded.single()
        assertTrue(moneyIntoSavings.isIncome)
        assertEquals(primaSavings.id, moneyIntoSavings.accountId)
        assertEquals(-100.0, balanceOf(current), 0.001)
        assertEquals(100.0, balanceOf(primaSavings), 0.001)
    }

    @Test
    fun `savings transfer from the bank app to another app is paired into a transfer`() = runBlocking {
        // The bank shows a foreign IBAN (the user's own Revolut account) that is not set up in
        // the app, so the money-out side only becomes a transfer once the Revolut side arrives.
        postNotification(
            "Wallet",
            "Odpísanie sumy: 200,00 EUR",
            "Z účtu SK*5600*18841*001 bola odpísaná suma 200,00 EUR, Prevod na účet LT*3250*0123*4567, " +
                "DISPO: 20,00 EUR (20,00 EUR), dňa: 01.10.2026 09:00:00",
            timestamp = 2_000_000L
        )
        assertNotEquals("TRANSFER", fromNotifications.single().category)

        postNotification(
            "Revolut",
            "Money added",
            "€200.00 received from Jan Novak",
            timestamp = 2_045_000L
        )

        assertEquals(2, fromNotifications.size)
        val (moneyOut, moneyIn) = fromNotifications

        assertEquals(200.0, moneyOut.amount, 0.001)
        assertFalse(moneyOut.isIncome)
        assertEquals(current.id, moneyOut.accountId)
        assertEquals("TRANSFER", moneyOut.category)

        assertEquals(200.0, moneyIn.amount, 0.001)
        assertTrue(moneyIn.isIncome)
        assertEquals(revolut.id, moneyIn.accountId)
        assertTrue(autoAdded.isEmpty())
        assertEquals("TRANSFER", moneyIn.category)
    }

    @Test
    fun `card payment and unrelated income of the same amount are not paired`() = runBlocking {
        postNotification(
            "Wallet",
            "Platba kartou: 50,00 EUR",
            "Platba kartou *3677 v sume 50,00 EUR, Kaufland 1234,Presov,SK, DISPO: 70,00 EUR, dňa: 01.10.2026 10:00:00",
            timestamp = 3_000_000L
        )
        postNotification(
            "Revolut",
            "Money added",
            "€50.00 received from Peter Horvath",
            timestamp = 3_060_000L
        )

        assertEquals(2, fromNotifications.size)
        fromNotifications.forEach { assertNotEquals("TRANSFER", it.category) }
        assertTrue(autoAdded.isEmpty())
    }

    private fun transactionDao(): TransactionDao = Proxy.newProxyInstance(
        TransactionDao::class.java.classLoader,
        arrayOf(TransactionDao::class.java)
    ) { _, method, args ->
        when (method.name) {
            "findDuplicate" -> saved.firstOrNull {
                it.amount == args[0] as Double &&
                    it.isIncome == args[1] as Boolean &&
                    it.merchant.equals(args[2] as String, ignoreCase = true) &&
                    it.timestamp in (args[3] as Long)..(args[4] as Long)
            }
            "findByAmountBetween" -> saved.filter {
                it.amount == args[0] as Double &&
                    it.isIncome == args[1] as Boolean &&
                    it.timestamp in (args[2] as Long)..(args[3] as Long)
            }
            "insertTransaction" -> {
                // OnConflictStrategy.REPLACE: an existing id is overwritten
                val tx = args[0] as TransactionEntity
                val existing = saved.indexOfFirst { tx.id != 0L && it.id == tx.id }
                if (existing >= 0) {
                    saved[existing] = tx
                    tx.id
                } else {
                    val id = (saved.maxOfOrNull { it.id } ?: 0L) + 1
                    saved += tx.copy(id = id)
                    id
                }
            }
            "updateCategory" -> {
                val index = saved.indexOfFirst { it.id == args[0] as Long }
                saved[index] = saved[index].copy(category = args[1] as String)
                Unit
            }
            "sumExpensesSince", "sumIncomeSince" -> 0.0
            "getUncategorizedCount" -> 0
            else -> null
        }
    } as TransactionDao

    private fun merchantAliasDao(): MerchantAliasDao = Proxy.newProxyInstance(
        MerchantAliasDao::class.java.classLoader,
        arrayOf(MerchantAliasDao::class.java)
    ) { _, method, _ -> if (method.name == "insertAlias") Unit else null } as MerchantAliasDao

    private fun categoryRuleDao(): CategoryRuleDao = Proxy.newProxyInstance(
        CategoryRuleDao::class.java.classLoader,
        arrayOf(CategoryRuleDao::class.java)
    ) { _, _, _ -> null } as CategoryRuleDao

    private fun accountDao(): AccountDao = Proxy.newProxyInstance(
        AccountDao::class.java.classLoader,
        arrayOf(AccountDao::class.java)
    ) { _, method, _ -> if (method.name == "getAllAccounts") accounts.toList() else null } as AccountDao
}
