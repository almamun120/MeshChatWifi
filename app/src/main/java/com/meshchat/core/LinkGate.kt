package com.meshchat.core

/**
 * A phone has ONE Wi-Fi Direct radio, so only one Wi-Fi session (call or file transfer) can run at a time.
 * Both managers share this gate: whoever holds it makes every other incoming invite answer BUSY.
 */
class LinkGate {
    private var owner: String? = null

    @Synchronized
    fun tryAcquire(id: String): Boolean {
        if (owner == null || owner == id) {
            owner = id
            return true
        }
        return false
    }

    @Synchronized
    fun release(id: String) {
        if (owner == id) owner = null
    }

    @Synchronized
    fun isFree(): Boolean = owner == null
}
