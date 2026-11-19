package com.dzaky.strafunk.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Fmt {
    val DAYS = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min") // index = dayOfWeek-1
    val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

    fun key(d: LocalDate): String = d.toString()
    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    fun km(m: Double, dp: Int = 2): String = String.format(Locale.US, "%.${dp}f", m / 1000.0).replace('.', ',')
    fun num(n: Long): String = String.format(Locale.GERMANY, "%,d", n)
    fun num(n: Int): String = num(n.toLong())

    fun dur(sec: Int): String {
        val s = maxOf(0, sec); val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, x) else String.format(Locale.US, "%02d:%02d", m, x)
    }

    fun pace(secPerKm: Double): String {
        if (secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm <= 0 || secPerKm > 3600) return "--'--\""
        var s = (secPerKm % 60).roundToInt(); val m = (secPerKm / 60).toInt()
        if (s == 60) s = 59
        return "$m'" + (if (s < 10) "0" else "") + s + "\""
    }

    fun speed(m: Double, sec: Double): String =
        if (sec <= 0) "0" else String.format(Locale.US, "%.1f", (m / 1000.0) / (sec / 3600.0)).replace('.', ',')

    fun dateStr(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return DAYS[z.dayOfWeek.value - 1] + ", " + z.dayOfMonth + " " + MONTHS[z.monthValue - 1] + " · " +
            String.format(Locale.US, "%02d.%02d", z.hour, z.minute)
    }

    fun dayLabel(d: LocalDate): String = DAYS[d.dayOfWeek.value - 1] + ", " + d.dayOfMonth + " " + MONTHS[d.monthValue - 1]

    fun partOfDay(hour: Int) = when { hour < 11 -> "pagi"; hour < 15 -> "siang"; hour < 18 -> "sore"; else -> "malam" }

    fun initials(n: String): String =
        n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }

    fun kcal(type: String, meters: Double, sec: Double, weight: Int): Int {
        val h = sec / 3600.0; val kmh = if (h > 0) meters / 1000.0 / h else 0.0
        val met = when (type) { "Jalan" -> 3.8; "Sepeda" -> if (kmh > 20) 8.0 else 6.0; else -> maxOf(6.0, kmh * 1.02) }
        return (met * weight * h).roundToInt()
    }

    fun hav(a: DoubleArray, b: DoubleArray): Double {
        val r = Math.PI / 180; val dLa = (b[0] - a[0]) * r; val dLo = (b[1] - a[1]) * r
        val x = sin(dLa / 2).let { it * it } + cos(a[0] * r) * cos(b[0] * r) * sin(dLo / 2).let { it * it }
        return 2 * 6371000.0 * asin(sqrt(x))
    }

    fun <T> downsample(a: List<T>, max: Int): List<T> {
        if (a.size <= max) return a
        val step = (a.size - 1).toDouble() / (max - 1)
        return List(max) { a[(it * step).roundToInt()] }
    }
}
