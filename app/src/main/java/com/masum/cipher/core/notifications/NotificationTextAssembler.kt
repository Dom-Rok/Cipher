package com.masum.cipher.core.notifications

/**
 * Joins the text fields of a posted notification into the single message that is
 * handed to the transaction parser. Blank and duplicate fields are dropped so that
 * e.g. a bigText identical to text is not parsed twice.
 */
object NotificationTextAssembler {

    fun assemble(
        title: String = "",
        titleBig: String = "",
        text: String = "",
        bigText: String = "",
        subText: String = "",
        textLines: String = "",
        summaryText: String = "",
        infoText: String = ""
    ): String {
        return listOf(title, titleBig, text, bigText, subText, textLines, summaryText, infoText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .trim()
    }
}
