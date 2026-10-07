package com.meshchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceAndEmailRulesTest {
    @Test
    fun distanceFromSignalAndHops() {
        assertEquals(1.0, Distance.meters(1, -59)!!, 0.01)
        assertEquals(10.0, Distance.meters(1, -84)!!, 0.01)
        assertEquals(60.0, Distance.meters(3, null)!!, 0.01)
        assertEquals(20.0, Distance.meters(1, null)!!, 0.01)
        assertNull(Distance.meters(null, -50))
        assertTrue(Distance.meters(1, -70)!! < Distance.meters(1, -90)!!)       // weaker signal = farther
    }

    @Test
    fun distanceIsShownInMetresOrKilometres() {
        assertEquals("12 m", Distance.format(12.4))
        assertEquals("1 m", Distance.format(0.2))
        assertEquals("1.5 km", Distance.format(1500.0))
        assertEquals("1.0 km", Distance.format(999.6))
    }

    @Test
    fun emailIsOptionalButMustLookLikeOne() {
        assertTrue(ProfileRules.isValidEmail(""))
        assertTrue(ProfileRules.isValidEmail("  "))
        assertTrue(ProfileRules.isValidEmail("a@b.co"))
        assertFalse(ProfileRules.isValidEmail("a@b"))
        assertFalse(ProfileRules.isValidEmail("a b@c.de"))
        assertFalse(ProfileRules.isValidEmail("x".repeat(60) + "@example.com"))
    }

    @Test
    fun identityCarriesTheOptionalEmailAndKeepsTheOldFormatWithoutIt() {
        val id = Identity.generate()
        val plain = Payloads.encodeIdentity(Payloads.Identity(3, "X", id.publicBytes))
        assertEquals(1 + 1 + 1 + 1 + id.publicBytes.size, plain.size)           // caps, name len, name, key len, key
        val d0 = Payloads.decodeIdentity(plain)!!
        assertEquals("", d0.email)
        val withMail = Payloads.encodeIdentity(Payloads.Identity(3, "X", id.publicBytes, "x@example.com"))
        val d1 = Payloads.decodeIdentity(withMail)!!
        assertEquals("x@example.com", d1.email)
        assertArrayEquals(id.publicBytes, d1.publicKey)
        assertNull(Payloads.decodeIdentity(withMail + byteArrayOf(1, 2)))       // trailing junk is rejected
    }
}

class GroupAdminTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = FakeNet()
    private val config = MeshConfig(
        tickMs = 50, pendingTickMs = 100, identityIntervalMs = 400, intervalOverrideMs = 200,
        retryBackoffMs = listOf(300L, 600L, 900L), maxAttempts = 3,
    )

    private class T(val id: Identity, val store: InMemoryStore, val engine: MeshEngine) { val nid get() = id.nodeId }

    private fun node(name: String, email: String = ""): T {
        val id = Identity.generate()
        val store = InMemoryStore()
        val e = MeshEngine(id, { name }, FakeTransport(id.nodeId, net), store, scope, config, emailProvider = { email })
        e.start()
        return T(id, store, e)
    }

    @After fun tearDown() { scope.cancel() }

    private suspend fun until(timeoutMs: Long = 10000, cond: () -> Boolean) { withTimeout(timeoutMs) { while (!cond()) delay(25) } }

    private suspend fun trio(): Triple<T, T, T> {
        val a = node("A"); val b = node("B"); val c = node("C")
        net.link(a.nid, b.nid); net.link(b.nid, c.nid); net.link(a.nid, c.nid)
        until { listOf(a to b, a to c, b to c).all { (x, y) -> x.store.nodes[y.nid]?.publicKey != null && y.store.nodes[x.nid]?.publicKey != null } }
        return Triple(a, b, c)
    }

    @Test
    fun emailIsBroadcastWithTheName() = runBlocking {
        val a = node("A", "a@example.com"); val b = node("B")
        net.link(a.nid, b.nid)
        until { b.store.nodes[a.nid]?.publicKey != null }
        until { b.store.nodes[a.nid]?.email == "a@example.com" }
        assertEquals("", a.store.nodes[b.nid]?.email ?: "")
    }

    @Test
    fun onlyTheCreatorRenames() = runBlocking {
        val (a, b, c) = trio()
        val gid = a.engine.createGroup("Team", listOf(b.nid, c.nid))!!
        until { b.store.groups.containsKey(gid) && c.store.groups.containsKey(gid) }
        assertFalse(b.engine.updateGroup(gid, "Hijack", null))
        assertTrue(a.engine.updateGroup(gid, "Renamed", null))
        until { b.store.groups[gid]?.name == "Renamed" && c.store.groups[gid]?.name == "Renamed" }
        assertEquals("Renamed", a.store.groups[gid]!!.name)
    }

    @Test
    fun creatorAddsAndRemovesMembers() = runBlocking {
        val (a, b, c) = trio()
        val gid = a.engine.createGroup("Duo", listOf(b.nid))!!
        until { b.store.groups.containsKey(gid) }
        assertNull(c.store.groups[gid])
        assertTrue(a.engine.updateGroup(gid, null, listOf(b.nid, c.nid)))        // add C
        until { c.store.groups.containsKey(gid) && b.store.groups[gid]!!.members.size == 3 }
        assertTrue(a.engine.updateGroup(gid, null, listOf(b.nid)))               // remove C again
        until { c.store.groups[gid] == null && b.store.groups[gid]!!.members.size == 2 }
        assertFalse(a.store.groups[gid]!!.members.contains(c.nid))
    }

    @Test
    fun creatorDeletingTheGroupDeletesItForEveryone() = runBlocking {
        val (a, b, c) = trio()
        val gid = a.engine.createGroup("Team", listOf(b.nid, c.nid))!!
        until { b.store.groups.containsKey(gid) && c.store.groups.containsKey(gid) }
        a.engine.leaveGroup(gid)
        until { b.store.groups[gid] == null && c.store.groups[gid] == null }
        assertNull(a.store.groups[gid])
    }

    @Test
    fun badGroupUpdatesAreRefused() = runBlocking {
        val (a, b, _) = trio()
        val gid = a.engine.createGroup("Duo", listOf(b.nid))!!
        until { b.store.groups.containsKey(gid) }
        assertFalse(a.engine.updateGroup(gid, null, emptyList()))                // a group needs 2+ people
        assertFalse(a.engine.updateGroup(gid, null, listOf(b.nid, "DEADBEEF")))  // unknown key
        assertFalse(a.engine.updateGroup(gid, "   ", null))                      // empty name
        assertNotNull(a.store.groups[gid])
    }
}
