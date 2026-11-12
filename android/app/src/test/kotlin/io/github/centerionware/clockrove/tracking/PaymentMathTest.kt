package io.github.centerionware.clockrove.tracking

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class PaymentMathTest {
    private fun n(value: Long) = BigInteger.valueOf(value)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid amount must be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun frozenDecimalAmountsParseWithoutInputRoundingOrExponentExpansion() {
        assertEquals(n(12345), PaymentMath.parseMajor("123.45", 2))
        assertEquals(n(12300), PaymentMath.parseMajor("123", 2))
        assertEquals(n(12), PaymentMath.parseMajor("0.0012", 4))
        assertEquals(BigInteger.ZERO, PaymentMath.parseMajor("0.00", 2))
        assertEquals(n(123), PaymentMath.parseMajor("123", 0))
        listOf("1.001", "1.000", "1e2", "NaN", "Infinity", "-1", "+1", "01", ".1", "1.", " 1", "1 ", "١", "1,00").forEach { value ->
            rejected { PaymentMath.parseMajor(value, 2) }
        }
        rejected { PaymentMath.parseMajor("1.0", 0) }
        rejected { PaymentMath.parseMajor("1", 5) }
    }
    @Test fun extendedInvoiceAndPartialPaymentsExceedSourcePriceAndMachineIntegerLimits() {
        val total = BigInteger("9".repeat(1050))
        assertEquals(total, PaymentMath.parseMinor(total.toString()))
        val first = total.divide(n(3))
        val partial = PaymentMath.receive(total, BigInteger.ZERO, first)
        assertEquals("PARTIALLY_PAID", partial.status)
        assertEquals(total.subtract(first), partial.due)
        val settled = PaymentMath.receive(total, partial.paid, partial.due)
        assertEquals("PAID", settled.status)
        assertEquals(BigInteger.ZERO, settled.due)
        assertEquals(total, settled.paid)
        assertEquals(total, PaymentMath.parseMajor(total.toString() + ".00", 2).divide(n(100)))
    }
    @Test fun canonicalPaymentBoundsRejectMalformedAndOversizeWithoutNarrowing() {
        val maximum = "9".repeat(PaymentMath.maxDigits)
        assertEquals(maximum, PaymentMath.parseMinor(maximum).toString())
        listOf("", "00", "01", "+1", "-1", "1.0", "1e3", " 1", "１", maximum + "0").forEach { value ->
            rejected { PaymentMath.parseMinor(value) }
        }
        rejected { PaymentMath.parseMajor(maximum + ".00", 2) }
    }
    @Test fun zeroOverpaymentNegativeAndInvalidExistingBalancesAreExplicit() {
        assertEquals("NOTHING_DUE", PaymentMath.balance(n(0), n(0)).status)
        assertEquals("UNPAID", PaymentMath.balance(n(100), n(0)).status)
        rejected { PaymentMath.receive(n(0), n(0), n(1)) }
        rejected { PaymentMath.receive(n(100), n(20), n(81)) }
        rejected { PaymentMath.receive(n(100), n(20), n(0)) }
        rejected { PaymentMath.receive(n(100), n(20), n(-1)) }
        rejected { PaymentMath.balance(n(-1), n(0)) }
        rejected { PaymentMath.balance(n(100), n(-1)) }
        rejected { PaymentMath.balance(n(100), n(101)) }
    }
    @Test fun cancelledBalanceCannotExposeCollectibleDebtWhileOriginalTotalRemainsHistorical() {
        val cancelled = PaymentMath.balance(n(10750), n(0)).copy(voided = true)
        assertEquals("VOID", cancelled.status); assertEquals(n(0), cancelled.due)
        assertEquals(n(10750), cancelled.total); assertEquals(n(0), cancelled.paid)
        assertEquals(n(10750), PaymentMath.balance(n(10750), n(0)).due)
    }
    @Test fun tinySplitPaymentsConserveTaxAndSalesAtEveryStepAndFinalSettlement() {
        // Independently rounding each 1-cent payment would lose all three cents of tax.
        var paid = n(0); var taxBooked = n(0); var salesBooked = n(0)
        repeat(10) {
            val next = PaymentMath.receive(n(10), paid, n(1)).paid
            val tax = requireNotNull(PaymentMath.taxChange(n(10), n(3), paid, next))
            assertTrue(tax >= n(0) && tax <= n(1))
            taxBooked = taxBooked.add(tax); salesBooked = salesBooked.add(n(1).subtract(tax)); paid = next
            assertEquals(paid, taxBooked.add(salesBooked))
        }
        assertEquals(n(3), taxBooked); assertEquals(n(7), salesBooked)
        assertEquals(n(1), PaymentMath.collectedTax(n(2), n(1), n(1)))
    }
    @Test fun reversalsAndReplacementsReclassifyTaxWithoutInventingCash() {
        val paid = n(30000)
        val originalTax = requireNotNull(PaymentMath.collectedTax(n(100000), n(7500), paid))
        val replacementTax = requireNotNull(PaymentMath.collectedTax(n(120000), n(12000), paid))
        val reclassification = replacementTax.subtract(originalTax)
        assertEquals(n(750), reclassification)
        assertEquals(paid, replacementTax.add(paid.subtract(replacementTax)))
        val reversal = requireNotNull(PaymentMath.taxChange(n(120000), n(12000), paid, n(20000)))
        assertEquals(n(-1000), reversal)
        assertEquals(n(2000), replacementTax.add(reversal))
        val cashReversed = n(-10000)
        val salesReversed = cashReversed.subtract(reversal)
        assertEquals(n(-9000), salesReversed)
        assertEquals(cashReversed, salesReversed.add(reversal))
        assertNull(PaymentMath.taxChange(n(100000), null, n(0), paid))
        assertEquals(n(0), PaymentMath.collectedTax(n(0), n(0), n(0)))
        rejected { PaymentMath.collectedTax(n(100), n(101), n(50)) }
    }
    @Test fun wideTaxAllocationRemainsExactAcrossUnevenPaymentsAndFullReversal() {
        val total = BigInteger("1234567890".repeat(105))
        val tax = total.multiply(n(3)).divide(n(17))
        val first = total.divide(n(7)); val second = total.divide(n(11))
        val firstTax = requireNotNull(PaymentMath.taxChange(total, tax, n(0), first))
        val secondTax = requireNotNull(PaymentMath.taxChange(total, tax, first, first.add(second)))
        val finalTax = requireNotNull(PaymentMath.taxChange(total, tax, first.add(second), total))
        assertEquals(tax, firstTax.add(secondTax).add(finalTax))
        assertEquals(tax.negate(), PaymentMath.taxChange(total, tax, total, n(0)))
    }
}
