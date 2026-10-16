package com.simoneluconi.betterauto.aap

import android.annotation.SuppressLint
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
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
 * Certificate: the PEM files in assets/, found by content (a self-signed one for development).
 * aasdk-based head units do not verify it (SSL_VERIFY_NONE); factory head units do.
 */
class AapTls(pem: Collection<String>) {
    private val engine: SSLEngine
    private var netIn: ByteBuffer = ByteBuffer.allocate(0)

    var handshakeComplete = false
        private set

    init {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(loadKeyManagers(pem), arrayOf(AcceptAnyHeadUnit), null)
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

    fun describeSession(): String =
        "${engine.session.protocol} ${engine.session.cipherSuite}, head unit: ${peerName() ?: "no certificate"}"

    /** The head unit maker from its certificate (O=Volkswagen...), null before the handshake. */
    val peerOrganization: String? get() = peerName()?.let(::organizationOf)

    private fun peerName() =
        runCatching { (engine.session.peerCertificates.first() as X509Certificate).subjectX500Principal.name }.getOrNull()

    private fun loadKeyManagers(pem: Collection<String>): Array<KeyManager> {
        val (key, chain) = findIdentity(pem)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("phone", key, KEY_PASSWORD, chain.toTypedArray())
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

        private val PEM_EXTENSIONS = setOf("pem", "crt", "cer", "key")
        private val PEM_BLOCK = Regex("""-----BEGIN ([A-Z0-9 ]+)-----([\s\S]*?)-----END \1-----""")

        /** Every PEM file at the root of assets/, whatever its name: [findIdentity] tells them apart by content. */
        fun fromAssets(context: Context) = AapTls(
            context.assets.list("").orEmpty()
                .filter { it.substringAfterLast('.', "").lowercase() in PEM_EXTENSIONS }
                .map { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
        )

        /**
         * The phone's private key and its certificate chain (leaf first) from PEM text. Blocks are recognised
         * by their BEGIN line, so a file may hold the key, the certificates or both, in any order.
         */
        internal fun findIdentity(pem: Collection<String>): Pair<PrivateKey, List<X509Certificate>> {
            val blocks = pem.flatMap { text -> PEM_BLOCK.findAll(text).map { it.groupValues[1] to it.groupValues[2] } }
            blocks.firstOrNull { it.first == "RSA PRIVATE KEY" || it.first == "EC PRIVATE KEY" }?.let {
                throw GeneralSecurityException(
                    "The ${it.first} is in the old format: convert it with `openssl pkcs8 -topk8 -nocrypt`"
                )
            }
            if (blocks.any { it.first == "ENCRYPTED PRIVATE KEY" }) {
                throw GeneralSecurityException("The private key is password protected: export it without a password")
            }
            val factory = CertificateFactory.getInstance("X.509")
            val certificates = blocks.filter { it.first == "CERTIFICATE" }
                .map { factory.generateCertificate(base64(it.second).inputStream()) as X509Certificate }
            val keys = blocks.filter { it.first == "PRIVATE KEY" }.map { base64(it.second) }
            if (keys.isEmpty()) throw GeneralSecurityException("No private key (BEGIN PRIVATE KEY) among the PEM files")
            if (certificates.isEmpty()) throw GeneralSecurityException("No certificate (BEGIN CERTIFICATE) among the PEM files")

            val pairs = keys.flatMap { der ->
                certificates.mapNotNull { cert -> privateKeyFor(cert, der)?.let { it to cert } }
            }
            val (key, leaf) = pairs.singleOrNull() ?: throw GeneralSecurityException(
                if (pairs.isEmpty()) "No certificate matches the private key"
                else "${pairs.size} key and certificate pairs found: keep only one"
            )
            // The rest of the chain, each certificate followed by its issuer
            val chain = mutableListOf(leaf)
            while (true) {
                val last = chain.last()
                if (last.issuerX500Principal == last.subjectX500Principal) break
                chain += certificates.firstOrNull { it !in chain && it.subjectX500Principal == last.issuerX500Principal } ?: break
            }
            return key to chain
        }

        /** The key, when it is the private half of [cert]: proven by a signature the certificate verifies. */
        private fun privateKeyFor(cert: X509Certificate, der: ByteArray): PrivateKey? = runCatching {
            val algorithm = cert.publicKey.algorithm
            val key = KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der))
            val signature = if (algorithm == "EC") "SHA256withECDSA" else "SHA256with$algorithm"
            val probe = "betterauto".toByteArray()
            val signed = Signature.getInstance(signature).run { initSign(key); update(probe); sign() }
            val valid = Signature.getInstance(signature).run { initVerify(cert.publicKey); update(probe); verify(signed) }
            key.takeIf { valid }
        }.getOrNull()

        private fun base64(body: String): ByteArray = Base64.getMimeDecoder().decode(body.trim())

        /** O= of an RFC 2253 name such as "OU=09,O=Volkswagen,L=Wolfsburg". */
        internal fun organizationOf(name: String): String? =
            Regex("""(?:^|,)O=((?:\\.|[^,\\])+)""").find(name)?.groupValues?.get(1)
                ?.replace(Regex("""\\(.)"""), "$1")?.trim()?.ifEmpty { null }
    }
}
