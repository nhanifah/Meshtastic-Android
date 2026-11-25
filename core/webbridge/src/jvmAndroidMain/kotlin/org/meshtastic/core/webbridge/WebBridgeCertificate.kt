/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.webbridge

import okio.ByteString.Companion.toByteString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** A server identity ready to serve TLS, plus the fingerprint the phone shows so a person can compare it. */
class WebBridgeIdentity internal constructor(val sslContext: SSLContext, val certificate: X509Certificate) {
    /** SHA-256 of the DER certificate as colon-separated upper-case hex, the format browsers display. */
    val sha256Fingerprint: String =
        certificate.encoded.toByteString().sha256().hex().uppercase().chunked(2).joinToString(":")
}

/**
 * Issues a self-signed P-256 server certificate whose subject alternative names are the addresses the bridge listens
 * on. Browsers match HTTPS by SAN, so the certificate is reissued whenever the phone's Wi-Fi address changes.
 *
 * BouncyCastle is used only as a certificate builder, held as a private provider instance and never registered, for the
 * same reason as `TakCertificateGenerator`: Android ships a stripped provider under the "BC" name.
 */
internal object WebBridgeCertificate {
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val CURVE = "secp256r1"
    private const val SERIAL_BITS = 128
    private const val NOT_BEFORE_SKEW_DAYS = 1L

    /** Browsers reject leaf certificates valid for longer than 398 days, even self-signed ones in some versions. */
    private const val VALIDITY_DAYS = 397L

    private val provider = BouncyCastleProvider()

    fun issue(ipAddresses: List<String>, random: SecureRandom = SecureRandom()): WebBridgeIdentity {
        require(ipAddresses.isNotEmpty()) { "At least one listen address is required" }
        val keys =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE), random) }.generateKeyPair()
        val name = X500Name("CN=Meshtastic Web Access,O=Meshtastic")
        val now = System.currentTimeMillis()
        val builder =
            JcaX509v3CertificateBuilder(
                name,
                BigInteger(SERIAL_BITS, random).setBit(0),
                Date(now - TimeUnit.DAYS.toMillis(NOT_BEFORE_SKEW_DAYS)),
                Date(now + TimeUnit.DAYS.toMillis(VALIDITY_DAYS)),
                name,
                keys.public,
            )
                .apply {
                    addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                    addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                    addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                    addExtension(
                        Extension.subjectAlternativeName,
                        false,
                        GeneralNames(ipAddresses.map { GeneralName(GeneralName.iPAddress, it) }.toTypedArray()),
                    )
                }
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(provider).build(keys.private)
        // Parsed by the platform's own factory, so the TLS stack (Conscrypt on Android) gets a certificate type it
        // knows.
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        // Hand the key to TLS directly instead of through a KeyStore. The default KeyStore type is PKCS12 on the JVM
        // but
        // BKS on Android, whose trimmed BouncyCastle may fail to decode an EC key; the key manager would then skip it
        // and
        // every browser would get a TLS error while the screen showed Running. In memory only: the key never touches
        // disk.
        val keyManager = SingleIdentityKeyManager(keys.private, arrayOf(certificate))
        val sslContext = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), null, random) }
        return WebBridgeIdentity(sslContext, certificate)
    }
}

/** Serves exactly one EC identity, for server handshakes only. */
internal class SingleIdentityKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) :
    X509ExtendedKeyManager() {

    // TLS 1.2 asks per cipher suite ("EC", "EC_EC", "RSA", ...); answering only for EC steers it to ECDSA suites.
    private fun aliasFor(keyType: String?): String? = ALIAS.takeIf { keyType == null || keyType.startsWith("EC") }

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
        aliasFor(keyType)

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        aliasFor(keyType)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        aliasFor(keyType)?.let { arrayOf(it) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = chain.clone().takeIf { alias == ALIAS }

    override fun getPrivateKey(alias: String?): PrivateKey? = key.takeIf { alias == ALIAS }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    private companion object {
        const val ALIAS = "meshtastic-web-bridge"
    }
}
