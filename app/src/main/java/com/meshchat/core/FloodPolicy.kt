package com.meshchat.core

/**
 * v2.00 flood control. A broadcast (public post, identity) is relayed by every phone that hears it. That is fine in a
 * small group and a disaster in a crowd, so how far and how eagerly a phone relays now depends on how many phones it
 * can hear around it ("density"), or on values the user fixed by hand.
 *
 * Pure Kotlin, no Android classes: unit-tested on the JVM.
 */
enum class FloodMode { AUTO, MANUAL }

/** One-tap manual setups. They only fill in the manual values; the user can still adjust each one afterwards. */
enum class FloodPreset(val label: String, val hint: String) {
    SMALL("Small group", "Few phones around. Messages travel far, everything is relayed, identities are shared with everyone."),
    CROWD("Crowd", "Market, campus, rally. Short reach, relays stay quiet when neighbours already passed a message on."),
    STADIUM("Stadium", "Thousands of phones. Reach of 2 hops, identities only to direct neighbours, 1 public post per 5 minutes."),
    LONG_RANGE("Long range", "Thin network over a large area. Maximum reach, nothing is held back."),
}

/** Everything a relay needs to decide what to do with a broadcast. */
data class FloodParams(
    /** A relayed broadcast never carries more than this many hops left, whatever the sender asked for. 1..[Protocol.MAX_HOPS]. */
    val maxTtl: Int,
    /** Cancel our own relay once this many copies of the message (the first one included) were heard while waiting. 2..5, or 0 = never cancel (always relay). */
    val suppressK: Int,
    /** Wait a random time in [jitterMinMs, jitterMaxMs] before relaying, so neighbours do not all transmit at once. */
    val jitterMinMs: Int,
    val jitterMaxMs: Int,
    /** Public posts per author: a burst of this many is allowed, then one more every [postEveryMs]. Applies to our own posts and to posts we relay. */
    val postBurst: Int,
    val postEveryMs: Long,
    /** true = our identity (name + key) is relayed across the mesh; false = given to direct neighbours only (others ask for it when they need it). */
    val identityFlood: Boolean,
    val identityEveryMs: Long,
)

data class FloodSettings(
    val mode: FloodMode = FloodMode.AUTO,
    /** Used only when [mode] is MANUAL. */
    val manual: FloodParams = FloodPolicy.preset(FloodPreset.SMALL),
)

/** What the settings screen shows: how crowded it is around us and what we are doing about it. */
data class FloodStatus(
    val density: Int,
    val band: String,
    val mode: FloodMode,
    val params: FloodParams,
)

object FloodPolicy {
    const val MIN_TTL = 1
    val MAX_TTL = Protocol.MAX_HOPS
    const val MAX_K = 5
    const val MAX_JITTER_MS = 10_000
    const val MAX_BURST = 20
    const val MIN_POST_EVERY_MS = 1_000L
    const val MAX_POST_EVERY_MS = 3_600_000L
    const val MIN_IDENTITY_EVERY_MS = 30_000L
    const val MAX_IDENTITY_EVERY_MS = 3_600_000L

    private val SPARSE = FloodParams(
        maxTtl = Protocol.DEFAULT_TTL, suppressK = 3, jitterMinMs = 10, jitterMaxMs = 150,
        postBurst = 5, postEveryMs = 12_000, identityFlood = true, identityEveryMs = 60_000,
    )
    private val NORMAL = FloodParams(
        maxTtl = 8, suppressK = 3, jitterMinMs = 40, jitterMaxMs = 500,
        postBurst = 4, postEveryMs = 30_000, identityFlood = true, identityEveryMs = 180_000,
    )
    private val DENSE = FloodParams(
        maxTtl = 3, suppressK = 2, jitterMinMs = 100, jitterMaxMs = 1_500,
        postBurst = 2, postEveryMs = 120_000, identityFlood = false, identityEveryMs = 300_000,
    )
    private val CROWDED = FloodParams(
        maxTtl = 2, suppressK = 2, jitterMinMs = 200, jitterMaxMs = 2_500,
        postBurst = 1, postEveryMs = 300_000, identityFlood = false, identityEveryMs = 600_000,
    )
    private val LONG = FloodParams(
        maxTtl = Protocol.MAX_HOPS, suppressK = 3, jitterMinMs = 20, jitterMaxMs = 300,
        postBurst = 5, postEveryMs = 12_000, identityFlood = true, identityEveryMs = 120_000,
    )

    fun preset(p: FloodPreset): FloodParams = when (p) {
        FloodPreset.SMALL -> SPARSE
        FloodPreset.CROWD -> DENSE
        FloodPreset.STADIUM -> CROWDED
        FloodPreset.LONG_RANGE -> LONG
    }

    /** Name of the automatic band for [density] phones heard around us. */
    fun band(density: Int): String = when {
        density <= 6 -> "Sparse"
        density <= 20 -> "Normal"
        density <= 60 -> "Dense"
        else -> "Crowded"
    }

    fun forDensity(density: Int): FloodParams = when {
        density <= 6 -> SPARSE
        density <= 20 -> NORMAL
        density <= 60 -> DENSE
        else -> CROWDED
    }

    /** Clamps every value into its allowed range, so a bad stored value or a typo can never switch relaying off or flood the air. */
    fun sanitize(p: FloodParams): FloodParams {
        val jmin = p.jitterMinMs.coerceIn(0, MAX_JITTER_MS)
        return FloodParams(
            maxTtl = p.maxTtl.coerceIn(MIN_TTL, MAX_TTL),
            suppressK = p.suppressK.coerceIn(0, MAX_K).let { if (it == 1) 2 else it },   // 1 would mean "never relay anything"
            jitterMinMs = jmin,
            jitterMaxMs = p.jitterMaxMs.coerceIn(jmin, MAX_JITTER_MS),
            postBurst = p.postBurst.coerceIn(1, MAX_BURST),
            postEveryMs = p.postEveryMs.coerceIn(MIN_POST_EVERY_MS, MAX_POST_EVERY_MS),
            identityFlood = p.identityFlood,
            identityEveryMs = p.identityEveryMs.coerceIn(MIN_IDENTITY_EVERY_MS, MAX_IDENTITY_EVERY_MS),
        )
    }

    fun effective(s: FloodSettings, density: Int): FloodParams =
        if (s.mode == FloodMode.AUTO) forDensity(density) else sanitize(s.manual)
}

/**
 * Number of phones heard around us, smoothed: it jumps up at once when a crowd arrives and falls slowly when it
 * leaves, so the policy does not flap between bands every second. Call [update] once per second.
 */
class DensityTracker(private val fall: Double = 0.97) {
    private var v = 0.0

    @Synchronized
    fun update(count: Int): Int {
        v = if (count >= v) count.toDouble() else v * fall + count * (1.0 - fall)
        return Math.round(v).toInt()
    }

    @Synchronized
    fun current(): Int = Math.round(v).toInt()
}
