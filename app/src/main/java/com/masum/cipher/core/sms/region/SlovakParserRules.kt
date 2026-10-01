package com.masum.cipher.core.sms.region

import java.util.regex.Pattern

object SlovakParserRules : RegionParserRules {
    override val regionCode: String = "SK"
    override val defaultCurrency: String = "EUR"

    override val amountPatterns: List<Pattern> = listOf(
        Pattern.compile("(?i)(?:eur|€)\\s+([\\d.,]+)"),
        Pattern.compile("(?i)([\\d.,]+)\\s+(?:eur|€)"),
        Pattern.compile("(?i)(?:platba|prípísanie|výber|suma)\\s+([\\d.,]+)"),
        Pattern.compile("(?i):\\s*([\\d.,]+)\\s*(?:eur)?"),
        Pattern.compile("(?i)(?<!a/c |account )([\\d.,]+)\\s+(?=eur|€)")
    )

    override val exclusionKeywords: List<String> = listOf(
        "potvrdenie", "overenie", "kódu", "heslo", "platnosť", "ponuka", "zľava", "bonus", "aktivácia", "registrácia",
        "eligible", "exclusive", "discount", "limited", "upgrade", "plan", "dáta", "data", "unlimited", "offer"
    )

    override val evidencePatterns: List<Pattern> = listOf(
        Pattern.compile("(?i)\\*\\d{4}|\\*\\*\\d{3,4}|xxx\\d{4}"),
        Pattern.compile("(?i)kartou|karta"),
        Pattern.compile("(?i)prípísanie|výber|platba|transakcia"),
        Pattern.compile("(?i)suma|sume|v sume"),
        Pattern.compile("(?i)dňa\\s+[\\d.]+"),
        Pattern.compile("(?i)€\\s*[\\d,]+"),
        Pattern.compile("(?i)eur\\s+[\\d,]+"),
        Pattern.compile("(?i)konto|účet"),
        Pattern.compile("(?i)prijata|prijatá|received"),
        Pattern.compile("(?i)depozit|vklad")
    )

    override val intentKeywords: List<String> = listOf(
        "eur", "€", "platba", "prípísanie", "prijata", "prijatá", "výber", "suma", "sume", "transakcia", "kartou"
    )

    override val structuralMerchantPatterns: List<Pattern> = listOf(
        // Prima banka: "Platba kartou *3677 v sume 12,19 EUR, Dr.Max 529, PO Novum,Presov,SK, DISPO: ..."
        Pattern.compile("(?i)v\\s+sume\\s+[\\d.,]+\\s*(?:eur|€)\\s*,\\s*([^,]+)"),
        Pattern.compile("(?i)^([A-Za-z][A-Za-z0-9\\s&.]{1,}?)\\s+(?:zaň|platba|transakcia)"),
        Pattern.compile("(?i)www\\.([A-Za-z0-9.]+)"),
        // Transfers: "Prevod z účtu *3677 v sume 100,00 EUR na účet Sporenie, ..." -> "Sporenie"
        Pattern.compile("(?i)(?:z konta|na konta|z účtu|na účet)\\s+(\\p{L}[\\p{L}0-9 .]{1,40}?)(?=\\s*,|\\s+v\\s+sume|\\s+dňa|$)"),
        Pattern.compile("(?i)(?:v|u|na|do)\\s+(?!sume\\b)([A-Za-z][A-Za-z0-9\\s.]{2,30}?)(?=,|\\s+v\\s+|\\s+na|\\s+dňa|$)"),
        Pattern.compile("(?i)([A-Z][A-Z0-9]{2,})\\s+(?:\\d{5,}|dakuje|nákup|prevod)")
    )

    override val brandDictionary: List<String> = listOf(
        "LIDL", "TESCO", "KAUFLAND", "METRO", "BILLA", "ALBERT", "COOP", "INTERSPAR", "PLUS",
        "AMAZON", "ALIEXPRESS", "MALL", "MALL.SK", "ZALANDO", "EMAG", "ALZA",
        "ZOMATO", "UBER", "BOLT", "WOLT", "GLOVO",
        "VODAFONE", "O2", "ORANGE", "TATRA BANKA", "OTP BANK", "ERSTE BANK", "VUB", "SLSP",
        "DPD", "UPS", "FEDEX", "GLS", "SPAR", "KAUFPARK", "BAUMARKT",
        "NETFLIX", "SPOTIFY", "PRIME VIDEO", "DEEZER", "YOUTUBE",
        "MASTERCARD", "VISA", "AMEX", "PAYPAL", "SKRILL", "WISE"
    )
}
