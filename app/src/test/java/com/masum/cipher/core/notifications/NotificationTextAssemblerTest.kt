package com.masum.cipher.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextAssemblerTest {

    @Test
    fun `title and text are joined with a single space`() {
        val message = NotificationTextAssembler.assemble(title = "Google Pay", text = "₹250 paid to Zomato")
        assertEquals("Google Pay ₹250 paid to Zomato", message)
    }

    @Test
    fun `fields are joined in title, titleBig, text, bigText, subText, textLines, summary, info order`() {
        val message = NotificationTextAssembler.assemble(
            title = "1",
            titleBig = "2",
            text = "3",
            bigText = "4",
            subText = "5",
            textLines = "6",
            summaryText = "7",
            infoText = "8"
        )
        assertEquals("1 2 3 4 5 6 7 8", message)
    }

    @Test
    fun `bigText identical to text is not duplicated`() {
        val alert = "You made a \$42.17 transaction with STARBUCKS on your card ending in 4012"
        val message = NotificationTextAssembler.assemble(title = "Chase", text = alert, bigText = alert)
        assertEquals("Chase $alert", message)
    }

    @Test
    fun `truncated text and its expanded bigText are both kept`() {
        val message = NotificationTextAssembler.assemble(
            text = "Rs.899.00 debited from A/c XX4521 to VPA netfl…",
            bigText = "Rs.899.00 debited from A/c XX4521 to VPA netflix@icici"
        )
        assertEquals(
            "Rs.899.00 debited from A/c XX4521 to VPA netfl… Rs.899.00 debited from A/c XX4521 to VPA netflix@icici",
            message
        )
    }

    @Test
    fun `blank and whitespace-only fields are dropped`() {
        val message = NotificationTextAssembler.assemble(title = "", titleBig = "   ", text = "Paid £4.50 at Pret", subText = "\n")
        assertEquals("Paid £4.50 at Pret", message)
    }

    @Test
    fun `non-adjacent duplicates are also dropped`() {
        val message = NotificationTextAssembler.assemble(title = "A", titleBig = "A", bigText = "B", summaryText = "B", infoText = "C")
        assertEquals("A B C", message)
    }

    @Test
    fun `notification with no text produces empty message`() {
        assertEquals("", NotificationTextAssembler.assemble())
    }
}
