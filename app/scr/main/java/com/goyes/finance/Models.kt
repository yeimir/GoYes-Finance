package com.goyes.finance

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Movimiento de ingreso/gasto. `auto` identifica los generados automáticamente (ej. "salary:2026-11"). */
data class Entry(val id: Long, val title: String, val amount: Double, val kind: String, val date: String, val note: String = "", val auto: String = "")

data class Debt(val id: Long, val title: String, val initial: Double, val paid: Double, val due: String, val category: String) {
    val balance: Double get() = (initial - paid).coerceAtLeast(0.0)
}

/** Plantilla de pago recurrente (alquiler, colegio, condominio...). `start` = mes de inicio (AAAA-MM). */
data class Bill(val id: Long, val title: String, val amount: Double, val day: Int, val arrears: Double, val repeat: Boolean, val start: String)

/** Pago concreto de un mes, generado a partir de una plantilla. `month` = AAAA-MM. */
data class Payment(val id: Long, val billId: Long, val title: String, val amount: Double, val month: String, val day: Int, val paid: Boolean, val paidDate: String = "")

/** Abono realizado a una deuda (historial). */
data class Abono(val id: Long, val debtId: Long, val title: String, val amount: Double, val date: String)

data class Salary(val title: String = "Salario", val amount: Double = 0.0, val day: Int = 1, val enabled: Boolean = false)

data class AlertPrefs(val enabled: Boolean = false, val daysBefore: Int = 3)

data class AlertItem(val title: String, val date: LocalDate, val amount: Double, val overdue: Boolean, val kind: String)

object Ids {
    private var last = 0L
    @Synchronized fun next(): Long {
        val n = maxOf(System.currentTimeMillis(), last + 1)
        last = n
        return n
    }
}

private val ES = Locale.forLanguageTag("es")

fun Payment.dueDate(): LocalDate {
    val m = YearMonth.parse(month)
    return m.atDay(day.coerceIn(1, m.lengthOfMonth()))
}

fun monthLabel(m: YearMonth): String =
    m.month.getDisplayName(TextStyle.FULL, ES).replaceFirstChar { it.uppercase() } + " " + m.year

fun shortDate(d: LocalDate): String = "${d.dayOfMonth} " + d.month.getDisplayName(TextStyle.SHORT, ES).trimEnd('.')

fun parseMonthOrNull(date: String): YearMonth? = runCatching { YearMonth.parse(date.take(7)) }.getOrNull()

fun parseDateOrNull(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim()) }.getOrNull()
