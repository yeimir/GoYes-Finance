package com.goyes.finance

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

private val Navy = Color(0xFF071326)
private val Blue = Color(0xFF087BEE)
private val Cyan = Color(0xFF13C7E8)
private val Green = Color(0xFF20B985)
private val Red = Color(0xFFFF7777)
private val Orange = Color(0xFFFFB45E)

data class Pal(val surface: Color, val fg: Color)
val LocalPal = compositionLocalOf { Pal(Color.White, Color.Black) }

private fun statusColor(s: String) = when (s) { "paid" -> Green; "overdue" -> Red; else -> Orange }

// =====================================================================================
// Estado de la app (todo se persiste en Store en cada cambio)
// =====================================================================================
class AppData(private val store: Store, private val appContext: Context) {
    var entries by mutableStateOf(store.entries())
    var debts by mutableStateOf(store.debts())
    var bills by mutableStateOf(store.bills())
    var payments by mutableStateOf(store.payments())
    var abonos by mutableStateOf(store.abonos())
    var salary by mutableStateOf(store.salary())
    var alertPrefs by mutableStateOf(store.alertPrefs())

    fun reload() {
        entries = store.entries(); debts = store.debts(); bills = store.bills()
        payments = store.payments(); abonos = store.abonos(); salary = store.salary(); alertPrefs = store.alertPrefs()
    }

    /** Genera lo pendiente (salario / pagos del mes) y recarga. */
    fun refresh() { Generator.run(store); reload() }

    fun backupJson() = store.backup()
    fun restore(json: String) {
        store.restore(json)
        refresh()
        if (alertPrefs.enabled) Alerts.schedule(appContext) else Alerts.cancel(appContext)
    }

    // ----- movimientos -----
    fun addEntry(e: Entry) { entries = entries + e; store.saveEntries(entries) }
    fun deleteEntry(id: Long) { entries = entries.filterNot { it.id == id }; store.saveEntries(entries) }

    // ----- deudas -----
    fun addDebt(d: Debt) { debts = debts + d; store.saveDebts(debts) }
    fun deleteDebt(id: Long) { debts = debts.filterNot { it.id == id }; store.saveDebts(debts) }
    fun payDebt(d: Debt, amount: Double) {
        val real = minOf(amount, d.balance)
        if (real <= 0.0) return
        debts = debts.map { if (it.id == d.id) it.copy(paid = (it.paid + real).coerceAtMost(it.initial)) else it }
        store.saveDebts(debts)
        abonos = abonos + Abono(Ids.next(), d.id, d.title, real, LocalDate.now().toString())
        store.saveAbonos(abonos)
    }

    // ----- pagos recurrentes -----
    fun saveBill(b: Bill, isNew: Boolean) {
        bills = if (isNew) bills + b else bills.map { if (it.id == b.id) b else it }
        store.saveBills(bills)
        if (!isNew) {
            val cur = YearMonth.now().toString()
            payments = payments.map {
                if (it.billId == b.id && !it.paid && it.month >= cur) it.copy(title = b.title, amount = b.amount, day = b.day) else it
            }
            store.savePayments(payments)
        }
        refresh()
    }
    fun deleteBill(id: Long) {
        val cur = YearMonth.now().toString()
        bills = bills.filterNot { it.id == id }
        store.saveBills(bills)
        payments = payments.filterNot { it.billId == id && !it.paid && it.month >= cur }
        store.savePayments(payments)
    }
    fun togglePayment(id: Long) {
        payments = payments.map {
            if (it.id == id) it.copy(paid = !it.paid, paidDate = if (!it.paid) LocalDate.now().toString() else "") else it
        }
        store.savePayments(payments)
    }
    fun clearArrears(b: Bill) {
        if (b.arrears <= 0.0) return
        bills = bills.map { if (it.id == b.id) it.copy(arrears = 0.0) else it }
        store.saveBills(bills)
        addEntry(Entry(Ids.next(), "Atraso: ${b.title}", b.arrears, "Gasto", LocalDate.now().toString(), "Atraso saldado"))
    }

    // ----- salario y alertas -----
    fun saveSalary(s: Salary) {
        if (!salary.enabled && s.enabled) store.salaryLast = YearMonth.now().minusMonths(1).toString()
        store.saveSalary(s)
        salary = s
        if (s.enabled) {
            val m = YearMonth.now()
            val key = "salary:$m"
            entries = entries.map {
                if (it.auto == key) it.copy(title = s.title, amount = s.amount, date = m.atDay(s.day.coerceIn(1, m.lengthOfMonth())).toString()) else it
            }
            store.saveEntries(entries)
        }
        refresh()
    }
    fun setAlerts(a: AlertPrefs) {
        store.saveAlertPrefs(a)
        alertPrefs = a
        if (a.enabled) Alerts.schedule(appContext) else Alerts.cancel(appContext)
    }

    // ----- consultas por mes -----
    fun entriesOf(m: YearMonth) = entries.filter { it.date.startsWith(m.toString()) }
    fun paymentsOf(m: YearMonth) = payments.filter { it.month == m.toString() }.sortedBy { it.day }
    fun abonosOf(m: YearMonth) = abonos.filter { it.date.startsWith(m.toString()) }

    fun availableMonths(): List<YearMonth> {
        val cur = YearMonth.now()
        val set = mutableSetOf(cur)
        entries.forEach { e -> parseMonthOrNull(e.date)?.let { set.add(it) } }
        payments.forEach { p -> runCatching { YearMonth.parse(p.month) }.getOrNull()?.let { set.add(it) } }
        abonos.forEach { a -> parseMonthOrNull(a.date)?.let { set.add(it) } }
        return set.filter { !it.isAfter(cur) }.sortedDescending()
    }

    fun budget(m: YearMonth): Budget {
        val isCur = m == YearMonth.now()
        val es = entriesOf(m)
        val ps = paymentsOf(m)
        val carry = if (isCur) payments.filter { !it.paid && it.month < m.toString() }.sumOf { it.amount } + bills.sumOf { it.arrears } else 0.0
        val expected = if (isCur && salary.enabled && salary.amount > 0 && es.none { it.auto == "salary:$m" }) salary.amount else 0.0
        return Budget(
            income = es.filter { it.kind == "Ingreso" }.sumOf { it.amount },
            expectedSalary = expected,
            expenses = es.filter { it.kind == "Gasto" }.sumOf { it.amount },
            paidBills = ps.filter { it.paid }.sumOf { it.amount },
            pendingBills = ps.filter { !it.paid }.sumOf { it.amount },
            abonos = abonosOf(m).sumOf { it.amount },
            carryOver = carry
        )
    }
}

// =====================================================================================
// Actividad y raíz de la UI
// =====================================================================================
class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GoYesApp(resumeTick) }
    }
    override fun onResume() { super.onResume(); resumeTick++ }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoYesApp(resumeTick: Int) {
    val context = LocalContext.current
    val store = remember { Store(context).also { it.migrateIfNeeded() } }
    val data = remember { AppData(store, context.applicationContext) }
    var tab by remember { mutableIntStateOf(0) }
    var moreSub by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf("") }
    var selectedDebt by remember { mutableStateOf<Debt?>(null) }
    var editingBill by remember { mutableStateOf<Bill?>(null) }

    // Al abrir/volver a la app: genera salario y pagos del mes si faltan, y reprograma las alertas.
    LaunchedEffect(resumeTick) {
        data.refresh()
        if (data.alertPrefs.enabled) Alerts.schedule(context)
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(data.backupJson().toByteArray()) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            val s = context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            data.restore(s)
        }
    }

    val bg = if (dark) Color(0xFF081426) else Color(0xFFF3F6FA)
    val surface = if (dark) Color(0xFF12233B) else Color.White
    val fg = if (dark) Color.White else Color(0xFF152238)

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Blue, background = bg, surface = surface, onSurface = fg) else lightColorScheme(primary = Blue, background = bg, surface = surface, onSurface = fg)) {
        CompositionLocalProvider(LocalPal provides Pal(surface, fg)) {
            Scaffold(
                containerColor = bg,
                contentColor = fg,
                topBar = {
                    TopAppBar(
                        title = { Column { Text("GoYes", fontWeight = FontWeight.Black, fontSize = 23.sp, color = if (dark) Color.White else Navy); Text("FINANCE", fontSize = 10.sp, letterSpacing = 3.sp, color = Cyan) } },
                        actions = { IconButton(onClick = { dark = !dark }) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Cambiar tema", tint = fg) } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = surface) {
                        val labels = listOf("Inicio", "Movimientos", "Deudas", "Pagos", "Más")
                        val icons = listOf(Icons.Default.Home, Icons.Default.SwapHoriz, Icons.Default.CreditCard, Icons.Default.Event, Icons.Default.MoreHoriz)
                        labels.forEachIndexed { i, label ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { val wasMore = tab == 4; tab = i; if (i == 4 && wasMore) moreSub = "" },
                                icon = { Icon(icons[i], label) },
                                label = { Text(label, fontSize = 10.sp) }
                            )
                        }
                    }
                }
            ) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                    when (tab) {
                        0 -> Dashboard(data, { dialog = "entry" }, { moreSub = "budget"; tab = 4 })
                        1 -> MovementsScreen(data) { dialog = "entry" }
                        2 -> DebtsScreen(data, { dialog = "debt" }, { d -> selectedDebt = d; dialog = "paydebt" })
                        3 -> PaymentsScreen(data, { editingBill = null; dialog = "bill" }, { b -> editingBill = b; dialog = "bill" })
                        else -> MoreScreen(data, moreSub, { moreSub = it }, dark, { dark = it }, { export.launch("goyes-finance-backup.json") }, { import.launch(arrayOf("application/json")) })
                    }
                }
            }
            when (dialog) {
                "entry" -> EntryDialog({ title, amount, kind, date, note ->
                    data.addEntry(Entry(Ids.next(), title, amount, kind, date, note)); dialog = ""
                }, { dialog = "" })
                "debt" -> DebtDialog({ title, amount, category, due ->
                    data.addDebt(Debt(Ids.next(), title, amount, 0.0, due, category)); dialog = ""
                }, { dialog = "" })
                "bill" -> {
                    val eb = editingBill
                    BillDialog(eb, { title, amount, day, arrears, repeat ->
                        if (eb == null) data.saveBill(Bill(Ids.next(), title, amount, day, arrears, repeat, YearMonth.now().toString()), true)
                        else data.saveBill(eb.copy(title = title, amount = amount, day = day, arrears = arrears, repeat = repeat), false)
                        dialog = ""; editingBill = null
                    }, { dialog = ""; editingBill = null })
                }
                "paydebt" -> selectedDebt?.let { d ->
                    PayDebtDialog(d, { payment -> data.payDebt(d, payment); dialog = "" }, { dialog = "" })
                }
            }
        }
    }
}

// =====================================================================================
// Componentes comunes
// =====================================================================================
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    Column(modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun SubTitle(text: String) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LocalPal.current.fg, modifier = Modifier.padding(top = 6.dp)) }

@Composable
fun SectionHeader(title: String, subtitle: String, onAdd: (() -> Unit)?, button: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = LocalPal.current.fg); Text(subtitle, fontSize = 11.sp, color = Color.Gray) }
        if (onAdd != null) Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Icon(Icons.Default.Add, null); Text(button, fontSize = 11.sp) }
    }
}

@Composable
fun EmptyState(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Inbox, null, tint = Color.Gray, modifier = Modifier.size(34.dp))
        Text(title, fontWeight = FontWeight.Bold, color = LocalPal.current.fg)
        Text(sub, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Metric(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Card(modifier, colors = CardDefaults.cardColors(containerColor = pal.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) { Text(label, color = Color.Gray, fontSize = 11.sp); Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 19.sp) }
    }
}

@Composable
fun MonthSelector(month: YearMonth, onChange: (YearMonth) -> Unit, options: List<YearMonth> = emptyList(), max: YearMonth? = null) {
    val pal = LocalPal.current
    var open by remember { mutableStateOf(false) }
    val atMax = max != null && !month.isBefore(max)
    Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(14.dp)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.Default.ChevronLeft, "Mes anterior", tint = pal.fg) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(monthLabel(month), color = pal.fg, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = options.isNotEmpty()) { open = true }.padding(8.dp))
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { m -> DropdownMenuItem(text = { Text(monthLabel(m)) }, onClick = { onChange(m); open = false }) }
            }
        }
        IconButton(onClick = { onChange(month.plusMonths(1)) }, enabled = !atMax) { Icon(Icons.Default.ChevronRight, "Mes siguiente", tint = if (atMax) Color.Gray else pal.fg) }
    }
}

@Composable
fun EntryRow(e: Entry, onDelete: (() -> Unit)? = null) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(14.dp)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.title, color = pal.fg, fontWeight = FontWeight.SemiBold)
            Text("${e.kind} · ${e.date}", color = Color.Gray, fontSize = 11.sp)
            if (e.note.isNotBlank()) Text(e.note, color = Color.Gray, fontSize = 11.sp)
        }
        Text((if (e.kind == "Ingreso") "+" else "−") + money(e.amount), color = if (e.kind == "Ingreso") Green else Red, fontWeight = FontWeight.Bold)
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = Color.Gray) }
    }
}

@Composable
fun PaymentRow(p: Payment, today: LocalDate, onToggle: (() -> Unit)?) {
    val pal = LocalPal.current
    val due = p.dueDate()
    val status = when { p.paid -> "paid"; due.isBefore(today) -> "overdue"; else -> "pending" }
    val label = when (status) { "paid" -> "Pagado"; "overdue" -> "Vencido"; else -> dueLabel(due, today) }
    Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(15.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.title, color = pal.fg, fontWeight = FontWeight.Bold)
            Text("Día ${shortDate(due)} · $label", color = statusColor(status), fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(p.amount), color = pal.fg, fontWeight = FontWeight.Bold)
            if (onToggle != null) TextButton(onClick = onToggle) { Text(if (p.paid) "Marcar pendiente" else "Marcar pagado") }
        }
    }
}

@Composable
fun BudgetLine(label: String, value: Double, color: Color, sign: String = "") {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = LocalPal.current.fg, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(sign + money(value), color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

// =====================================================================================
// Inicio
// =====================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard(d: AppData, onAdd: () -> Unit, onBudget: () -> Unit) {
    val pal = LocalPal.current
    val today = LocalDate.now()
    val cur = YearMonth.from(today)
    val b = d.budget(cur)
    val alerts = buildAlerts(d.payments, d.debts, d.alertPrefs.daysBefore, today)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("BALANCE DE ${monthLabel(cur).uppercase()}", color = Color(0xFFB9C8DB), fontSize = 11.sp, letterSpacing = 2.sp)
                    Text(money(b.income - b.expenses), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("↑ ${money(b.income)} ingresos", color = Color(0xFF4BE0AE), fontSize = 12.sp)
                        Text("↓ ${money(b.expenses)} gastos", color = Color(0xFFFF9B9B), fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Card(onClick = onBudget, colors = CardDefaults.cardColors(containerColor = pal.surface), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("DISPONIBLE DESPUÉS DE COMPROMISOS", color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(money(b.available), color = if (b.available >= 0) Green else Red, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Pagos pendientes ${money(b.pendingBills + b.carryOver)} · Toca para ver el detalle", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
        if (alerts.isNotEmpty()) item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, tint = Orange)
                    Spacer(Modifier.width(8.dp))
                    Text("Vencimientos (${alerts.size})", color = pal.fg, fontWeight = FontWeight.Bold)
                }
                alerts.take(4).forEach { a ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, color = pal.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${a.kind} · ${dueLabel(a.date, today)}", color = if (a.overdue) Red else Orange, fontSize = 11.sp)
                        }
                        Text(money(a.amount), color = pal.fg, fontSize = 13.sp)
                    }
                }
                if (alerts.size > 4) Text("y ${alerts.size - 4} más en la pestaña Pagos", color = Color.Gray, fontSize = 11.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Deudas pendientes", money(d.debts.sumOf { it.balance }), Orange, Modifier.weight(1f))
                Metric("Pagos pendientes del mes", money(b.pendingBills), Cyan, Modifier.weight(1f))
            }
        }
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Registrar movimiento") } }
        item { Text("Actividad reciente", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = pal.fg) }
        if (d.entries.isEmpty()) item { EmptyState("Aún no hay movimientos", "Registra tu primer ingreso o gasto, o configura tu salario fijo en Más > Ajustes.") }
        items(d.entries.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.id }).take(5)) { e -> EntryRow(e) }
    }
}

// =====================================================================================
// Movimientos
// =====================================================================================
@Composable
fun MovementsScreen(d: AppData, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Movimientos", "Ingresos y gastos registrados", onAdd, "Agregar")
        if (d.entries.isEmpty()) EmptyState("Sin movimientos", "Agrega tus ingresos extra y gastos. El salario fijo se genera solo (Más > Ajustes).")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.entries.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.id })) { e -> EntryRow(e) { d.deleteEntry(e.id) } }
        }
    }
}

// =====================================================================================
// Deudas
// =====================================================================================
@Composable
fun DebtsScreen(d: AppData, onAdd: () -> Unit, onPay: (Debt) -> Unit) {
    val pal = LocalPal.current
    Column {
        SectionHeader("Mis deudas", "Control de saldo y abonos", onAdd, "Nueva deuda")
        Text("TOTAL PENDIENTE  ${money(d.debts.sumOf { it.balance })}", color = Orange, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(d.debts) { debt ->
                Card(colors = CardDefaults.cardColors(containerColor = pal.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(debt.title, color = pal.fg, fontWeight = FontWeight.Bold)
                                Text("${debt.category} · Vence: ${debt.due.ifBlank { "No definido" }}", color = Color.Gray, fontSize = 11.sp)
                            }
                            Text(money(debt.balance), color = Orange, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(progress = { (debt.paid / debt.initial.coerceAtLeast(0.01)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Abonado: ${money(debt.paid)} de ${money(debt.initial)}", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onPay(debt) }) { Text("Registrar abono") }
                            IconButton(onClick = { d.deleteDebt(debt.id) }) { Icon(Icons.Default.Delete, null, tint = Color.Gray) }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================
// Pagos: lista del mes, calendario y plantillas recurrentes
// =====================================================================================
@Composable
fun PaymentsScreen(d: AppData, onAdd: () -> Unit, onEdit: (Bill) -> Unit) {
    var sub by remember { mutableIntStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Pagos", "Alquiler, colegio, condominio y servicios", onAdd, "Nuevo pago")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Mes", "Calendario", "Recurrentes").forEachIndexed { i, l -> FilterChip(selected = sub == i, onClick = { sub = i }, label = { Text(l) }) }
        }
        Spacer(Modifier.height(10.dp))
        when (sub) {
            0 -> MonthPayments(d, month) { month = it }
            1 -> CalendarView(d, month) { month = it }
            else -> RecurringList(d, onEdit)
        }
    }
}

@Composable
fun MonthPayments(d: AppData, month: YearMonth, onMonth: (YearMonth) -> Unit) {
    val today = LocalDate.now()
    val list = d.paymentsOf(month)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { MonthSelector(month, onMonth, listOf(YearMonth.from(today).plusMonths(1)) + d.availableMonths(), YearMonth.from(today).plusMonths(1)) }
        item {
            Text("Pendiente: ${money(list.filter { !it.paid }.sumOf { it.amount })}  ·  Pagado: ${money(list.filter { it.paid }.sumOf { it.amount })}", color = Color.Gray, fontSize = 12.sp)
        }
        if (list.isEmpty()) item { EmptyState("Sin pagos este mes", "Los pagos recurrentes se generan automáticamente desde el mes en que los creas.") }
        items(list) { p -> PaymentRow(p, today) { d.togglePayment(p.id) } }
    }
}

@Composable
fun RecurringList(d: AppData, onEdit: (Bill) -> Unit) {
    val pal = LocalPal.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (d.bills.isEmpty()) item { EmptyState("Sin pagos recurrentes", "Agrega alquiler, colegio o condominio y se repetirán solos cada mes.") }
        items(d.bills) { b ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.title, color = pal.fg, fontWeight = FontWeight.Bold)
                        Text("Día ${b.day} · " + if (b.repeat) "Se repite cada mes" else "Solo ${b.start}", color = Color.Gray, fontSize = 11.sp)
                    }
                    Text(money(b.amount), color = pal.fg, fontWeight = FontWeight.Bold)
                }
                if (b.arrears > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Atrasos: ${money(b.arrears)}", color = Orange, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { d.clearArrears(b) }) { Text("Saldar atraso") }
                }
                Row {
                    TextButton(onClick = { onEdit(b) }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("Editar") }
                    TextButton(onClick = { d.deleteBill(b.id) }) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp)); Text("Eliminar") }
                }
            }
        }
        if (d.bills.isNotEmpty()) item { Text("Editar actualiza los pagos pendientes del mes actual y siguientes. Eliminar conserva lo ya pagado en el historial.", color = Color.Gray, fontSize = 11.sp) }
    }
}

@Composable
fun CalendarView(d: AppData, month: YearMonth, onMonth: (YearMonth) -> Unit) {
    val pal = LocalPal.current
    val today = LocalDate.now()
    var selected by remember(month) { mutableStateOf<Int?>(null) }
    val events = eventsFor(month, d.payments, d.debts, today)
    val offset = month.atDay(1).dayOfWeek.value - 1
    val rows = (offset + month.lengthOfMonth() + 6) / 7
    val sel = selected
    val shown = if (sel != null) events.filter { it.day == sel } else events
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { MonthSelector(month, onMonth, listOf(YearMonth.from(today).plusMonths(1)) + d.availableMonths(), YearMonth.from(today).plusMonths(1)) }
        item {
            Column(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(14.dp)).padding(8.dp)) {
                Row {
                    listOf("L", "M", "X", "J", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Color.Gray, fontSize = 11.sp) }
                }
                for (r in 0 until rows) {
                    Row {
                        for (c in 0 until 7) {
                            val day = r * 7 + c - offset + 1
                            if (day < 1 || day > month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(46.dp))
                            else DayCell(day, events.filter { it.day == day }, month.atDay(day) == today, sel == day, Modifier.weight(1f)) {
                                selected = if (sel == day) null else day
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("pending" to "Pendiente", "overdue" to "Vencido", "paid" to "Pagado").forEach { (s, l) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(statusColor(s), CircleShape)); Spacer(Modifier.width(4.dp)); Text(l, color = Color.Gray, fontSize = 11.sp) }
                }
            }
        }
        item { SubTitle(if (sel != null) "Día $sel de ${monthLabel(month)}" else "Vencimientos de ${monthLabel(month)}") }
        if (shown.isEmpty()) item { Text("Sin vencimientos.", color = Color.Gray, fontSize = 12.sp) }
        items(shown) { ev ->
            Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(statusColor(ev.status), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ev.title, color = pal.fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("${ev.kind} · día ${ev.day}", color = Color.Gray, fontSize = 11.sp)
                }
                Text(money(ev.amount), color = pal.fg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DayCell(day: Int, events: List<CalEvent>, isToday: Boolean, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.height(46.dp).padding(1.dp).clip(shape)
            .background(if (isSelected) Blue.copy(alpha = 0.35f) else Color.Transparent)
            .then(if (isToday) Modifier.border(1.dp, Cyan, shape) else Modifier)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("$day", color = pal.fg, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp)) {
            events.map { it.status }.distinct().take(3).forEach { s -> Box(Modifier.size(6.dp).background(statusColor(s), CircleShape)) }
        }
    }
}

// =====================================================================================
// Más: presupuesto, historial, simulador, ajustes
// =====================================================================================
@Composable
fun MoreScreen(d: AppData, sub: String, onSub: (String) -> Unit, dark: Boolean, onTheme: (Boolean) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    val pal = LocalPal.current
    BackHandler(enabled = sub.isNotEmpty()) { onSub("") }
    if (sub.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Más", "Herramientas de planificación", null, "")
            listOf(
                Triple("budget", "Presupuesto mensual" to "Cuánto te queda después de tus compromisos", Icons.Default.Savings),
                Triple("history", "Historial por mes" to "Consulta ingresos, gastos y pagos de meses anteriores", Icons.Default.History),
                Triple("sim", "Simulador" to "Distribuye un ingreso entre gastos y deudas", Icons.Default.Calculate),
                Triple("settings", "Ajustes" to "Salario fijo, alertas, tema y respaldo", Icons.Default.Settings)
            ).forEach { (key, texts, icon) ->
                Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(16.dp)).clickable { onSub(key) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Cyan)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(texts.first, color = pal.fg, fontWeight = FontWeight.Bold); Text(texts.second, color = Color.Gray, fontSize = 11.sp) }
                    Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { onSub("") }) { Icon(Icons.Default.ChevronLeft, null); Text("Más") }
            when (sub) {
                "budget" -> BudgetScreen(d)
                "history" -> HistoryScreen(d)
                "sim" -> SimulatorScreen(d)
                else -> SettingsScreen(d, dark, onTheme, onExport, onImport)
            }
        }
    }
}

@Composable
fun BudgetScreen(d: AppData) {
    val pal = LocalPal.current
    val cur = YearMonth.now()
    var month by remember { mutableStateOf(cur) }
    val b = d.budget(month)
    val committed = b.paidBills + b.pendingBills + b.abonos + b.carryOver
    val base = b.income + b.expectedSalary
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SubTitle("Presupuesto mensual") }
        item { MonthSelector(month, { month = it }, d.availableMonths(), cur) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("DISPONIBLE DESPUÉS DE COMPROMISOS", color = Color(0xFFB9C8DB), fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(money(b.available), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (b.available >= 0) Color(0xFF4BE0AE) else Color(0xFFFF9B9B))
                    if (base > 0) Text("Tus compromisos (pagos y abonos) son el ${(committed / base * 100).toInt()}% de tus ingresos", color = Color(0xFFB9C8DB), fontSize = 11.sp)
                }
            }
        }
        item {
            Panel {
                BudgetLine("Ingresos registrados", b.income, Green, "+")
                if (b.expectedSalary > 0) BudgetLine("Salario esperado (día ${d.salary.day})", b.expectedSalary, Green, "+")
                BudgetLine("Gastos registrados", b.expenses, Red, "−")
                BudgetLine("Pagos recurrentes pagados", b.paidBills, Red, "−")
                BudgetLine("Pagos recurrentes pendientes", b.pendingBills, Orange, "−")
                BudgetLine("Abonos a deudas", b.abonos, Red, "−")
                if (b.carryOver > 0) BudgetLine("Atrasos de meses anteriores", b.carryOver, Orange, "−")
                HorizontalDivider()
                BudgetLine("Disponible", b.available, if (b.available >= 0) Green else Red)
            }
        }
        item { Text("Los pagos recurrentes y los abonos a deudas se descuentan solos: no los registres además como gasto.", color = Color.Gray, fontSize = 11.sp) }
    }
}

@Composable
fun HistoryScreen(d: AppData) {
    val pal = LocalPal.current
    val cur = YearMonth.now()
    var month by remember { mutableStateOf(cur) }
    val today = LocalDate.now()
    val es = d.entriesOf(month).sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.id })
    val ps = d.paymentsOf(month)
    val ab = d.abonosOf(month)
    val b = d.budget(month)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SubTitle("Historial por mes") }
        item { MonthSelector(month, { month = it }, d.availableMonths(), cur) }
        item {
            Panel {
                BudgetLine("Ingresos", b.income, Green, "+")
                BudgetLine("Gastos", b.expenses, Red, "−")
                BudgetLine("Pagos recurrentes pagados", b.paidBills, Red, "−")
                BudgetLine("Abonos a deudas", b.abonos, Red, "−")
                HorizontalDivider()
                BudgetLine("Resultado del mes", b.income - b.expenses - b.paidBills - b.abonos, if (b.income - b.expenses - b.paidBills - b.abonos >= 0) Green else Red)
                if (b.pendingBills > 0) BudgetLine("Pagos sin pagar de ese mes", b.pendingBills, Orange)
            }
        }
        item { SubTitle("Movimientos (${es.size})") }
        if (es.isEmpty()) item { Text("Sin movimientos en este mes.", color = Color.Gray, fontSize = 12.sp) }
        items(es) { e -> EntryRow(e) }
        item { SubTitle("Pagos recurrentes (${ps.size})") }
        if (ps.isEmpty()) item { Text("Sin pagos en este mes.", color = Color.Gray, fontSize = 12.sp) }
        items(ps) { p -> PaymentRow(p, today, null) }
        item { SubTitle("Abonos a deudas (${ab.size})") }
        if (ab.isEmpty()) item { Text("Sin abonos en este mes.", color = Color.Gray, fontSize = 12.sp) }
        items(ab) { a ->
            Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(14.dp)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(a.title, color = pal.fg, fontWeight = FontWeight.SemiBold); Text("Abono · ${a.date}", color = Color.Gray, fontSize = 11.sp) }
                Text("−" + money(a.amount), color = Red, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SimulatorScreen(d: AppData) {
    val pal = LocalPal.current
    val cur = YearMonth.now()
    val initial = remember { d.budget(cur).let { it.income + it.expectedSalary } }
    var income by remember { mutableStateOf(if (initial > 0) plain(initial) else "") }
    var variable by remember { mutableStateOf("0") }
    var savings by remember { mutableStateOf("0") }
    var strategy by remember { mutableStateOf("Menor saldo") }
    var confirm by remember { mutableStateOf(false) }

    val commits = d.payments.filter { !it.paid && it.month <= cur.toString() }.map { Commit(it.title, it.amount, it.dueDate()) } +
        d.bills.filter { it.arrears > 0 }.map { Commit("Atraso: ${it.title}", it.arrears, null) }
    val result = simulate(num(income) ?: 0.0, num(variable) ?: 0.0, num(savings) ?: 0.0, commits, d.debts, strategy)
    val toPay = result.allocs.filter { it.debtId != null && it.amount > 0.005 }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SubTitle("Simulador de distribución") }
        item { Text("Indica cuánto vas a repartir. Se cubren primero tus pagos pendientes, luego gastos y ahorro, y lo que sobre va a deudas.", color = Color.Gray, fontSize = 12.sp) }
        item {
            Panel {
                Field("Ingreso a distribuir USD", income, { income = it }, true)
                Field("Gastos variables estimados USD", variable, { variable = it }, true)
                Field("Ahorro (% del ingreso)", savings, { savings = it }, true)
                Text("Estrategia para las deudas", color = Color.Gray, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Menor saldo", "Vence primero", "Proporcional").forEach { s -> FilterChip(selected = strategy == s, onClick = { strategy = s }, label = { Text(s) }) }
                }
            }
        }
        item { SubTitle("Resultado") }
        if (result.allocs.isEmpty()) item { Text("No hay pagos pendientes ni deudas para repartir.", color = Color.Gray, fontSize = 12.sp) }
        items(result.allocs) { a ->
            Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, color = pal.fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(a.kind + if (a.note.isNotBlank()) " · ${a.note}" else "", color = if (a.note.startsWith("Faltan")) Red else Color.Gray, fontSize = 11.sp)
                }
                Text(money(a.amount), color = pal.fg, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Panel {
                if (result.shortfall > 0.005) Text("No alcanza: faltan ${money(result.shortfall)} para cubrir todo.", color = Red, fontWeight = FontWeight.Bold)
                else Text("Sin asignar: ${money(result.leftover)}", color = Green, fontWeight = FontWeight.Bold)
                if (toPay.isNotEmpty()) Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Registrar abonos del plan (${money(toPay.sumOf { it.amount })})") }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Registrar abonos") },
        text = { Text("Se registrarán ${toPay.size} abono(s) por ${money(toPay.sumOf { it.amount })} y se descontarán del saldo de tus deudas.") },
        confirmButton = {
            TextButton(onClick = {
                toPay.forEach { a ->
                    val debt = d.debts.find { it.id == a.debtId } ?: return@forEach
                    d.payDebt(debt, a.amount)
                }
                confirm = false
            }) { Text("Registrar") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
    )
}

@Composable
fun SettingsScreen(d: AppData, dark: Boolean, onTheme: (Boolean) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubTitle("Ajustes")
        Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(14.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Apariencia", color = pal.fg, fontWeight = FontWeight.SemiBold); Text("Modo oscuro / claro", color = Color.Gray, fontSize = 11.sp) }
            Switch(dark, onTheme)
        }
        SalaryCard(d)
        AlertsCard(d)
        Panel {
            Text("Respaldo manual", fontWeight = FontWeight.Bold, color = pal.fg)
            Text("Exporta o restaura tus datos en un archivo JSON.", fontSize = 12.sp, color = Color.Gray)
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Upload, null); Text(" Exportar respaldo") }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Text(" Importar respaldo") }
        }
        Text("GoYes Finance · v1.1.0", color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
fun SalaryCard(d: AppData) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val s = d.salary
    var title by remember(s) { mutableStateOf(s.title) }
    var amount by remember(s) { mutableStateOf(if (s.amount > 0) plain(s.amount) else "") }
    var day by remember(s) { mutableStateOf(s.day.toString()) }
    var enabled by remember(s) { mutableStateOf(s.enabled) }
    Panel {
        Text("Salario fijo", fontWeight = FontWeight.Bold, color = pal.fg)
        Text("Se registra solo, como ingreso, en el día elegido de cada mes.", fontSize = 12.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Generar automáticamente", color = pal.fg, modifier = Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
        Field("Concepto", title, { title = it })
        Field("Monto mensual USD", amount, { amount = it }, true)
        Field("Día del mes (1-31)", day, { day = it }, true)
        if ((day.toIntOrNull() ?: 1) > 28) Text("En los meses más cortos se usará su último día.", fontSize = 11.sp, color = Color.Gray)
        Button(onClick = {
            val a = num(amount) ?: 0.0
            if (a > 0 || !enabled) {
                d.saveSalary(Salary(title.ifBlank { "Salario" }.trim(), a, (day.toIntOrNull() ?: 1).coerceIn(1, 31), enabled && a > 0))
                Toast.makeText(ctx, "Salario guardado", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(ctx, "Ingresa un monto mayor a 0", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("Guardar salario") }
    }
}

@Composable
fun AlertsCard(d: AppData) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val a = d.alertPrefs
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Panel {
        Text("Alertas de vencimiento", fontWeight = FontWeight.Bold, color = pal.fg)
        Text("Aviso diario (9:00 a. m.) de pagos y deudas vencidos o por vencer.", fontSize = 12.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notificaciones", color = pal.fg, modifier = Modifier.weight(1f))
            Switch(a.enabled, { on ->
                if (on && Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                d.setAlerts(a.copy(enabled = on))
            })
        }
        Text("Avisar con anticipación de:", fontSize = 11.sp, color = Color.Gray)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 5, 7).forEach { n -> FilterChip(selected = a.daysBefore == n, onClick = { d.setAlerts(a.copy(daysBefore = n)) }, label = { Text(if (n == 1) "1 día" else "$n días") }) }
        }
        TextButton(onClick = {
            val ok = Alerts.notifyNow(ctx, ignoreEnabled = true)
            if (!ok) Toast.makeText(ctx, "No hay vencimientos próximos para avisar", Toast.LENGTH_SHORT).show()
        }) { Text("Enviar alerta de prueba") }
    }
}

// =====================================================================================
// Diálogos
// =====================================================================================
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
fun DialogFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
}

@Composable
fun EntryDialog(save: (String, Double, String, String, String) -> Unit, close: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Ingreso") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("") }
    val validDate = parseDateOrNull(date) != null
    val a = num(amount) ?: 0.0
    DialogFrame("Nuevo movimiento", close) {
        Field("Descripción", title, { title = it })
        Field("Monto en USD", amount, { amount = it }, true)
        Row {
            FilterChip(selected = kind == "Ingreso", onClick = { kind = "Ingreso" }, label = { Text("Ingreso") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = kind == "Gasto", onClick = { kind = "Gasto" }, label = { Text("Gasto") })
        }
        Field("Fecha (AAAA-MM-DD)", date, { date = it })
        if (!validDate) Text("Formato de fecha no válido", color = Red, fontSize = 11.sp)
        Field("Nota (opcional)", note, { note = it })
        Button(onClick = { save(title.trim(), a, kind, date.trim(), note) }, modifier = Modifier.fillMaxWidth(), enabled = title.isNotBlank() && a > 0 && validDate) { Text("Guardar movimiento") }
    }
}

@Composable
fun DebtDialog(save: (String, Double, String, String) -> Unit, close: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Personal") }
    var due by remember { mutableStateOf("") }
    val a = num(amount) ?: 0.0
    val validDue = due.isBlank() || parseDateOrNull(due) != null
    DialogFrame("Nueva deuda", close) {
        Field("Nombre (Cashea, préstamo...)", title, { title = it })
        Field("Monto inicial USD", amount, { amount = it }, true)
        Field("Categoría", category, { category = it })
        Field("Vence (AAAA-MM-DD, opcional)", due, { due = it })
        if (!validDue) Text("Formato de fecha no válido", color = Red, fontSize = 11.sp)
        Button(onClick = { save(title.trim(), a, category, due.trim()) }, modifier = Modifier.fillMaxWidth(), enabled = title.isNotBlank() && a > 0 && validDue) { Text("Guardar deuda") }
    }
}

@Composable
fun BillDialog(initial: Bill?, save: (String, Double, Int, Double, Boolean) -> Unit, close: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var amount by remember { mutableStateOf(initial?.let { plain(it.amount) } ?: "") }
    var day by remember { mutableStateOf((initial?.day ?: 1).toString()) }
    var arrears by remember { mutableStateOf(plain(initial?.arrears ?: 0.0)) }
    var repeat by remember { mutableStateOf(initial?.repeat ?: true) }
    val a = num(amount) ?: 0.0
    DialogFrame(if (initial == null) "Nuevo pago recurrente" else "Editar pago", close) {
        if (initial == null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Alquiler", "Colegio", "Condominio").forEach { n -> AssistChip(onClick = { title = n }, label = { Text(n, fontSize = 11.sp) }) }
        }
        Field("Concepto (alquiler, colegio...)", title, { title = it })
        Field("Monto mensual USD", amount, { amount = it }, true)
        Field("Día de vencimiento (1-31)", day, { day = it }, true)
        Field("Atrasos acumulados USD", arrears, { arrears = it }, true)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Repetir cada mes", modifier = Modifier.weight(1f)); Switch(repeat, { repeat = it }) }
        Button(onClick = { save(title.trim(), a, (day.toIntOrNull() ?: 1).coerceIn(1, 31), num(arrears) ?: 0.0, repeat) }, modifier = Modifier.fillMaxWidth(), enabled = title.isNotBlank() && a > 0) { Text("Guardar pago") }
    }
}

@Composable
fun PayDebtDialog(debt: Debt, save: (Double) -> Unit, close: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    val a = num(amount) ?: 0.0
    DialogFrame("Abonar a ${debt.title}", close) {
        Text("Saldo: ${money(debt.balance)}")
        Field("Monto del abono USD", amount, { amount = it }, true)
        Button(onClick = { save(a) }, modifier = Modifier.fillMaxWidth(), enabled = a > 0) { Text("Registrar abono") }
    }
}
