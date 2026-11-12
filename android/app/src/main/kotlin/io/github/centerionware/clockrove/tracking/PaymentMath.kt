package io.github.centerionware.clockrove.tracking

import java.math.BigInteger

/** Exact document balances. Source-price text limits must not truncate an extended invoice total. */
internal object PaymentMath {
    const val maxDigits = 4096
    private val canonical = Regex("0|[1-9][0-9]*")
    private val major = Regex("(0|[1-9][0-9]*)(?:\\.([0-9]+))?")
    private val two = BigInteger.valueOf(2)

    fun parseMinor(value: String): BigInteger {
        require(value.length in 1..maxDigits && canonical.matches(value)) { "INVALID_PAYMENT_AMOUNT" }
        return BigInteger(value)
    }

    /** Frozen invoice amounts are plain decimal text, never floating-point values. */
    fun parseMajor(value: String, digits: Int): BigInteger {
        require(digits in 0..4 && value.length in 1..maxDigits + 5) { "INVALID_DOCUMENT_AMOUNT" }
        val match = major.matchEntire(value) ?: throw IllegalArgumentException("INVALID_DOCUMENT_AMOUNT")
        val fraction = match.groupValues[2]
        require(fraction.length <= digits) { "INVALID_DOCUMENT_AMOUNT" }
        val minor = (match.groupValues[1] + fraction.padEnd(digits, '0')).trimStart('0').ifEmpty { "0" }
        return parseMinor(minor)
    }

    data class Balance(val total: BigInteger, val paid: BigInteger, private val unallocated: BigInteger, val voided: Boolean = false) {
        // The original total remains historical. A cancelled invoice is not collectible;
        // its credited/cancelled split is retained in the immutable lifecycle record.
        val due: BigInteger get() = if (voided) BigInteger.ZERO else unallocated
        val status: String get() = when {
            voided -> "VOID"
            total.signum() == 0 -> "NOTHING_DUE"
            due.signum() == 0 -> "PAID"
            paid.signum() == 0 -> "UNPAID"
            else -> "PARTIALLY_PAID"
        }
    }

    fun balance(total: BigInteger, paid: BigInteger): Balance {
        require(total.signum() >= 0 && paid.signum() >= 0 && paid <= total) { "INVALID_PAYMENT_BALANCE" }
        return Balance(total, paid, total.subtract(paid))
    }

    fun receive(total: BigInteger, paid: BigInteger, amount: BigInteger): Balance {
        val before = balance(total, paid)
        require(amount.signum() > 0 && amount <= before.due) { "PAYMENT_EXCEEDS_BALANCE" }
        return balance(total, paid.add(amount))
    }

    /**
     * Half-up allocation on the cumulative paid balance, not independently per payment.
     * Each event books the difference between cumulative allocations. Thus split payments,
     * reversals and final settlement conserve every tax and net-sales minor unit.
     * Legacy unknown tax stays null; it must not be silently represented as zero.
     */
    fun collectedTax(total: BigInteger, tax: BigInteger?, paid: BigInteger): BigInteger? {
        balance(total, paid)
        if (tax == null) return null
        require(tax.signum() >= 0 && tax <= total) { "INVALID_DOCUMENT_TAX" }
        if (total.signum() == 0) return BigInteger.ZERO
        return paid.multiply(tax).multiply(two).add(total).divide(total.multiply(two))
    }

    fun taxChange(total: BigInteger, tax: BigInteger?, beforePaid: BigInteger, afterPaid: BigInteger): BigInteger? {
        val before = collectedTax(total, tax, beforePaid)
        val after = collectedTax(total, tax, afterPaid)
        return if (before == null || after == null) null else after.subtract(before)
    }
}
