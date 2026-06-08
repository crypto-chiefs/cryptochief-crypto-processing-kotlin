package com.cryptochief.processing.http

import java.security.MessageDigest
import java.util.Base64

/** `signature = hex(md5(base64(canonicalJson(body)) + apiKey))`. */
public object RequestSigner {

    public fun sign(canonicalBody: ByteArray, apiKey: String): String {
        require(apiKey.isNotEmpty()) { "API key is required" }
        val b64 = Base64.getEncoder().encodeToString(canonicalBody)
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(b64.toByteArray(Charsets.UTF_8))
        md5.update(apiKey.toByteArray(Charsets.UTF_8))
        return md5.digest().toHexLower()
    }

    private fun ByteArray.toHexLower(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
