package com.cryptochief.processing

import com.cryptochief.processing.rsa.RsaDecrypt
import com.cryptochief.processing.rsa.RsaKeyLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class RsaDecryptTest {

    private fun newKeyPair(): Pair<RSAPrivateKey, RSAPublicKey> {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        return (kp.private as RSAPrivateKey) to (kp.public as RSAPublicKey)
    }

    @Test
    fun `oaep sha256 round trip`() {
        val (priv, pub) = newKeyPair()
        val plaintext = "0x" + "ab".repeat(32)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val params = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT,
        )
        cipher.init(Cipher.ENCRYPT_MODE, pub, params)
        val ct = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray()))
        assertEquals(plaintext, RsaDecrypt.oaepSha256(priv, ct))
    }

    @Test
    fun `loader parses PKCS8 PEM`() {
        val (priv, _) = newKeyPair()
        val pkcs8 = Base64.getEncoder().encodeToString(priv.encoded)
        val pem = buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            pkcs8.chunked(64).forEach { append(it).append('\n') }
            append("-----END PRIVATE KEY-----\n")
        }
        val parsed = RsaKeyLoader.loadPrivateKeyFromPem(pem)
        assertEquals(priv.modulus, parsed.modulus)
    }
}
