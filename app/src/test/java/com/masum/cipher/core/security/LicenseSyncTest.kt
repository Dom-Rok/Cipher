package com.masum.cipher.core.security

import com.masum.cipher.core.worker.LicenseSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseSyncTest {

    @Test
    fun testRemoteLicenseCheckResultTypes() {
        val valid = RemoteLicenseCheckResult.Valid(ProTier.ANNUAL, deviceCount = 2, maxDevices = 3)
        assertEquals(ProTier.ANNUAL, valid.tier)
        assertEquals(2, valid.deviceCount)
        assertEquals(3, valid.maxDevices)

        val revoked = RemoteLicenseCheckResult.Revoked("refund.succeeded")
        assertEquals("refund.succeeded", revoked.reason)

        val expired = RemoteLicenseCheckResult.Expired("subscription.expired")
        assertEquals("subscription.expired", expired.reason)

        val notFound = RemoteLicenseCheckResult.NotFound("Key not in KV")
        assertEquals("Key not in KV", notFound.message)

        val networkErr = RemoteLicenseCheckResult.NetworkError("Connect timeout")
        assertEquals("Connect timeout", networkErr.error)
    }

    @Test
    fun testOfflineGracePeriodCalculation() {
        val gracePeriodMs = LicenseSyncWorker.OFFLINE_GRACE_PERIOD_MS
        assertEquals(30L * 24L * 60L * 60L * 1000L, gracePeriodMs)

        val now = System.currentTimeMillis()
        val sync7DaysAgo = now - (7L * 24L * 60L * 60L * 1000L)
        val sync29DaysAgo = now - (29L * 24L * 60L * 60L * 1000L)
        val sync31DaysAgo = now - (31L * 24L * 60L * 60L * 1000L)

        val isGraceValid7 = (now - sync7DaysAgo) <= gracePeriodMs
        val isGraceValid29 = (now - sync29DaysAgo) <= gracePeriodMs
        val isGraceValid31 = (now - sync31DaysAgo) <= gracePeriodMs

        assertTrue(isGraceValid7)
        assertTrue(isGraceValid29)
        assertFalse(isGraceValid31)
    }

    @Test
    fun testProTierParsing() {
        val lifetime = ProTier.entries.firstOrNull { it.identifier.equals("LIFETIME", ignoreCase = true) }
        val annual = ProTier.entries.firstOrNull { it.identifier.equals("ANNUAL", ignoreCase = true) }
        val monthly = ProTier.entries.firstOrNull { it.identifier.equals("MONTHLY", ignoreCase = true) }
        val halfYearly = ProTier.entries.firstOrNull { it.identifier.equals("HALF_YEARLY", ignoreCase = true) }

        assertEquals(ProTier.LIFETIME, lifetime)
        assertEquals(ProTier.ANNUAL, annual)
        assertEquals(ProTier.MONTHLY, monthly)
        assertEquals(ProTier.HALF_YEARLY, halfYearly)
    }

    @Test
    fun testAlgorithmicPromoCodeValidation() {
        val engine = LicenseEngine()
        val validKey = "CIPHER-LIFETIME-VIP2026-6AA7"
        val invalidKey = "CIPHER-LIFETIME-VIP2026-0000"
        val fakeUuid = "eb17883c-da4c-4d62-8c33-b0a74aec0f36"

        assertTrue(engine.isAlgorithmicPromoCode(validKey))
        assertFalse(engine.isAlgorithmicPromoCode(invalidKey))
        assertFalse(engine.isAlgorithmicPromoCode(fakeUuid))

        val validResult = engine.validateLicense(validKey)
        assertTrue(validResult.isValid)
        assertEquals(ProTier.LIFETIME, validResult.tier)

        val fakeUuidResult = engine.validateLicense(fakeUuid)
        assertFalse(fakeUuidResult.isValid)
    }

    @Test
    fun testUuidFormatDetection() {
        val engine = LicenseEngine()
        assertTrue(engine.isUuidFormat("eb17883c-da4c-4d62-8c33-b0a74aec0f36"))
        assertTrue(engine.isUuidFormat("EB17883C-DA4C-4D62-8C33-B0A74AEC0F36"))
        assertFalse(engine.isUuidFormat("CIPHER-LIFETIME-VIP2026-6AA7"))
        assertFalse(engine.isUuidFormat("invalid-uuid-string"))
    }

    @Test
    fun testPromoKeyExpiryCalculation() {
        val engine = LicenseEngine()
        val sixMonthKey = "CIPHER-6MONTH-REDDIT6M-1AC0"
        val res = engine.validateLicense(sixMonthKey)
        assertTrue(res.isValid)
        assertEquals(ProTier.HALF_YEARLY, res.tier)
        val now = System.currentTimeMillis()
        assertTrue(res.expiresAtEpochMs > now)
        val diffDays = (res.expiresAtEpochMs - res.issuedAtEpochMs) / (24L * 60L * 60L * 1000L)
        assertEquals(180L, diffDays)

        val lifetimeKey = "CIPHER-LIFETIME-VIP2026-6AA7"
        val lifeRes = engine.validateLicense(lifetimeKey)
        assertEquals(0L, lifeRes.expiresAtEpochMs)
    }
}
