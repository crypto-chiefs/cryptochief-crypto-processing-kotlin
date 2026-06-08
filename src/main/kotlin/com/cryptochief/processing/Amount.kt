package com.cryptochief.processing

import java.math.BigInteger

/** Conversion between human decimal strings and integer base units. */
public object Amount {

    /**
     * `Amount.toBase("1.5", 18)` → `1500000000000000000`.
     *
     * Negative values and scientific notation throw.
     * Sub-base-unit precision is truncated.
     */
    @Throws(IllegalArgumentException::class)
    public fun toBase(human: String, decimals: Int): BigInteger {
        require(decimals >= 0) { "decimals must be >= 0, got $decimals" }
        val trimmed = human.trim()
        require(trimmed.isNotEmpty()) { "amount is empty" }
        require(!trimmed.contains('e', ignoreCase = true)) {
            "scientific notation not allowed: \"$human\""
        }
        require(!trimmed.startsWith('-')) { "negative amount not allowed: \"$human\"" }

        val dot = trimmed.indexOf('.')
        val intPart: String
        var fracPart: String
        if (dot < 0) {
            intPart = trimmed
            fracPart = ""
        } else {
            intPart = trimmed.substring(0, dot).ifEmpty { "0" }
            fracPart = trimmed.substring(dot + 1)
            require(fracPart.isNotEmpty()) { "amount has trailing dot: \"$human\"" }
        }
        require(intPart.all { it in '0'..'9' }) { "invalid integer part: \"$intPart\"" }
        require(fracPart.all { it in '0'..'9' }) { "invalid fractional part: \"$fracPart\"" }

        fracPart = if (fracPart.length > decimals) {
            fracPart.substring(0, decimals)
        } else {
            fracPart.padEnd(decimals, '0')
        }
        val combined = (intPart + fracPart).trimStart('0').ifEmpty { "0" }
        return BigInteger(combined)
    }

    /** `Amount.fromBase(BigInteger("1500000000000000000"), 18)` → `"1.5"`. */
    public fun fromBase(base: BigInteger, decimals: Int): String {
        if (decimals <= 0) return base.abs().toString()
        val abs = base.abs().toString()
        val padded = abs.padStart(decimals + 1, '0')
        val cut = padded.length - decimals
        val intPart = padded.substring(0, cut)
        val fracPart = padded.substring(cut).trimEnd('0')
        return if (fracPart.isEmpty()) intPart else "$intPart.$fracPart"
    }

    /** Decimal nanoTON string for [humanTon] — `nanoTon("0.05")` → `"50000000"`. */
    public fun nanoTon(humanTon: String): String = toBase(humanTon, 9).toString()
}

public fun String.toBaseUnits(decimals: Int): BigInteger = Amount.toBase(this, decimals)

public fun BigInteger.toHumanString(decimals: Int): String = Amount.fromBase(this, decimals)
