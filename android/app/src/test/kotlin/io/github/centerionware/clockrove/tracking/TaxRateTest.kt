package io.github.centerionware.clockrove.tracking

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class TaxRateTest {
    @Test fun percentInputHasExactUnitsAndStableDisplay() {
        for ((text, units) in listOf("0" to 0, "7.5" to 75000, "0.0001" to 1, "100.0000" to 1000000, " 07.5000 " to 75000)) {
            assertEquals(units, TaxRate.parse(text))
            assertEquals(units, TaxRate.parse(TaxRate.format(units)))
        }
        assertEquals("7.5", TaxRate.format(75000))
    }
    @Test fun invalidInputCannotBecomeANegativeFractionalOrUnboundedRate() {
        for (text in listOf("", " ", "-1", "+1", ".5", "7.", "7,5", "1e2", "NaN", "Infinity", "100.0001", "0.00001", "99999999999999999999999999999999999999")) {
            try { TaxRate.parse(text); fail("Accepted $text") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun subtotalIsTaxedOnceAndHalfMinorUnitsRoundUp() {
        assertEquals(BigInteger.valueOf(750), TaxRate.amount(BigInteger.valueOf(10000), 75000))
        assertEquals(BigInteger.ONE, TaxRate.amount(BigInteger.valueOf(5), 100000))
        assertEquals(BigInteger.ZERO, TaxRate.amount(BigInteger.valueOf(4), 100000))
        assertEquals(BigInteger.ZERO, TaxRate.amount(BigInteger.ZERO, 1000000))
    }
    @Test fun largeCurrencyTotalsNeverOverflowLongDuringMultiplication() {
        val amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(5000))
        assertEquals(amount, TaxRate.amount(amount, 1000000))
        assertEquals(BigInteger.ZERO, TaxRate.amount(amount, 0))
    }
    @Test fun nativeCalculationRejectsMalformedStoredRatesAndNegativeSubtotals() {
        for (rate in listOf(-1, 1000001)) {
            try { TaxRate.amount(BigInteger.TEN, rate); fail("Accepted rate") } catch (_: IllegalArgumentException) {}
        }
        try { TaxRate.amount(BigInteger.valueOf(-1), 75000); fail("Accepted negative subtotal") } catch (_: IllegalArgumentException) {}
    }
}
