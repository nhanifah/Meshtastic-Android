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
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

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
    private const val ALIAS = "meshtastic-web-bridge"
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
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))

        // In-memory only: the key never touches disk, and a password is required by the KeyStore API but protects
        // nothing here.
        val password = CharArray(0)
        val keyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry(ALIAS, keys.private, password, arrayOf(certificate))
            }
        val keyManagers =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, password) }
                .keyManagers
        val sslContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, random) }
        return WebBridgeIdentity(sslContext, certificate)
    }
}
