package com.goyes.finance

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

// ---------- Formato ----------
fun money(v: Double): String {
    val nf = NumberFormat.getNumberInstance(Locale.US)
    nf.minimumFractionDigits = 2
    nf.maximumFractionDigits = 2
    return (if (v < -0.005) "-$" else "$") + nf.format(abs(v))
}

/** Número sin formato para campos de texto (12 / 12.50). */
fun plain(v: Double): String = if (v == floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

/** Acepta coma o punto decimal. */
fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

fun dueLabel(due: LocalDate, today: LocalDate): String {
    val diff = ChronoUnit.DAYS.between(today, due)
    return when {
        diff < 0 -> "Vencido hace ${-diff} d"
        diff == 0L -> "Vence hoy"
        diff == 1L -> "Vence mañana"
        else -> "Vence en $diff d"
    }
}

// ---------- Generación automática (salario + pagos recurrentes) ----------
object Generator {
    /** Crea lo que falte hasta hoy. Es idempotente: se puede llamar cuantas veces se quiera. */
    fun run(store: Store, today: LocalDate = LocalDate.now()): Boolean {
        store.migrateIfNeeded()
        var changed = false
        val cur = YearMonth.from(today)

        // Salario fijo: una entrada de ingreso por mes, en el día configurado.
        val s = store.salary()
        if (s.enabled && s.amount > 0) {
            val last = store.salaryLast
            var m = if (last.isBlank()) cur else YearMonth.parse(last).plusMonths(1)
            if (m.isBefore(cur.minusMonths(24))) m = cur.minusMonths(24)
            val entries = store.entries().toMutableList()
            var added = false
            while (!m.isAfter(cur)) {
                val date = m.atDay(s.day.coerceIn(1, m.lengthOfMonth()))
                if (date.isAfter(today)) break
                val key = "salary:$m"
                if (entries.none { it.auto == key }) {
                    entries += Entry(Ids.next(), s.title, s.amount, "Ingreso", date.toString(), "Generado automáticamente", key)
                    added = true
                }
                store.salaryLast = m.toString()
                m = m.plusMonths(1)
            }
            if (added) { store.saveEntries(entries); changed = true }
        }

        // Pagos recurrentes: un pago por plantilla y mes, hasta el mes siguiente (para ver y alertar con anticipación).
        val bills = store.bills()
        if (bills.isNotEmpty()) {
            val payments = store.payments().toMutableList()
            val limit = cur.plusMonths(1)
            val floorMonth = cur.minusMonths(24)
            var added = false
            for (b in bills) {
                val startM = runCatching { YearMonth.parse(b.start) }.getOrDefault(cur)
                var m = if (startM.isBefore(floorMonth)) floorMonth else startM
                val end = if (b.repeat) limit else startM
                while (!m.isAfter(end)) {
                    val key = m.toString()
                    if (payments.none { it.billId == b.id && it.month == key }) {
                        payments += Payment(Ids.next(), b.id, b.title, b.amount, key, b.day, false)
                        added = true
                    }
                    m = m.plusMonths(1)
                }
            }
            if (added) { store.savePayments(payments); changed = true }
        }
        return changed
    }
}

// ---------- Presupuesto ----------
data class Budget(
    val income: Double,
    val expectedSalary: Double,
    val expenses: Double,
    val paidBills: Double,
    val pendingBills: Double,
    val abonos: Double,
    val carryOver: Double
) {
    val available: Double get() = income + expectedSalary - expenses - paidBills - pendingBills - abonos - carryOver
}

// ---------- Alertas ----------
fun buildAlerts(payments: List<Payment>, debts: List<Debt>, daysBefore: Int, today: LocalDate): List<AlertItem> {
    val out = mutableListOf<AlertItem>()
    for (p in payments) {
        if (p.paid) continue
        val due = runCatching { p.dueDate() }.getOrNull() ?: continue
        val diff = ChronoUnit.DAYS.between(today, due)
        if (diff < 0) out += AlertItem(p.title, due, p.amount, true, "Pago")
        else if (diff <= daysBefore) out += AlertItem(p.title, due, p.amount, false, "Pago")
    }
    for (d in debts) {
        if (d.balance <= 0.005) continue
        val due = parseDateOrNull(d.due) ?: continue
        val diff = ChronoUnit.DAYS.between(today, due)
        if (diff < 0) out += AlertItem(d.title, due, d.balance, true, "Deuda")
        else if (diff <= daysBefore) out += AlertItem(d.title, due, d.balance, false, "Deuda")
    }
    return out.sortedBy { it.date }
}

// ---------- Calendario ----------
data class CalEvent(val day: Int, val title: String, val amount: Double, val status: String, val kind: String)

fun eventsFor(month: YearMonth, payments: List<Payment>, debts: List<Debt>, today: LocalDate): List<CalEvent> {
    val out = mutableListOf<CalEvent>()
    payments.filter { it.month == month.toString() }.forEach { p ->
        val due = p.dueDate()
        val status = when {
            p.paid -> "paid"
            due.isBefore(today) -> "overdue"
            else -> "pending"
        }
        out += CalEvent(due.dayOfMonth, p.title, p.amount, status, "Pago")
    }
    debts.forEach { d ->
        val due = parseDateOrNull(d.due) ?: return@forEach
        if (YearMonth.from(due) == month && d.balance > 0.005) {
            out += CalEvent(due.dayOfMonth, d.title, d.balance, if (due.isBefore(today)) "overdue" else "pending", "Deuda")
        }
    }
    return out.sortedBy { it.day }
}

// ---------- Simulador ----------
data class Commit(val label: String, val amount: Double, val due: LocalDate?)
data class Alloc(val label: String, val amount: Double, val kind: String, val note: String = "", val debtId: Long? = null)
data class SimResult(val allocs: List<Alloc>, val leftover: Double, val shortfall: Double)

/**
 * Orden de prioridad: compromisos fijos (por fecha) -> gastos variables -> ahorro -> deudas (según estrategia).
 */
fun simulate(income: Double, variable: Double, savingsPct: Double, commits: List<Commit>, debts: List<Debt>, strategy: String): SimResult {
    val allocs = mutableListOf<Alloc>()
    var rest = income.coerceAtLeast(0.0)
    var shortfall = 0.0

    fun take(label: String, want: Double, kind: String) {
        if (want <= 0.005) return
        val give = minOf(rest, want)
        rest -= give
        shortfall += want - give
        allocs += Alloc(label, give, kind, if (give < want - 0.005) "Faltan ${money(want - give)}" else "")
    }

    commits.sortedBy { it.due ?: LocalDate.MIN }.forEach { take(it.label, it.amount, "Compromiso") }
    take("Gastos variables", variable, "Gasto")
    take("Ahorro (${plain(savingsPct)}%)", income.coerceAtLeast(0.0) * savingsPct.coerceIn(0.0, 100.0) / 100.0, "Ahorro")

    val open = debts.filter { it.balance > 0.005 }
    val give = open.associate { it.id to 0.0 }.toMutableMap()
    if (strategy == "Proporcional") {
        var pool = rest
        repeat(10) {
            val needy = open.filter { it.balance - give.getValue(it.id) > 0.005 }
            val totalNeed = needy.sumOf { it.balance - give.getValue(it.id) }
            if (pool <= 0.005 || needy.isEmpty()) return@repeat
            var used = 0.0
            needy.forEach { d ->
                val need = d.balance - give.getValue(d.id)
                val part = minOf(need, pool * need / totalNeed)
                give[d.id] = give.getValue(d.id) + part
                used += part
            }
            pool -= used
        }
        rest = pool
    } else {
        val ordered = if (strategy == "Vence primero") open.sortedBy { parseDateOrNull(it.due) ?: LocalDate.MAX } else open.sortedBy { it.balance }
        ordered.forEach { d ->
            val part = minOf(rest, d.balance)
            give[d.id] = part
            rest -= part
        }
    }
    open.forEach { d ->
        val part = give.getValue(d.id)
        allocs += Alloc(d.title, part, "Deuda", "Saldo tras abono: ${money(d.balance - part)}", d.id)
    }
    return SimResult(allocs, rest.coerceAtLeast(0.0), shortfall)
}
