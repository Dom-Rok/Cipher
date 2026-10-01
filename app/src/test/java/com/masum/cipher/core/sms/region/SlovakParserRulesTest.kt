package com.masum.cipher.core.sms.region

import com.masum.cipher.core.sms.TransactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SlovakParserRulesTest {

    private lateinit var parser: TransactionParser

    @Before
    fun setup() {
        parser = TransactionParser()
    }

    private fun parseSk(message: String) = parser.parse(message, preferredCurrency = "EUR")

    // ────── REJECTION TESTS ──────

    @Test
    fun `otp code is rejected`() {
        assertNull(parseSk("Vas kod: 123456"))
    }

    @Test
    fun `promotional offer is rejected`() {
        assertNull(parseSk("Ponuka: zlava 50%"))
    }

    @Test
    fun `confirmation request is rejected`() {
        assertNull(parseSk("Kliknite pre potvrdenie"))
    }

    @Test
    fun `non-transactional message returns null`() {
        assertNull(parseSk("Your appointment is at 3pm tomorrow"))
    }

    // ────── AMOUNT EXTRACTION TESTS ──────

    @Test
    fun `amount with euro symbol is parsed`() {
        val result = parseSk("Platba: EUR 23.70 v LIDL")
        assertNotNull(result)
        assertEquals(23.70, result!!.amount, 0.001)
    }

    @Test
    fun `amount in sume format is parsed`() {
        val result = parseSk("Prípísanie sumy EUR 50.00")
        assertNotNull(result)
        assertEquals(50.00, result!!.amount, 0.001)
    }

    @Test
    fun `amount with v sume is extracted`() {
        val result = parseSk("Prevod v sume EUR 100.00")
        assertNotNull(result)
        assertEquals(100.00, result!!.amount, 0.001)
    }

    @Test
    fun `euro symbol amount is recognized`() {
        val result = parseSk("Spent EUR 32.50")
        assertNotNull(result)
        assertEquals(32.50, result!!.amount, 0.001)
    }

    @Test
    fun `amount after colon is extracted`() {
        val result = parseSk("Platba: EUR 45.99")
        assertNotNull(result)
        assertEquals(45.99, result!!.amount, 0.001)
    }

    // ────── MERCHANT EXTRACTION TESTS ──────

    @Test
    fun `LIDL merchant is recognized`() {
        val result = parseSk("Nakup v LIDL EUR 23.70")
        assertNotNull(result)
        assertEquals("LIDL", result!!.merchant)
    }

    @Test
    fun `TESCO merchant is recognized`() {
        val result = parseSk("Platba u TESCO EUR 50.00")
        assertNotNull(result)
        assertEquals("TESCO", result!!.merchant)
    }

    @Test
    fun `NETFLIX merchant is recognized`() {
        val result = parseSk("Poplatok NETFLIX EUR 9.99")
        assertNotNull(result)
        assertEquals("NETFLIX", result!!.merchant)
    }

    @Test
    fun `UBER merchant is recognized`() {
        val result = parseSk("Jazda UBER EUR 12.50")
        assertNotNull(result)
        assertEquals("UBER", result!!.merchant)
    }

    // ────── INCOME VS EXPENSE ──────

    @Test
    fun `credit deposit is marked as income`() {
        val result = parseSk("Prípísanie sumy EUR 50.00")
        assertNotNull(result)
        assertTrue(result!!.isIncome)
    }

    @Test
    fun `debit payment is marked as expense`() {
        val result = parseSk("Platba kartou EUR 23.70 v LIDL")
        assertNotNull(result)
        assertTrue(!result!!.isIncome)
    }

    @Test
    fun `withdrawal is marked as expense`() {
        val result = parseSk("Vyber EUR 100.00")
        assertNotNull(result)
        assertTrue(!result!!.isIncome)
    }

    @Test
    fun `received deposit is marked as income`() {
        val result = parseSk("Prijata suma EUR 200.00")
        assertNotNull(result)
        assertTrue(result!!.isIncome)
    }

    // ────── ACCOUNT IDENTIFICATION ──────

    @Test
    fun `account last4 from asterisks is extracted`() {
        val result = parseSk("Platba kartou *3677 EUR 23.70")
        assertNotNull(result)
        assertEquals("3677", result!!.accountLast4)
    }

    @Test
    fun `account last4 from double asterisks is extracted`() {
        val result = parseSk("Platba kartou **4532 EUR 45.99")
        assertNotNull(result)
        assertEquals("4532", result!!.accountLast4)
    }

    // ────── CURRENCY ──────

    @Test
    fun `parsed transaction uses EUR as default currency`() {
        val result = parseSk("Platba kartou EUR 45.99")
        assertNotNull(result)
        assertEquals("EUR", result!!.currency)
    }

    // ────── EDGE CASES ──────

    @Test
    fun `empty message returns null`() {
        assertNull(parseSk(""))
    }

    @Test
    fun `simple payment works`() {
        val result = parseSk("Platba EUR 25.00 v obchode")
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.001)
    }
}
