package com.meshchat.core

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Rough distance to a node, from what the mesh knows: hop count and, for a directly heard phone, the received signal
 * strength (RF power). It is an ESTIMATE: walls, bodies and phone antennas change RSSI by 10-20 dB.
 */
object Distance {
    /** Typical RSSI of a BLE phone at 1 m. */
    const val RSSI_AT_1M = -59
    /** Path-loss exponent: 2 = free space, 2.5-3 = indoors. */
    const val PATH_LOSS = 2.5
    /** Assumed spacing between relaying phones when only the hop count is known. */
    const val METERS_PER_HOP = 20.0

    /** Metres, or null when the node is not reachable (hops == null). */
    fun meters(hops: Int?, rssi: Int?): Double? = when {
        hops == null -> null
        hops <= 1 && rssi != null -> 10.0.pow((RSSI_AT_1M - rssi) / (10.0 * PATH_LOSS)).coerceIn(0.5, 200.0)
        else -> maxOf(hops, 1) * METERS_PER_HOP
    }

    fun format(m: Double): String {
        val r = m.roundToInt()
        return if (r < 1000) "${maxOf(1, r)} m" else String.format(Locale.US, "%.1f km", m / 1000.0)
    }
}

object ProfileRules {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    /** Blank is fine (the field is optional). */
    fun isValidEmail(s: String): Boolean {
        val t = s.trim()
        return t.isEmpty() || (EMAIL.matches(t) && t.toByteArray(Charsets.UTF_8).size <= Payloads.MAX_EMAIL_BYTES)
    }
}
