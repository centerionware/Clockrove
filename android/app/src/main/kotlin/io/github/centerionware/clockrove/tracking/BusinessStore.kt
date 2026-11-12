package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.util.Currency
import java.math.BigInteger

/** Business data is mutated only in TimerRepository's serialized transaction. */
internal object BusinessStore {
    data class Settings(val currency: String, val defaultHourlyMinor: BigInteger? = null,
        val companyName: String = "", val companyEmail: String = "", val companyPhone: String = "",
        val companyAddress: String = "", val companyWebsite: String = "", val invoiceHeader: String = "",
        val invoiceFooter: String = "", val invoiceStyle: String = "MODERN",
        val emailSubject: String = "Invoice {invoice_number} — {job_name}",
        val emailBody: String = "Hello {customer_name},\n\nPlease find invoice {invoice_number} attached.\nAmount: {amount} {currency}\n\n{company_name}",
        val defaultTaxUnits: Int = 0, val receivablesEmail: String = "") {
        fun storedValues(): List<Any?> = listOf(currency, MoneyMath.legacy(defaultHourlyMinor), companyName, companyEmail, companyPhone,
            companyAddress, companyWebsite, invoiceHeader, invoiceFooter, invoiceStyle, emailSubject, emailBody)
        // Retain released command fingerprints when the new preference is zero.
        fun values(): List<Any?> = storedValues().toMutableList().apply { this[1] = MoneyMath.fingerprint(defaultHourlyMinor) } + (if (defaultTaxUnits == 0) emptyList() else listOf(defaultTaxUnits)) +
            (if (receivablesEmail.isEmpty()) emptyList() else listOf(receivablesEmail))
    }
    data class Customer(val id: String, val name: String, val email: String = "", val phone: String = "",
        val address: String = "", val notes: String = "", val archived: Boolean = false) {
        fun values(): List<Any?> = listOf(id, name, email, phone, address, notes, archived)
    }
    data class JobSettings(val id: String, val name: String, val colorArgb: Long, val billingMode: String,
        val currency: String, val hourlyRateMinor: BigInteger?, val customerId: String?, val customerEmailOverride: String?, val taxUnits: Int? = null) {
        fun values(): List<Any?> = listOf(id, name, colorArgb, billingMode, currency, MoneyMath.fingerprint(hourlyRateMinor), customerId, customerEmailOverride) +
            if (taxUnits == null) emptyList() else listOf(taxUnits)
    }
    data class JobDetail(val settings: JobSettings, val archived: Boolean, val archivedMillis: Long?,
        val customer: Customer?, val effectiveEmail: String, val effectiveHourlyMinor: BigInteger?, val effectiveTaxUnits: Int = 0)
    val customerColumns = listOf("id", "name", "email", "phone", "address", "notes", "archived")

    fun settings(db: SQLiteDatabase): Settings = db.rawQuery("SELECT ${BusinessSchema.settingsColumns.drop(1).take(12).joinToString(",")} FROM app_settings WHERE id=1", null).use {
        check(it.moveToFirst())
        Settings(it.getString(0), MoneyStore.read(db, "settings_money", "1", if (it.isNull(1)) null else it.getString(1)), it.getString(2), it.getString(3),
            it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getString(8), it.getString(9), it.getString(10), it.getString(11), BillingTaxStore.defaultUnits(db), InvoicePaymentStore.accountingEmail(db))
    }
    fun customers(db: SQLiteDatabase): List<Customer> = db.rawQuery("SELECT ${customerColumns.joinToString(",")} FROM customers ORDER BY archived,name,id", null).use { rows ->
        buildList { while (rows.moveToNext()) add(Customer(rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getInt(6) != 0)) }
    }
    fun job(db: SQLiteDatabase, id: String): JobDetail = db.rawQuery("SELECT name,color_argb,billing_mode,currency,hourly_rate_minor,customer_id,customer_email_override,archived,archived_ms FROM jobs WHERE id=?", arrayOf(id)).use {
        require(it.moveToFirst()) { "JOB_UNAVAILABLE" }
        val value = JobSettings(id, it.getString(0), it.getLong(1), it.getString(2), it.getString(3),
            MoneyStore.read(db, "job_money", id, if (it.isNull(4)) null else it.getString(4)), if (it.isNull(5)) null else it.getString(5), if (it.isNull(6)) null else it.getString(6), BillingTaxStore.jobUnits(db, id))
        val customer = value.customerId?.let { customerId -> customers(db).firstOrNull { c -> c.id == customerId } }
        val defaults = settings(db)
        JobDetail(value, it.getInt(7) != 0, if (it.isNull(8)) null else it.getLong(8), customer,
            value.customerEmailOverride ?: customer?.email.orEmpty(),
            value.hourlyRateMinor ?: defaults.defaultHourlyMinor.takeIf { defaults.currency == value.currency }, value.taxUnits ?: defaults.defaultTaxUnits)
    }
    fun validCurrency(value: String): Boolean = value.matches(Regex("[A-Z]{3}")) && try {
        Currency.getInstance(value).defaultFractionDigits in 0..4
    } catch (_: IllegalArgumentException) { false }
    fun validEmail(value: String): Boolean = value.isEmpty() || (value.length <= 254 &&
        !value.any { it.isWhitespace() || it.isISOControl() } && android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches())
    private fun bounded(value: String, size: Int) = value.length <= size && !value.contains('\u0000')

    fun saveSettings(db: SQLiteDatabase, value: Settings, at: Long): String? {
        if (!validEmail(value.receivablesEmail)) return "INVALID_ACCOUNTS_RECEIVABLE_EMAIL"
        if (!TaxRate.valid(value.defaultTaxUnits)) return "INVALID_TAX_RATE"
        if (!validCurrency(value.currency) || !MoneyMath.valid(value.defaultHourlyMinor) ||
            !bounded(value.companyName, 240) || !validEmail(value.companyEmail) || !bounded(value.companyPhone, 120) ||
            !bounded(value.companyAddress, 4000) || !bounded(value.companyWebsite, 2000) ||
            !bounded(value.invoiceHeader, 4000) || !bounded(value.invoiceFooter, 4000) ||
            value.invoiceStyle !in setOf("MINIMAL", "CLASSIC", "MODERN")) return "INVALID_SETTINGS"
        if (!InvoiceEmailTemplate.valid(value.emailSubject, subject = true)) return "INVALID_EMAIL_SUBJECT"
        if (!InvoiceEmailTemplate.valid(value.emailBody, subject = false)) return "INVALID_EMAIL_BODY"
        val columns = BusinessSchema.settingsColumns.drop(1).take(12)
        // Existing jobs keep their own currency; a new default never converts existing money.
        db.execSQL("UPDATE app_settings SET ${columns.joinToString(",") { "$it=?" }} WHERE id=1", value.storedValues().toTypedArray())
        MoneyStore.save(db, "settings_money", "1", null, value.defaultHourlyMinor, at)
        BillingTaxStore.saveDefault(db, value.defaultTaxUnits, at)
        InvoicePaymentStore.saveAccountingEmail(db, value.receivablesEmail, at)
        RevisionStore.snapshotRows(db, "app_settings", "settings", BusinessSchema.settingsColumns, at)
        return null
    }
    fun saveCustomer(db: SQLiteDatabase, value: Customer, at: Long): String? {
        if (value.id.isBlank() || value.name.isBlank() || !bounded(value.name, 240) || !validEmail(value.email) ||
            !bounded(value.phone, 120) || !bounded(value.address, 4000) || !bounded(value.notes, 20000)) return "INVALID_CUSTOMER"
        val exists = db.rawQuery("SELECT id FROM customers WHERE id=?", arrayOf(value.id)).use { it.moveToFirst() }
        if (exists) db.execSQL("UPDATE customers SET name=?,email=?,phone=?,address=?,notes=?,archived=? WHERE id=?",
            arrayOf<Any?>(value.name.trim(), value.email, value.phone, value.address, value.notes, if (value.archived) 1 else 0, value.id))
        else db.execSQL("INSERT INTO customers VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(value.id, value.name.trim(), value.email, value.phone, value.address, value.notes, if (value.archived) 1 else 0))
        RevisionStore.snapshotRows(db, "customers", "customer", customerColumns, at, where = "id=?", args = arrayOf(value.id))
        return null
    }
    fun removeCustomer(db: SQLiteDatabase, id: String, at: Long): String? {
        if (customers(db).none { it.id == id }) return "CUSTOMER_UNAVAILABLE"
        val used = db.rawQuery("SELECT id FROM jobs WHERE customer_id=? UNION ALL SELECT id FROM invoices WHERE customer_id=? LIMIT 1", arrayOf(id, id)).use { it.moveToFirst() }
        if (used) return "CUSTOMER_IN_USE"
        db.execSQL("DELETE FROM customers WHERE id=?", arrayOf(id))
        db.execSQL("UPDATE entity_revisions SET payload=NULL WHERE kind='customer' AND entity_id=?", arrayOf(id))
        RevisionStore.record(db, "customer", id, null, mapOf("id" to id), at, deleted = true)
        OpaqueReceipt.purgePayloads(db)
        return null
    }
    fun saveJob(db: SQLiteDatabase, value: JobSettings, at: Long): String? {
        if (value.taxUnits != null && !TaxRate.valid(value.taxUnits)) return "INVALID_TAX_RATE"
        val previous = db.rawQuery("SELECT archived,currency,customer_id FROM jobs WHERE id=?", arrayOf(value.id)).use {
            if (!it.moveToFirst()) return "JOB_UNAVAILABLE"
            Triple(it.getInt(0) != 0, it.getString(1), if (it.isNull(2)) null else it.getString(2))
        }
        if (previous.first) return "JOB_ARCHIVED"
        if (value.name.isBlank() || !bounded(value.name, 240) || value.colorArgb !in 0..0xffffffffL ||
            value.billingMode !in setOf("HOURLY", "TASK") || !MoneyMath.valid(value.hourlyRateMinor) ||
            (value.customerEmailOverride != null && !validEmail(value.customerEmailOverride))) return "INVALID_JOB"
        // Job currency is fixed when created. Changing the global default affects new jobs only.
        if (value.currency != previous.second) return "CURRENCY_IMMUTABLE"
        if (value.customerId != null) {
            val customer = customers(db).firstOrNull { it.id == value.customerId } ?: return "CUSTOMER_UNAVAILABLE"
            if (customer.archived && value.customerId != previous.third) return "CUSTOMER_ARCHIVED"
        }
        db.execSQL("UPDATE jobs SET name=?,color_argb=?,billing_mode=?,hourly_rate_minor=?,customer_id=?,customer_email_override=? WHERE id=?",
            arrayOf<Any?>(value.name.trim(), value.colorArgb, value.billingMode, MoneyMath.legacy(value.hourlyRateMinor), value.customerId, value.customerEmailOverride, value.id))
        MoneyStore.save(db, "job_money", value.id, value.id, value.hourlyRateMinor, at)
        BillingTaxStore.saveJob(db, value.id, value.taxUnits, at)
        RevisionStore.snapshotRows(db, "jobs", "job", BusinessSchema.jobColumns, at, where = "id=?", args = arrayOf(value.id))
        return null
    }
}

/** Literal allowlisted substitution. Values are never evaluated as template syntax. */
/** Bounded scanner: invalid user text never constructs a regex or recursive parser. */
internal object InvoiceEmailTemplate {
    val placeholders = setOf("company_name", "customer_name", "job_name", "invoice_number", "amount", "currency")
    private data class Token(val start: Int, val end: Int, val name: String)
    private fun tokens(value: String): List<Token>? {
        val result = mutableListOf<Token>()
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                '}' -> return null
                '{' -> {
                    val end = value.indexOf('}', index + 1)
                    if (end < 0) return null
                    val name = value.substring(index + 1, end)
                    if (name !in placeholders) return null
                    result.add(Token(index, end + 1, name))
                    index = end + 1
                }
                else -> index++
            }
        }
        return result
    }
    fun valid(value: String, subject: Boolean): Boolean = value.length <= (if (subject) 500 else 20000) &&
        !value.contains('\u0000') && (!subject || !value.any { it.isISOControl() }) && tokens(value) != null
    fun expand(value: String, values: Map<String, String>, subject: Boolean): String {
        require(valid(value, subject)) { "INVALID_EMAIL_TEMPLATE" }
        val result = StringBuilder()
        var copied = 0
        for (token in requireNotNull(tokens(value))) {
            result.append(value, copied, token.start)
            val replacement = values[token.name].orEmpty()
            result.append(if (subject) replacement.map { if (it.isISOControl()) ' ' else it }.joinToString("") else replacement.replace("\u0000", ""))
            copied = token.end
        }
        return result.append(value, copied, value.length).toString()
    }
}
