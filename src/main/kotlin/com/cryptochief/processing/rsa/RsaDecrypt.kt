package com.cryptochief.processing.rsa

import com.cryptochief.processing.ConfigurationException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** PEM RSA private key loader. Accepts PKCS#1 and PKCS#8. */
public object RsaKeyLoader {

    @Throws(ConfigurationException::class)
    public fun loadPrivateKeyFromFile(path: String): RSAPrivateKey =
        loadPrivateKeyFromPem(Files.readString(Path.of(path)))

    @Throws(ConfigurationException::class)
    public fun loadPrivateKeyFromPem(pem: String): RSAPrivateKey {
        val trimmed = pem.trim()
        val header = """-----BEGIN ([A-Z ]+)-----""".toRegex().find(trimmed)
            ?: throw ConfigurationException("cryptochief: RSA key: no PEM header found")
        val label = header.groupValues[1].trim()
        val base64 = trimmed
            .substringAfter("-----BEGIN $label-----")
            .substringBefore("-----END $label-----")
            .replace("\\s".toRegex(), "")
        val raw = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw ConfigurationException("cryptochief: RSA key: bad base64", e)
        }
        val der = when (label.uppercase()) {
            "PRIVATE KEY" -> raw
            "RSA PRIVATE KEY" -> pkcs1ToPkcs8(raw)
            else -> throw ConfigurationException("cryptochief: RSA key: unexpected PEM label \"$label\"")
        }
        return try {
            KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
        } catch (e: Exception) {
            throw ConfigurationException("cryptochief: RSA key: not a valid RSA private key", e)
        }
    }

    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val algId = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00,
        )
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val privateKeyOctet = derOctetString(pkcs1)
        val body = version + algId + privateKeyOctet
        return derSequence(body)
    }

    private fun derSequence(content: ByteArray): ByteArray = byteArrayOf(0x30) + derLength(content.size) + content
    private fun derOctetString(content: ByteArray): ByteArray = byteArrayOf(0x04) + derLength(content.size) + content

    private fun derLength(len: Int): ByteArray {
        if (len < 0x80) return byteArrayOf(len.toByte())
        val bytes = mutableListOf<Byte>()
        var v = len
        while (v > 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }
}

/** RSA-OAEP / SHA-256 decryption. */
public object RsaDecrypt {

    @Throws(ConfigurationException::class)
    public fun oaepSha256(privateKey: RSAPrivateKey, base64Ciphertext: String): String {
        val ct = try {
            Base64.getDecoder().decode(base64Ciphertext)
        } catch (e: IllegalArgumentException) {
            throw ConfigurationException("cryptochief: RSA decrypt: bad base64", e)
        }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val params = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT,
        )
        try {
            cipher.init(Cipher.DECRYPT_MODE, privateKey, params)
            return cipher.doFinal(ct).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw ConfigurationException("cryptochief: RSA decrypt: ${e.message}", e)
        }
    }
}
