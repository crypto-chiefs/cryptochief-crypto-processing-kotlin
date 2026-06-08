package com.cryptochief.processing

import com.cryptochief.processing.solana.Anchor
import com.cryptochief.processing.solana.Borsh
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class AnchorTest {

    @Test
    fun `discriminator matches sha256 prefix`() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("global:initialize".toByteArray())
            .copyOfRange(0, 8)
        assertEquals(
            expected.toList(),
            Anchor.discriminator("initialize").toList(),
        )
    }

    @Test
    fun `instruction is discriminator plus borsh args`() {
        val data = Anchor.encodeInstruction(
            "transfer",
            Borsh.u64(1_000_000L),
        )
        assertEquals(
            Anchor.discriminator("transfer").toList(),
            data.copyOfRange(0, 8).toList(),
        )
        assertEquals(
            listOf<Byte>(
                0x40, 0x42, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            data.copyOfRange(8, 16).toList(),
        )
    }

    @Test
    fun `borsh string layout`() {
        val out = Borsh.string("hi").encode()
        assertEquals(
            listOf<Byte>(0x02, 0x00, 0x00, 0x00, 0x68, 0x69),
            out.toList(),
        )
    }
}
