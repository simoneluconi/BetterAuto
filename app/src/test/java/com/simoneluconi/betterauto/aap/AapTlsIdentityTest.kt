package com.simoneluconi.betterauto.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.util.Base64

class AapTlsIdentityTest {
    private val pemFiles = File("src/main/assets").listFiles()!!.filter { it.extension == "pem" }.map { it.readText() }
    private val key = pemFiles.single { "PRIVATE KEY" in it }
    private val cert = pemFiles.single { "CERTIFICATE" in it }

    @Test
    fun keyAndCertificateAreFoundByContentInAnyOrder() {
        val (_, chain) = AapTls.findIdentity(listOf(cert, key))
        assertEquals(1, chain.size)
        assertEquals(chain, AapTls.findIdentity(listOf(key, cert)).second)
        // One file holding both, with text around the blocks
        assertEquals(chain, AapTls.findIdentity(listOf("phone identity\n$cert\n$key\n")).second)
    }

    @Test
    fun aKeyWithoutItsCertificateIsRefused() {
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val error = assertThrows(GeneralSecurityException::class.java) {
            AapTls.findIdentity(listOf(cert, pem("PRIVATE KEY", otherKey.encoded)))
        }
        assertTrue(error.message!!, "No certificate matches" in error.message!!)
    }

    @Test
    fun missingOrOldFormatKeysSayWhatToDo() {
        val missing = assertThrows(GeneralSecurityException::class.java) { AapTls.findIdentity(listOf(cert)) }
        assertTrue(missing.message!!, "No private key" in missing.message!!)

        val pkcs1 = assertThrows(GeneralSecurityException::class.java) {
            AapTls.findIdentity(listOf(cert, pem("RSA PRIVATE KEY", ByteArray(16))))
        }
        assertTrue(pkcs1.message!!, "openssl pkcs8" in pkcs1.message!!)
    }

    private fun pem(type: String, der: ByteArray) =
        "-----BEGIN $type-----\n${Base64.getMimeEncoder().encodeToString(der)}\n-----END $type-----\n"
}
