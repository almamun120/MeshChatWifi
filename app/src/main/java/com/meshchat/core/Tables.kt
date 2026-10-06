package com.meshchat.core

/** A node heard over BLE advertisements (RSSI) and/or connected through a GATT link. Hop = 1 by definition. */
data class Neighbor(
    val nodeId: String,
    val rssi: Int,          // 0 = unknown
    val caps: Int,
    val lastSeen: Long,
    val linked: Boolean,
)

class NeighborTable(private val timeoutMs: Long) {
    private val map = LinkedHashMap<String, Neighbor>()

    @Synchronized
    fun seen(id: String, rssi: Int, caps: Int, now: Long) {
        val old = map[id]
        val smoothed = if (old != null && old.rssi != 0) (old.rssi * 3 + rssi) / 4 else rssi
        map[id] = Neighbor(id, smoothed, caps, now, old?.linked ?: false)
    }

    /** Any frame from a linked peer proves it is alive. */
    @Synchronized
    fun touch(id: String, now: Long) {
        val old = map[id] ?: return
        map[id] = old.copy(lastSeen = now)
    }

    @Synchronized
    fun setLinked(id: String, linked: Boolean, now: Long) {
        val old = map[id]
        map[id] = (old ?: Neighbor(id, 0, 0, now, linked)).copy(linked = linked, lastSeen = now)
    }

    @Synchronized
    fun expire(now: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val n = it.next().value
            if (!n.linked && now - n.lastSeen > timeoutMs) it.remove()
        }
    }

    @Synchronized
    fun snapshot(): List<Neighbor> = map.values.sortedByDescending { if (it.rssi == 0) -999 else it.rssi }
}

data class Route(val dest: String, val nextHop: String, val hops: Int, val updated: Long)

/** Distance-vector table. Application-level routing: next hop is always a directly linked peer. */
class RoutingTable(private val ttlMs: Long) {
    private val map = HashMap<String, Route>()

    /** Returns true if the table changed in a way that matters (new dest / next hop / hop count). */
    @Synchronized
    fun update(dest: String, nextHop: String, hops: Int, now: Long): Boolean {
        val e = map[dest]
        if (e == null || e.nextHop == nextHop || hops < e.hops || now - e.updated > ttlMs) {
            map[dest] = Route(dest, nextHop, hops, now)
            return e == null || e.nextHop != nextHop || e.hops != hops
        }
        return false
    }

    @Synchronized
    fun lookup(dest: String, now: Long): Route? {
        val r = map[dest] ?: return null
        return if (now - r.updated <= ttlMs) r else null
    }

    /** Link to [peer] went down: drop everything that depended on it. */
    @Synchronized
    fun removeVia(peer: String): Boolean {
        val before = map.size
        map.entries.removeIf { it.value.nextHop == peer || it.key == peer }
        return map.size != before
    }

    @Synchronized
    fun expire(now: Long) {
        map.entries.removeIf { now - it.value.updated > ttlMs }
    }

    @Synchronized
    fun snapshot(now: Long): List<Route> =
        map.values.filter { now - it.updated <= ttlMs }.sortedWith(compareBy({ it.hops }, { it.dest }))

    /** Route advertisement for [peer] with split horizon (never advertise a route back to where it came from). */
    @Synchronized
    fun advertisementsFor(peer: String, now: Long, limit: Int): List<RouteAd> =
        map.values
            .filter { it.nextHop != peer && it.dest != peer && now - it.updated <= ttlMs && it.hops < Protocol.MAX_HOPS }
            .sortedBy { it.hops }
            .take(limit)
            .map { RouteAd(it.dest, it.hops) }
}
