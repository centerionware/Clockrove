package io.github.centerionware.clockrove.tracking

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class InvoiceBillingPolicyTest {
    private val rate = BigInteger.valueOf(10_000)
    private val minute = BigInteger.valueOf(60_000)

    @Test fun minimumThenExactChargesOneHourAndKeepsAdditionalWorkExact() {
        val fiveMinutes = InvoiceBillingPolicy.hourlyCharge(minute * BigInteger.valueOf(5), rate,
            InvoiceBillingPolicy.MINIMUM_THEN_EXACT, "1")
        assertEquals(BigInteger.valueOf(10_000), fiveMinutes.amountMinor)
        assertEquals("1", fiveMinutes.billedHours)

        val hourAndMinute = InvoiceBillingPolicy.hourlyCharge(BigInteger.valueOf(3_660_000), rate,
            InvoiceBillingPolicy.MINIMUM_THEN_EXACT, "1")
        assertEquals(BigInteger.valueOf(10_167), hourAndMinute.amountMinor)
        assertEquals("1.016666667", hourAndMinute.billedHours)
    }

    @Test fun roundUpAppliesToTheAggregatedDurationAndHonorsLargerMinimum() {
        val oneHourOneMinute = InvoiceBillingPolicy.hourlyCharge(BigInteger.valueOf(3_660_000), rate,
            InvoiceBillingPolicy.ROUND_TOTAL_UP, "0")
        assertEquals(BigInteger.valueOf(20_000), oneHourOneMinute.amountMinor)
        assertEquals("2", oneHourOneMinute.billedHours)

        val underMinimum = InvoiceBillingPolicy.hourlyCharge(BigInteger.valueOf(3_600_000), rate,
            InvoiceBillingPolicy.ROUND_TOTAL_UP, "2.5")
        assertEquals(BigInteger.valueOf(25_000), underMinimum.amountMinor)
        assertEquals("2.5", underMinimum.billedHours)
    }

    @Test fun exactIntegerArithmeticHandlesLargeRatesAndTaskMinimums() {
        val wideRate = BigInteger("1234567890123456789012345678901234567890")
        val charge = InvoiceBillingPolicy.hourlyCharge(BigInteger.valueOf(3_600_000), wideRate,
            InvoiceBillingPolicy.MINIMUM_THEN_EXACT, "0")
        assertEquals(wideRate, charge.amountMinor)
        assertEquals(BigInteger.valueOf(500), InvoiceBillingPolicy.taskMinimumAdjustment(BigInteger.valueOf(2_000), BigInteger.valueOf(2_500)))
        assertEquals(BigInteger.ZERO, InvoiceBillingPolicy.taskMinimumAdjustment(BigInteger.valueOf(3_000), BigInteger.valueOf(2_500)))
    }

    @Test fun minimumHoursRejectsMalformedOrExcessivelyPreciseInput() {
        assertTrue(InvoiceBillingPolicy.validHours("0"))
        assertTrue(InvoiceBillingPolicy.validHours("1.250000"))
        assertFalse(InvoiceBillingPolicy.validHours("1e2"))
        assertFalse(InvoiceBillingPolicy.validHours("-1"))
        assertFalse(InvoiceBillingPolicy.validHours("1.0000001"))
        assertEquals("1000", InvoiceBillingPolicy.canonicalHours("1000.000"))
    }
}
