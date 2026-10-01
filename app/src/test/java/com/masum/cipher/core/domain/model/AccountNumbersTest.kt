package com.masum.cipher.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountNumbersTest {

    @Test
    fun `single number still works`() {
        assertEquals(listOf("3677"), AccountNumbers.parse("3677"))
        assertTrue(AccountNumbers.matches("3677", "3677"))
    }

    @Test
    fun `several numbers are split on commas and spaces`() {
        assertEquals(listOf("3677", "001"), AccountNumbers.parse("3677, 001"))
        assertEquals(listOf("3677", "001"), AccountNumbers.parse(" 3677,001 "))
        assertEquals(listOf("3677", "001"), AccountNumbers.parse("3677 001"))
    }

    @Test
    fun `any of the numbers matches`() {
        assertTrue(AccountNumbers.matches("3677, 001", "3677"))
        assertTrue(AccountNumbers.matches("3677, 001", "001"))
        assertFalse(AccountNumbers.matches("3677, 001", "021"))
    }

    @Test
    fun `input is normalised for storage`() {
        assertEquals("3677, 001", AccountNumbers.normalize("3677,001"))
        assertEquals("3677, 001", AccountNumbers.normalize("3677, , 001,"))
        assertNull(AccountNumbers.normalize(" , "))
    }

    @Test
    fun `long numbers keep their last four digits`() {
        assertEquals(listOf("4567"), AccountNumbers.parse("1234567"))
    }

    @Test
    fun `primary is the first number for display`() {
        assertEquals("3677", AccountNumbers.primary("3677, 001"))
        assertNull(AccountNumbers.primary(null))
    }

    @Test
    fun `empty field matches nothing`() {
        assertFalse(AccountNumbers.matches(null, "3677"))
        assertFalse(AccountNumbers.matches("", "3677"))
    }
}
