package com.naslink.stream

import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.security.jce.JceSecurityProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the crypto-backend choice.
 *
 * The tempting "cleanup" is to always use the platform provider, because it is faster.
 * That silently breaks SMB3 encryption, which negotiates AES-CCM: the platform provider
 * has no CCM cipher. These tests pin the behaviour so the trade-off stays visible.
 */
class SmbSecurityProviderTest {

    @Test
    fun `unencrypted connections use the faster platform provider`() {
        assertTrue(
            "signing is the hot path, so the platform provider should win when encryption is off",
            SmbSessionPool.securityProviderFor(encryptData = false) is JceSecurityProvider,
        )
    }

    @Test
    fun `encrypted connections keep the bundled provider because the platform lacks CCM`() {
        // This is the whole reason for the split: AES-CCM is what SMB3 encryption
        // negotiates, and only the bundled backend implements it. Verified on JDK 17:
        // the platform provider has no CCM under either accessor, AEAD or plain.
        assertTrue(
            "switching to the platform provider here would break encrypted NAS setups",
            SmbSessionPool.securityProviderFor(encryptData = true) is BCSecurityProvider,
        )
        assertNotNull(
            "sanity: the bundled provider supplies CCM through the AEAD interface",
            BCSecurityProvider().getAEADBlockCipher("AES/CCM/NoPadding"),
        )
        assertTrue(
            "sanity: the platform provider really cannot supply CCM",
            runCatching { JceSecurityProvider().getAEADBlockCipher("AES/CCM/NoPadding") }.isFailure,
        )
    }

    @Test
    fun `both backends can produce AES-GCM, the dialect 3_1_1 cipher`() {
        for (provider in listOf(SmbSessionPool.securityProviderFor(false), SmbSessionPool.securityProviderFor(true))) {
            val label = provider.javaClass.simpleName
            assertNotNull("$label is missing AES-GCM", provider.getAEADBlockCipher("AES/GCM/NoPadding"))
        }
    }

    @Test
    fun `both backends cover the algorithms a signed SMB3 session needs`() {
        // Signing runs on every message regardless of the encryption setting, so neither
        // backend may be missing anything used on the plain path.
        for (provider in listOf(SmbSessionPool.securityProviderFor(false), SmbSessionPool.securityProviderFor(true))) {
            val label = provider.javaClass.simpleName
            assertNotNull("$label is missing MD5", provider.getDigest("MD5"))
            assertNotNull("$label is missing SHA256", provider.getDigest("SHA256"))
            assertNotNull("$label is missing HmacSHA256", provider.getMac("HmacSHA256"))
            assertNotNull("$label is missing HmacMD5", provider.getMac("HmacMD5"))
            assertNotNull("$label is missing the SMB3 KDF", provider.getDerivationFunction("KDF/Counter/HMACSHA256"))
        }
    }

    @Test
    fun `the built config carries the provider and encryption flag the policy chose`() {
        val plain = SmbSessionPool.defaultConfig(encryptData = false)
        val encrypted = SmbSessionPool.defaultConfig(encryptData = true)

        assertTrue(
            "the config must not silently fall back to smbj's own default provider",
            plain.securityProvider is JceSecurityProvider,
        )
        assertTrue(encrypted.securityProvider is BCSecurityProvider)
        assertEquals("the encryption flag must reach the config", false, plain.isEncryptData)
        assertEquals("the encryption flag must reach the config", true, encrypted.isEncryptData)
    }
}
