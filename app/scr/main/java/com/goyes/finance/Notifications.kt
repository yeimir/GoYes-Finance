package com.goyes.finance

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.util.Calendar

object Alerts {
    private const val CHANNEL = "goyes_vencimientos"

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 77, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Revisión diaria a las 9:00 (alarma inexacta: no requiere permisos especiales). */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val first = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, first.timeInMillis, AlarmManager.INTERVAL_DAY, alarmIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    /** Publica la notificación si hay vencimientos. Devuelve true si se publicó. */
    fun notifyNow(context: Context, ignoreEnabled: Boolean = false): Boolean {
        val store = Store(context)
        Generator.run(store)
        val prefs = store.alertPrefs()
        if (!prefs.enabled && !ignoreEnabled) return false
        val items = buildAlerts(store.payments(), store.debts(), prefs.daysBefore, LocalDate.now())
        if (items.isEmpty()) return false

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Vencimientos", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val today = LocalDate.now()
        val overdue = items.count { it.overdue }
        val title = when {
            overdue > 0 && items.size > overdue -> "$overdue vencido(s) y ${items.size - overdue} próximo(s)"
            overdue > 0 -> "$overdue pago(s) vencido(s)"
            else -> "${items.size} vencimiento(s) próximo(s)"
        }
        val lines = items.take(6).joinToString("\n") { "${it.title} · ${money(it.amount)} · ${dueLabel(it.date, today)}" }
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("${items.first().title} · ${dueLabel(items.first().date, today)}")
            .setStyle(Notification.BigTextStyle().bigText(lines))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, n)
        return true
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Alerts.notifyNow(context) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store(context).alertPrefs().enabled) Alerts.schedule(context)
    }
}
