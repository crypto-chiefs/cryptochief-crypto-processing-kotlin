package com.cryptochief.processing

import com.cryptochief.processing.http.CanonicalJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalJsonTest {

    @Serializable
    private data class Sample(
        @SerialName("zeta") val zeta: String,
        @SerialName("alpha") val alpha: Int,
        @SerialName("nested") val nested: Nested,
    )

    @Serializable
    private data class Nested(
        @SerialName("zoo") val zoo: List<Int>,
        @SerialName("apple") val apple: String,
    )

    @Test
    fun `keys are sorted recursively`() {
        val bytes = CanonicalJson.encode(
            Sample(zeta = "z", alpha = 1, nested = Nested(zoo = listOf(3, 2, 1), apple = "a")),
        )
        val text = bytes.toString(Charsets.UTF_8)
        assertEquals("""{"alpha":1,"nested":{"apple":"a","zoo":[3,2,1]},"zeta":"z"}""", text)
    }

    @Test
    fun `null payload returns empty bytes`() {
        val bytes = CanonicalJson.encode<JsonElement?>(null)
        assertEquals(0, bytes.size)
    }

    @Test
    fun `empty object stays as empty object`() {
        val bytes = CanonicalJson.encode(kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals("{}", bytes.toString(Charsets.UTF_8))
    }
}
