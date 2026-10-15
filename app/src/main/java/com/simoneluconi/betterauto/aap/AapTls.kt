package com.simoneluconi.betterauto.aap

import android.annotation.SuppressLint
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.X509TrustManager

/**
 * TLS tunnelled inside AAP messages. The phone is the TLS server, the head unit
 * the client (aasdk calls SSL_set_connect_state). Handshake records travel in
 * MESSAGE_ENCAPSULATED_SSL, afterwards every encrypted frame holds TLS records.
 *
 * Certificate: assets/phone_cert.pem + phone_key.pem (self-signed, dev only).
 * aasdk-based head units do not verify it (SSL_VERIFY_NONE); real cars might.
 */
class AapTls(certPem: InputStream, keyPem: InputStream) {
    private val engine: SSLEngine
    private var netIn: ByteBuffer = ByteBuffer.allocate(0)

    var handshakeComplete = false
        private set

    init {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(loadKeyManagers(certPem, keyPem), arrayOf(AcceptAnyHeadUnit), null)
        engine = sslContext.createSSLEngine().apply {
            useClientMode = false
            wantClientAuth = true // only to log the head unit certificate
            enabledProtocols = arrayOf("TLSv1.2")
        }
        engine.beginHandshake()
    }

    /** Feeds handshake bytes from the head unit and returns the bytes to answer with (possibly empty). */
    fun handshake(input: ByteArray): ByteArray {
        netIn = ByteBuffer.allocate(netIn.remaining() + input.size).put(netIn).put(input).flip() as ByteBuffer
        val out = ByteArrayOutputStream()
        val appScratch = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val netOut = ByteBuffer.allocate(engine.session.packetBufferSize)

        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_UNWRAP -> {
                    val result = engine.unwrap(netIn, appScratch)
                    if (result.status == Status.BUFFER_UNDERFLOW) break // wait for the next message
                    if (result.status != Status.OK) throw IOException("TLS handshake unwrap: ${result.status}")
                }
                HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(EMPTY, netOut)
                    if (result.status != Status.OK) throw IOException("TLS handshake wrap: ${result.status}")
                    out.write(netOut.array(), 0, netOut.position())
                }
                HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                }
                else -> {
                    handshakeComplete = true
                    break
                }
            }
        }
        return out.toByteArray()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(plain)
        val dst = ByteBuffer.allocate(engine.session.packetBufferSize)
        val out = ByteArrayOutputStream()
        while (src.hasRemaining()) {
            dst.clear()
            val result = engine.wrap(src, dst)
            if (result.status != Status.OK) throw IOException("TLS wrap: ${result.status}")
            out.write(dst.array(), 0, dst.position())
        }
        return out.toByteArray()
    }

    fun decrypt(data: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(data)
        val dst = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val out = ByteArrayOutputStream()
        while (src.hasRemaining()) {
            dst.clear()
            val result = engine.unwrap(src, dst)
            if (result.status != Status.OK) throw IOException("TLS unwrap: ${result.status}")
            out.write(dst.array(), 0, dst.position())
            if (result.bytesConsumed() == 0) break
        }
        return out.toByteArray()
    }

    val protocol: String get() = engine.session.protocol

    fun describeSession(): String {
        val session = engine.session
        val peer = runCatching { (session.peerCertificates.first() as X509Certificate).subjectX500Principal.name }
            .getOrDefault("no certificate")
        return "${session.protocol} ${session.cipherSuite}, head unit: $peer"
    }

    private fun loadKeyManagers(certPem: InputStream, keyPem: InputStream): Array<KeyManager> {
        val cert = certPem.use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val keyText = keyPem.bufferedReader().use { it.readText() }
        val keyDer = Base64.getMimeDecoder().decode(keyText.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyDer))

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("phone", key, KEY_PASSWORD, arrayOf(cert))
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, KEY_PASSWORD) }
            .keyManagers
    }

    /** We are the phone: any car is welcome, its certificate is only logged. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AcceptAnyHeadUnit : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
        private val KEY_PASSWORD = "betterauto".toCharArray()

        fun fromAssets(context: Context) =
            AapTls(context.assets.open("phone_cert.pem"), context.assets.open("phone_key.pem"))
    }
}
