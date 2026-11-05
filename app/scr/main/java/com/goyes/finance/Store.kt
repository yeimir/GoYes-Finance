package com.goyes.finance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("goyes_finance", Context.MODE_PRIVATE)

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun <T> writeList(key: String, v: List<T>, toJson: (T) -> JSONObject) {
        val a = JSONArray()
        v.forEach { a.put(toJson(it)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun arr(key: String) = JSONArray(prefs.getString(key, "[]"))

    // ---------- Movimientos ----------
    fun entries(): List<Entry> = readList("entries") { o ->
        Entry(o.getLong("id"), o.getString("title"), o.getDouble("amount"), o.getString("kind"), o.getString("date"), o.optString("note"), o.optString("auto"))
    }
    fun saveEntries(v: List<Entry>) = writeList("entries", v) {
        JSONObject().put("id", it.id).put("title", it.title).put("amount", it.amount).put("kind", it.kind).put("date", it.date).put("note", it.note).put("auto", it.auto)
    }

    // ---------- Deudas y abonos ----------
    fun debts(): List<Debt> = readList("debts") { o ->
        Debt(o.getLong("id"), o.getString("title"), o.getDouble("initial"), o.getDouble("paid"), o.optString("due"), o.optString("category"))
    }
    fun saveDebts(v: List<Debt>) = writeList("debts", v) {
        JSONObject().put("id", it.id).put("title", it.title).put("initial", it.initial).put("paid", it.paid).put("due", it.due).put("category", it.category)
    }
    fun abonos(): List<Abono> = readList("abonos") { o ->
        Abono(o.getLong("id"), o.getLong("debtId"), o.getString("title"), o.getDouble("amount"), o.getString("date"))
    }
    fun saveAbonos(v: List<Abono>) = writeList("abonos", v) {
        JSONObject().put("id", it.id).put("debtId", it.debtId).put("title", it.title).put("amount", it.amount).put("date", it.date)
    }

    // ---------- Pagos recurrentes (plantillas) y pagos mensuales ----------
    fun bills(): List<Bill> = readList("bills") { o ->
        Bill(
            o.getLong("id"), o.getString("title"), o.getDouble("amount"), o.getInt("day"),
            o.optDouble("arrears", 0.0), o.optBoolean("repeat", true),
            o.optString("start").ifBlank { YearMonth.now().toString() }
        )
    }
    fun saveBills(v: List<Bill>) = writeList("bills", v) {
        JSONObject().put("id", it.id).put("title", it.title).put("amount", it.amount).put("day", it.day)
            .put("arrears", it.arrears).put("repeat", it.repeat).put("start", it.start)
    }
    fun payments(): List<Payment> = readList("payments") { o ->
        Payment(o.getLong("id"), o.getLong("billId"), o.getString("title"), o.getDouble("amount"), o.getString("month"), o.getInt("day"), o.getBoolean("paid"), o.optString("paidDate"))
    }
    fun savePayments(v: List<Payment>) = writeList("payments", v) {
        JSONObject().put("id", it.id).put("billId", it.billId).put("title", it.title).put("amount", it.amount)
            .put("month", it.month).put("day", it.day).put("paid", it.paid).put("paidDate", it.paidDate)
    }

    /** Versión 1.0 guardaba un único estado pagado/pendiente por plantilla: lo convertimos en el pago del mes actual. */
    fun migrateIfNeeded() {
        if (prefs.contains("payments")) return
        val now = YearMonth.now().toString()
        val out = mutableListOf<Payment>()
        runCatching {
            val legacy = arr("bills")
            for (i in 0 until legacy.length()) {
                val o = legacy.getJSONObject(i)
                out += Payment(Ids.next(), o.getLong("id"), o.getString("title"), o.getDouble("amount"), now, o.getInt("day"), o.optBoolean("paid", false), "")
            }
        }
        savePayments(out)
    }

    // ---------- Salario y alertas ----------
    fun salary(): Salary = runCatching {
        val o = JSONObject(prefs.getString("salary", "{}"))
        Salary(o.optString("title", "Salario").ifBlank { "Salario" }, o.optDouble("amount", 0.0), o.optInt("day", 1), o.optBoolean("enabled", false))
    }.getOrDefault(Salary())
    fun saveSalary(s: Salary) {
        prefs.edit().putString("salary", JSONObject().put("title", s.title).put("amount", s.amount).put("day", s.day).put("enabled", s.enabled).toString()).apply()
    }
    /** Último mes (AAAA-MM) para el que ya se generó el salario. */
    var salaryLast: String
        get() = prefs.getString("salary_last", "") ?: ""
        set(v) { prefs.edit().putString("salary_last", v).apply() }

    fun alertPrefs(): AlertPrefs = runCatching {
        val o = JSONObject(prefs.getString("alerts", "{}"))
        AlertPrefs(o.optBoolean("enabled", false), o.optInt("daysBefore", 3))
    }.getOrDefault(AlertPrefs())
    fun saveAlertPrefs(a: AlertPrefs) {
        prefs.edit().putString("alerts", JSONObject().put("enabled", a.enabled).put("daysBefore", a.daysBefore).toString()).apply()
    }

    // ---------- Respaldo ----------
    fun backup(): String = JSONObject()
        .put("version", 2)
        .put("entries", arr("entries")).put("debts", arr("debts")).put("bills", arr("bills"))
        .put("payments", arr("payments")).put("abonos", arr("abonos"))
        .put("salary", JSONObject(prefs.getString("salary", "{}"))).put("salary_last", salaryLast)
        .put("alerts", JSONObject(prefs.getString("alerts", "{}")))
        .toString(2)

    fun restore(json: String) {
        val o = JSONObject(json)
        val e = prefs.edit()
        for (k in listOf("entries", "debts", "bills", "abonos")) e.putString(k, o.optJSONArray(k)?.toString() ?: "[]")
        // Respaldos de la v1.0 no traen "payments": se quita para que migrateIfNeeded() los reconstruya.
        val pay = o.optJSONArray("payments")
        if (pay != null) e.putString("payments", pay.toString()) else e.remove("payments")
        e.putString("salary", o.optJSONObject("salary")?.toString() ?: "{}")
        e.putString("salary_last", o.optString("salary_last"))
        e.putString("alerts", o.optJSONObject("alerts")?.toString() ?: "{}")
        e.apply()
        migrateIfNeeded()
    }
}
