package io.github.centerionware.clockrove.tracking

import java.math.BigDecimal
import java.math.BigInteger

/** A unit is 0.0001 percentage point: 7.5% is 75000, not a binary float. */
internal object TaxRate {
    const val maximum = 1_000_000
    private val denominator = BigInteger.valueOf(maximum.toLong())
    fun valid(units: Int) = units in 0..maximum
    fun parse(input: String): Int {
        val value = input.trim()
        require(value.length in 1..32 && value.matches(Regex("[0-9]+(\\.[0-9]{1,4})?"))) { "INVALID_TAX_RATE" }
        val units = BigDecimal(value).movePointRight(4).toBigIntegerExact()
        require(units.signum() >= 0 && units <= denominator) { "INVALID_TAX_RATE" }
        return units.toInt()
    }
    fun format(units: Int): String {
        require(valid(units)) { "INVALID_TAX_RATE" }
        return BigDecimal.valueOf(units.toLong(), 4).stripTrailingZeros().toPlainString()
    }
    fun amount(subtotalMinor: BigInteger, units: Int): BigInteger {
        require(subtotalMinor.signum() >= 0 && valid(units)) { "INVALID_TAX_RATE" }
        return subtotalMinor.multiply(BigInteger.valueOf(units.toLong()))
            .add(BigInteger.valueOf(500_000)).divide(denominator)
    }
}
