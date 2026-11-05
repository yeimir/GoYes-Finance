# GoYes Finance — Android project

Versión 1.1.0 · marca GoYes · datos 100 % locales (sin cuentas bancarias ni nube).

## Novedades de la v1.1
- **Salario fijo configurable** (Más > Ajustes): concepto, monto y día del mes. Se registra solo como ingreso cada mes; si la app no se abre por varios meses, se completan los que falten.
- **Pagos recurrentes automáticos**: alquiler, colegio, condominio y servicios se generan cada mes (también el mes siguiente, para verlos con anticipación). Se pueden editar, eliminar o marcar como "solo este mes".
- **Calendario de vencimientos** (Pagos > Calendario) con pagos y deudas, por colores: pendiente, vencido, pagado.
- **Alertas**: tarjeta en Inicio y notificación diaria a las 9:00 (configurable con 1–7 días de anticipación). Se reprograma al reiniciar el teléfono.
- **Presupuesto mensual** (Más > Presupuesto): ingresos − gastos − pagos recurrentes − abonos − atrasos = disponible después de compromisos.
- **Historial por mes** (Más > Historial): ingresos, gastos, pagos y abonos de cualquier mes anterior.
- **Simulador** (Más > Simulador): reparte un ingreso entre compromisos, gastos variables, ahorro y deudas (menor saldo, vence primero o proporcional) y permite registrar los abonos del plan.

## Reglas del presupuesto
- Los pagos recurrentes y los abonos a deudas se descuentan automáticamente: **no los registres además como gasto**.
- "Atrasos" = pagos sin pagar de meses anteriores + atrasos iniciales declarados en cada pago recurrente.
- Los abonos hechos con la v1.0 no tienen fecha, así que no aparecen en el historial; los nuevos sí.

## Migración desde la v1.0
Al abrir la v1.1 sobre datos de la v1.0, el estado pagado/pendiente de cada pago pasa a ser el del mes actual. Los respaldos JSON antiguos se pueden importar.

## Requisitos
- Android Studio reciente, JDK 17, Android SDK 35
- Conexión inicial a Internet para descargar dependencias Gradle

## Compilar
1. Abre esta carpeta desde Android Studio y espera la sincronización de Gradle.
2. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
3. APK: `app/build/outputs/apk/debug/app-debug.apk` (o `gradle assembleDebug`).

## Estructura
- `Models.kt` modelos · `Store.kt` persistencia y respaldo · `Logic.kt` generación automática, presupuesto, alertas, calendario y simulador
- `Notifications.kt` alarma diaria y notificaciones · `MainActivity.kt` interfaz (Compose)

## Límites
- Moneda USD. Sin autenticación ni sincronización en la nube.
- Las alertas usan alarma inexacta: pueden llegar con algunos minutos de diferencia.
