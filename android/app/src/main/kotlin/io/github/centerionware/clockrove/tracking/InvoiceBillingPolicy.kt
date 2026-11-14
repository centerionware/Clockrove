package io.github.centerionware.clockrove.tracking

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Invoice-only billing rules. Session durations and report intervals remain exact. */
internal object InvoiceBillingPolicy {
    const val MINIMUM_THEN_EXACT = "MINIMUM_THEN_EXACT"
    const val ROUND_TOTAL_UP = "ROUND_TOTAL_UP"
    private val hourMillis = BigInteger.valueOf(3_600_000L)
    private val hoursPattern = Regex("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,6})?")

    data class HourlyCharge(val amountMinor: BigInteger, val billedHours: String)

    fun validMode(value: String) = value == MINIMUM_THEN_EXACT || value == ROUND_TOTAL_UP

    fun validHours(value: String): Boolean = value.length <= 20 && hoursPattern.matches(value) &&
        try { BigDecimal(value) <= BigDecimal("999999999") } catch (_: NumberFormatException) { false }

    fun canonicalHours(value: String): String {
        require(validHours(value)) { "INVALID_MINIMUM_HOURS" }
        return BigDecimal(value).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0).toPlainString() else it.toPlainString() }
    }

    fun hourlyCharge(durationMillis: BigInteger, rateMinor: BigInteger, mode: String, minimumHours: String): HourlyCharge {
        require(durationMillis.signum() >= 0 && rateMinor.signum() >= 0 && validMode(mode) && validHours(minimumHours))
        val minimum = BigDecimal(minimumHours)
        val minimumNumerator = minimum.unscaledValue()
        val minimumDenominator = BigInteger.TEN.pow(minimum.scale())
        val actualAtLeastMinimum = durationMillis.multiply(minimumDenominator) >= minimumNumerator.multiply(hourMillis)
        if (mode == MINIMUM_THEN_EXACT) {
            return if (actualAtLeastMinimum) {
                HourlyCharge(roundHalfUp(durationMillis.multiply(rateMinor), hourMillis), formatHours(durationMillis, hourMillis))
            } else {
                HourlyCharge(roundHalfUp(minimumNumerator.multiply(rateMinor), minimumDenominator), canonicalHours(minimumHours))
            }
        }

        val roundedHours = durationMillis.add(hourMillis).subtract(BigInteger.ONE).divide(hourMillis)
        val roundedAtLeastMinimum = roundedHours.multiply(minimumDenominator) >= minimumNumerator
        return if (roundedAtLeastMinimum) {
            HourlyCharge(roundedHours.multiply(rateMinor), roundedHours.toString())
        } else {
            HourlyCharge(roundHalfUp(minimumNumerator.multiply(rateMinor), minimumDenominator), canonicalHours(minimumHours))
        }
    }

    fun taskMinimumAdjustment(subtotalMinor: BigInteger, minimumMinor: BigInteger): BigInteger {
        require(subtotalMinor.signum() >= 0 && minimumMinor.signum() >= 0)
        return (minimumMinor - subtotalMinor).max(BigInteger.ZERO)
    }

    private fun roundHalfUp(numerator: BigInteger, denominator: BigInteger): BigInteger =
        numerator.add(denominator.divide(BigInteger.valueOf(2))).divide(denominator)

    private fun formatHours(durationMillis: BigInteger, hour: BigInteger): String =
        BigDecimal(durationMillis).divide(BigDecimal(hour), 9, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
