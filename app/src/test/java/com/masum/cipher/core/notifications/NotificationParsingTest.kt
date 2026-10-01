package com.masum.cipher.core.notifications

import com.masum.cipher.core.domain.model.ParsedTransaction
import com.masum.cipher.core.sms.TransactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Feeds realistic app notifications through the same path as TransactionNotificationService:
 * notification fields -> NotificationTextAssembler -> TransactionParser.
 *
 * Unlike SMS, the notification title (usually the app or bank name) is prepended to the text,
 * so these tests cover how that extra text affects parsing.
 */
class NotificationParsingTest {

    private lateinit var parser: TransactionParser

    @Before
    fun setup() {
        parser = TransactionParser()
    }

    private fun parseNotification(
        currency: String,
        title: String,
        text: String,
        bigText: String = ""
    ): ParsedTransaction? {
        val message = NotificationTextAssembler.assemble(title = title, text = text, bigText = bigText)
        return parser.parse(message, currency, header = title)
    }

    private fun assertTransaction(
        result: ParsedTransaction?,
        amount: Double,
        merchant: String,
        currency: String,
        isIncome: Boolean
    ) {
        assertNotNull(result)
        assertEquals(amount, result!!.amount, 0.001)
        assertEquals(merchant, result.merchant)
        assertEquals(currency, result.currency)
        assertEquals(isIncome, result.isIncome)
    }

    // ─── INDIA (INR) ──────────────────────────────────────────────────────────

    @Test
    fun `Google Pay paid notification`() {
        val result = parseNotification("INR", "Google Pay", "₹250 paid to Zomato", "₹250 paid to Zomato")
        assertTransaction(result, 250.0, "ZOMATO", "INR", isIncome = false)
    }

    @Test
    fun `PhonePe payment successful notification`() {
        val result = parseNotification("INR", "Payment successful", "Paid ₹1,200 to Swiggy")
        assertTransaction(result, 1200.0, "SWIGGY", "INR", isIncome = false)
    }

    @Test
    fun `HDFC truncated text with expanded bigText`() {
        val result = parseNotification(
            "INR",
            "HDFC Bank",
            "Rs.899.00 debited from A/c XX4521 to VPA netfl…",
            "Rs.899.00 debited from A/c XX4521 to VPA netflix@icici on 30-09-26. Ref 627381920011"
        )
        assertTransaction(result, 899.0, "NETFLIX", "INR", isIncome = false)
        assertEquals("4521", result!!.accountLast4)
    }

    @Test
    fun `Paytm wallet received notification is income`() {
        val result = parseNotification("INR", "Paytm", "Received ₹2,000 from Amit Kumar in your Paytm Wallet")
        assertTransaction(result, 2000.0, "AMIT KUMAR", "INR", isIncome = true)
    }

    @Test
    fun `newlines tabs and repeated spaces in notification are normalised`() {
        val result = parseNotification("INR", "HDFC   Bank", "Rs.500.00\n debited\tfrom a/c XX1234 at AMAZON")
        assertTransaction(result, 500.0, "AMAZON", "INR", isIncome = false)
        assertEquals("1234", result!!.accountLast4)
    }

    // ─── US / CA / UK / EU / UAE / SG / AU ────────────────────────────────────

    @Test
    fun `Chase card transaction notification`() {
        val result = parseNotification(
            "USD", "Chase", "You made a \$42.17 transaction with STARBUCKS on your card ending in 4012"
        )
        assertTransaction(result, 42.17, "STARBUCKS", "USD", isIncome = false)
        assertEquals("4012", result!!.accountLast4)
    }

    @Test
    fun `Capital One purchase with thousands separator`() {
        val result = parseNotification(
            "USD", "Capital One", "A purchase of \$1,249.99 was charged at APPLE.COM/BILL on card ending 7788"
        )
        assertTransaction(result, 1249.99, "APPLE", "USD", isIncome = false)
    }

    @Test
    fun `untitled P2P paid you notification is income from sender`() {
        val result = parseNotification("USD", "", "Alex Morgan paid you \$35.00")
        assertTransaction(result, 35.0, "ALEX MORGAN", "USD", isIncome = true)
    }

    @Test
    fun `untitled P2P sent notification is expense to recipient`() {
        val result = parseNotification("USD", "", "You sent \$20 to Jordan Lee")
        assertTransaction(result, 20.0, "JORDAN LEE", "USD", isIncome = false)
    }

    @Test
    fun `RBC Interac e-Transfer notification`() {
        val result = parseNotification("CAD", "RBC", "INTERAC e-Transfer: You sent \$85.00 to Tim Hortons")
        assertTransaction(result, 85.0, "TIM HORTONS", "CAD", isIncome = false)
    }

    @Test
    fun `Monzo title-only amount notification`() {
        val result = parseNotification("GBP", "Monzo", "£4.50 at Pret A Manger")
        assertTransaction(result, 4.5, "PRET A MANGER", "GBP", isIncome = false)
    }

    @Test
    fun `Monzo notification with trailing emoji`() {
        val result = parseNotification("GBP", "Monzo", "You spent £4.50 at Pret A Manger ☕")
        assertTransaction(result, 4.5, "PRET A MANGER", "GBP", isIncome = false)
    }

    @Test
    fun `Revolut euro card payment`() {
        val result = parseNotification("EUR", "Revolut", "You paid €12.50 at Lidl")
        assertTransaction(result, 12.5, "LIDL", "EUR", isIncome = false)
    }

    @Test
    fun `N26 card payment with card ending`() {
        val result = parseNotification("EUR", "N26", "Spent €32.50 at Carrefour with card ending 1024")
        assertTransaction(result, 32.5, "CARREFOUR", "EUR", isIncome = false)
        assertEquals("1024", result!!.accountLast4)
    }

    @Test
    fun `Emirates NBD card spend`() {
        val result = parseNotification("AED", "Emirates NBD", "AED 150.00 spent on Card ending 4455 at Lulu Hypermarket")
        assertTransaction(result, 150.0, "LULU HYPERMARKET", "AED", isIncome = false)
    }

    @Test
    fun `DBS PayNow transfer`() {
        val result = parseNotification("SGD", "DBS", "You have sent S\$22.80 to Grab via PayNow")
        assertTransaction(result, 22.8, "GRAB", "SGD", isIncome = false)
    }

    @Test
    fun `CommBank PayID transfer`() {
        val result = parseNotification("AUD", "CommBank", "You transferred \$45.00 to Woolworths via PayID")
        assertTransaction(result, 45.0, "WOOLWORTHS", "AUD", isIncome = false)
    }

    // ─── SLOVAKIA (EUR) ───────────────────────────────────────────────────────

    @Test
    fun `Slovak card payment titled Platba kartou`() {
        val result = parseNotification(
            "EUR", "Platba kartou", "Platba kartou *1234 v sume 23.70 EUR u LIDL dňa 30.09.2026"
        )
        assertTransaction(result, 23.7, "LIDL", "EUR", isIncome = false)
    }

    @Test
    fun `Prima banka card payment at NYX`() {
        val result = parseNotification(
            "EUR",
            "Platba kartou: 0,50 EUR",
            "Platba kartou *3677 v sume 0,50 EUR, NYX*OCPRESOVsro,Bratislava,SK, DISPO: 19,76 EUR (19,76 EUR), dňa: 30.09.2026 17:14:12"
        )
        assertTransaction(result, 0.50, "NYX*OCPRESOVSRO", "EUR", isIncome = false)
        assertEquals("3677", result!!.accountLast4)
    }

    @Test
    fun `Prima banka card payment at Dr Max`() {
        val result = parseNotification(
            "EUR",
            "Platba kartou: 12,19 EUR",
            "Platba kartou *3677 v sume 12,19 EUR, Dr.Max 529, PO Novum,Presov,SK, DISPO: 20,26 EUR (20,26 EUR), dňa: 30.09.2026 12:19:53"
        )
        assertTransaction(result, 12.19, "DR.MAX 529", "EUR", isIncome = false)
        assertEquals("3677", result!!.accountLast4)
    }

    @Test
    fun `Slovak ATM withdrawal amount is parsed`() {
        val result = parseNotification("EUR", "SLSP", "Výber z bankomatu 60.00 EUR kartou *5566")
        assertNotNull(result)
        assertEquals(60.0, result!!.amount, 0.001)
        assertFalse(result.isIncome)
    }

    // ─── REJECTION & EDGE CASES ───────────────────────────────────────────────

    @Test
    fun `OTP notification mentioning an amount is rejected`() {
        assertNull(parseNotification("INR", "SBI", "Your OTP for transaction of Rs.1500 is 482913. Do not share."))
    }

    @Test
    fun `delivery notification with order value is rejected`() {
        assertNull(parseNotification("INR", "Amazon", "Your package with Rs.499 order has been delivered"))
    }

    @Test
    fun `statement balance reminder is rejected`() {
        assertNull(parseNotification("USD", "Bank of America", "Your statement balance is \$3,200.00. Payment due Oct 15."))
    }

    @Test
    fun `Slovak verification code notification is rejected`() {
        assertNull(parseNotification("EUR", "Tatra banka", "Váš overovací kód je 123456"))
    }

    @Test
    fun `chat notification is rejected`() {
        assertNull(parseNotification("INR", "WhatsApp", "Mom: dinner at 8?"))
    }

    @Test
    fun `blank notification is rejected`() {
        assertNull(parseNotification("INR", "", "   "))
    }

    @Test
    fun `zero amount notification is rejected`() {
        assertNull(parseNotification("INR", "Google Pay", "₹0.00 paid to Test Merchant"))
    }

    @Test
    fun `amount over one million without decimals is rejected`() {
        assertNull(parseNotification("USD", "Chase", "You made a \$1,500,000 purchase at TESLA with card ending 1111"))
    }

    @Test
    fun `grouped inbox notification without account evidence is rejected`() {
        val message = NotificationTextAssembler.assemble(
            title = "2 new transactions",
            textLines = "Rs.250.00 debited at ZOMATO Rs.1,000.00 debited at AMAZON"
        )
        assertNull(parser.parse(message, "INR"))
    }

    // ─── REGRESSIONS ──────────────────────────────────────────────────────────

    @Test
    fun `PhonePe title does not become the merchant`() {
        val result = parseNotification("INR", "PhonePe", "Paid ₹1,200 to Swiggy")
        assertTransaction(result, 1200.0, "SWIGGY", "INR", isIncome = false)
    }

    @Test
    fun `Google Pay title does not become the merchant for P2P payments`() {
        val result = parseNotification("INR", "Google Pay", "₹300 paid to Ravi via UPI. Ref 6273819")
        assertTransaction(result, 300.0, "RAVI", "INR", isIncome = false)
    }

    @Test
    fun `Tatra banka title does not become the merchant`() {
        val result = parseNotification(
            "EUR", "Tatra banka", "Platba kartou *1234 v sume 23.70 EUR u LIDL dňa 30.09.2026"
        )
        assertTransaction(result, 23.7, "LIDL", "EUR", isIncome = false)
    }

    @Test
    fun `Revolut title does not become the merchant`() {
        val result = parseNotification("GBP", "Revolut", "Paid £18.20 at TESCO")
        assertTransaction(result, 18.2, "TESCO", "GBP", isIncome = false)
    }

    @Test
    fun `Cash App title does not become the merchant`() {
        val result = parseNotification("USD", "Cash App", "You sent \$20 to Jordan Lee")
        assertTransaction(result, 20.0, "JORDAN LEE", "USD", isIncome = false)
    }

    @Test
    fun `Venmo title is not merged into the sender name`() {
        val result = parseNotification("USD", "Venmo", "Alex Morgan paid you \$35.00")
        assertTransaction(result, 35.0, "ALEX MORGAN", "USD", isIncome = true)
    }

    @Test
    fun `merchant containing an exclusion keyword keeps INR currency`() {
        val result = parseNotification("INR", "Google Pay", "₹450 paid to Wonderla Holidays via UPI. Ref 6273819")
        assertNotNull(result)
        assertEquals(450.0, result!!.amount, 0.001)
        assertEquals("INR", result.currency)
    }

    @Test
    fun `payee name containing an exclusion keyword keeps INR currency`() {
        val result = parseNotification("INR", "Google Pay", "₹300 paid to Edwin via UPI. Ref 6273819")
        assertNotNull(result)
        assertEquals("INR", result!!.currency)
    }

    @Test
    fun `Slovak payment at a merchant containing an exclusion keyword is parsed`() {
        val result = parseNotification("EUR", "Platba kartou", "Platba kartou 45.90 EUR u DATART dňa 30.09.2026")
        assertNotNull(result)
        assertEquals(45.9, result!!.amount, 0.001)
    }

    @Test
    fun `Slovak incoming transfer is income`() {
        val result = parseNotification("EUR", "George", "Prípísanie na účet: 150.00 EUR od Jan Novak")
        assertNotNull(result)
        assertEquals(150.0, result!!.amount, 0.001)
        assertTrue(result.isIncome)
    }

    @Test
    fun `Slovak single-asterisk card number is extracted`() {
        val result = parseNotification(
            "EUR", "Platba kartou", "Platba kartou *1234 v sume 23.70 EUR u LIDL dňa 30.09.2026"
        )
        assertEquals("1234", result?.accountLast4)
    }

    @Test
    fun `yen payment keeps JPY currency`() {
        val result = parseNotification("JPY", "PayPay", "Paid ¥1,200.00 to FamilyMart")
        assertNotNull(result)
        assertEquals(1200.0, result!!.amount, 0.001)
        assertEquals("JPY", result.currency)
    }

    @Test
    fun `dollar payment for a yen user is still USD`() {
        val result = parseNotification("JPY", "Apple Pay", "Paid \$9.99 to Netflix with card ending 1234")
        assertNotNull(result)
        assertEquals("USD", result!!.currency)
    }

    @Test
    fun `Slovak comma decimal amount is parsed`() {
        val result = parseNotification("EUR", "Tatra banka", "Platba kartou *1234 v sume 23,70 EUR u LIDL dňa 30.09.2026")
        assertTransaction(result, 23.7, "LIDL", "EUR", isIncome = false)
    }

    @Test
    fun `inflected exclusion keywords are still rejected`() {
        assertNull(parseNotification("INR", "Jio", "Your pack of Rs.299 has expired. Pay via UPI to continue."))
        assertNull(parseNotification("INR", "Lucky Draw", "You are a winner of Rs.5000 paid via UPI to your account XX1234"))
    }

    @Test
    fun `title alone is used as merchant when the body has none`() {
        val result = parseNotification("INR", "Paid to Swiggy", "₹1,200 debited from A/c XX4521")
        assertNotNull(result)
        assertEquals(1200.0, result!!.amount, 0.001)
        assertEquals("SWIGGY", result.merchant)
    }
}
