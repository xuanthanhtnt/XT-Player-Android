package com.xuanthanhtnt.xtplayer.core

import java.util.Locale
import kotlin.math.floor

object TimeFormat {
    fun clock(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0) return "0:00"
        val total = floor(seconds).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Always with hours, as on Infuse's Resume button: "00:12:15". */
    fun fullClock(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0) floor(seconds).toLong() else 0
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    /** "2 giờ 10 phút" / "45 phút". */
    fun runtime(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60} giờ ${minutes % 60} phút" else "$minutes phút"
}

object ByteFormat {
    fun file(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(Locale.US, if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
    }

    fun bitrate(bitsPerSecond: Double): String = when {
        bitsPerSecond <= 0 -> "—"
        bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000)
        else -> String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1000)
    }
}

/** Natural ("Finder") ordering: "Episode 2" before "Episode 10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ei
                j = ej
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
