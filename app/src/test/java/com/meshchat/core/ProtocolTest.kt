package com.meshchat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private fun sample(payloadSize: Int = 10, sig: ByteArray? = null) = MeshPacket(
        PacketType.ANNOUNCE, 0, 7, 0, ByteArray(8) { it.toByte() }, "7F3A92B1", NodeIds.BROADCAST,
        1_700_000_000_000L, ByteArray(payloadSize) { (it * 3).toByte() }, sig,
    )

    @Test
    fun packetRoundTrip() {
        val p = sample(sig = ByteArray(70) { 9 })
        val d = MeshPacket.decode(p.encode())
        assertNotNull(d)
        d!!
        assertEquals(p.type, d.type)
        assertEquals(p.ttl, d.ttl)
        assertEquals(p.src, d.src)
        assertEquals(p.dst, d.dst)
        assertEquals(p.timestamp, d.timestamp)
        assertArrayEquals(p.payload, d.payload)
        assertArrayEquals(p.signature, d.signature)
        assertEquals(p.key, d.key)
    }

    @Test
    fun malformedPacketsAreRejected() {
        assertNull(MeshPacket.decode(ByteArray(5)))
        val good = sample().encode()
        assertNull(MeshPacket.decode(good.copyOf(good.size - 1)))       // truncated payload
        assertNull(MeshPacket.decode(good + byteArrayOf(1)))              // trailing garbage
        val badVersion = good.copyOf().also { it[0] = 9 }
        assertNull(MeshPacket.decode(badVersion))
        val badType = good.copyOf().also { it[1] = 99 }
        assertNull(MeshPacket.decode(badType))
    }

    @Test
    fun forwardedDecrementsTtlAndIncrementsHops() {
        val f = sample().forwarded()
        assertEquals(6, f.ttl)
        assertEquals(1, f.hops)
    }

    @Test
    fun peekSrcMatches() {
        assertEquals("7F3A92B1", MeshPacket.peekSrc(sample().encode()))
    }

    @Test
    fun helloAnnounceIdentityRefsRoundTrip() {
        val h = Payloads.Hello("আব্দুল্লাহ", Cap.ALL, listOf(RouteAd("AABBCCDD", 2), RouteAd("01020304", 3)))
        val h2 = Payloads.decodeHello(Payloads.encodeHello(h))!!
        assertEquals("আব্দুল্লাহ", h2.name)
        assertEquals(2, h2.routes.size)
        assertEquals(RouteAd("01020304", 3), h2.routes[1])

        val a = Payloads.Announce("Rahim", "আজ বিকাল ৫টায় মাঠে মিটিং")
        assertEquals(a, Payloads.decodeAnnounce(Payloads.encodeAnnounce(a)))

        val id = Identity.generate()
        val i2 = Payloads.decodeIdentity(Payloads.encodeIdentity(Payloads.Identity(3, "X", id.publicBytes)))!!
        assertArrayEquals(id.publicBytes, i2.publicKey)

        val refs = listOf(PacketRef("AABBCCDD", "0011223344556677"), PacketRef("01020304", "8899AABBCCDDEEFF"))
        assertEquals(refs, Payloads.decodeRefs(Payloads.encodeRefs(refs)))
    }

    @Test
    fun nameIsClampedWithoutBreakingUtf8() {
        val long = "আ".repeat(40)  // 3 bytes each
        val bytes = Payloads.clampUtf8(long, 32)
        assertTrue(bytes.size <= 32)
        assertEquals(long.substring(0, bytes.toString(Charsets.UTF_8).length), bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun advertisementCarriesOnlyProtocolCapsAndId() {
        val adv = Protocol.encodeAdvert(Cap.CHAT or Cap.RELAY, "7F3A92B1")
        assertEquals(6, adv.size)
        assertEquals(Cap.CHAT or Cap.RELAY to "7F3A92B1", Protocol.decodeAdvert(adv))
    }
}

class FragmentationTest {
    @Test
    fun splitAndReassembleVariousSizesAndMtus() {
        for (maxFrame in listOf(20, 100, 244)) {
            for (size in listOf(0, 1, 17, 18, 500, 1999)) {
                val data = ByteArray(size) { (it % 251).toByte() }
                val frames = Fragmenter().split(data, maxFrame)
                assertTrue(frames.all { it.size <= maxFrame })
                val re = Reassembler()
                var out: ByteArray? = null
                for ((i, f) in frames.withIndex()) {
                    out = re.accept(f)
                    if (i < frames.size - 1) assertNull(out)
                }
                assertArrayEquals("size=$size mtu=$maxFrame", data, out)
            }
        }
    }

    @Test
    fun lostFragmentDropsPacketAndRecovers() {
        val data = ByteArray(100) { it.toByte() }
        val f = Fragmenter()
        val frames = f.split(data, 20)
        val re = Reassembler()
        re.accept(frames[0])
        re.accept(frames[1])
        // frames[2] lost
        assertNull(re.accept(frames[3]))
        // a fresh packet still works afterwards
        val next = f.split(data, 20)
        var out: ByteArray? = null
        for (x in next) out = re.accept(x)
        assertArrayEquals(data, out)
    }

    @Test
    fun timeoutDiscardsPartial() {
        var now = 0L
        val re = Reassembler(timeoutMs = 1000, clock = { now })
        val frames = Fragmenter().split(ByteArray(60), 20)
        re.accept(frames[0])
        now = 5000
        assertNull(re.accept(frames[1]))
    }
}

class CacheTest {
    @Test
    fun duplicateCacheDetectsAndExpires() {
        var now = 0L
        val c = DuplicateCache(capacity = 3, ttlMs = 1000, clock = { now })
        assertFalse(c.checkAndAdd("a"))
        assertTrue(c.checkAndAdd("a"))
        now = 2000
        assertFalse(c.checkAndAdd("a"))   // expired -> fresh again
        c.checkAndAdd("b"); c.checkAndAdd("c"); c.checkAndAdd("d")   // evicts oldest
        assertTrue(c.size() <= 3)
    }

    @Test
    fun rateLimiterBlocksBursts() {
        var now = 0L
        val r = RateLimiter(capacity = 3, refillMs = 10_000, clock = { now })
        assertTrue(r.allow("x")); assertTrue(r.allow("x")); assertTrue(r.allow("x"))
        assertFalse(r.allow("x"))
        assertTrue(r.allow("y"))
        now = 10_000
        assertTrue(r.allow("x"))
    }
}

class RoutingTableTest {
    @Test
    fun prefersShorterRouteAndSplitHorizon() {
        val t = RoutingTable(ttlMs = 1000)
        assertTrue(t.update("D", "B", 3, 0))
        assertFalse(t.update("D", "C", 4, 10))        // longer via other hop: ignored
        assertTrue(t.update("D", "C", 2, 20))         // shorter: replaces
        assertEquals("C", t.lookup("D", 30)!!.nextHop)
        assertTrue(t.advertisementsFor("C", 30, 10).none { it.nodeId == "D" })   // split horizon
        assertTrue(t.advertisementsFor("B", 30, 10).any { it.nodeId == "D" })
    }

    @Test
    fun linkLossRemovesDependentRoutesAndRoutesExpire() {
        val t = RoutingTable(ttlMs = 1000)
        t.update("B", "B", 1, 0); t.update("D", "B", 3, 0); t.update("E", "X", 2, 0)
        assertTrue(t.removeVia("B"))
        assertEquals(null, t.lookup("D", 1))
        assertEquals(null, t.lookup("B", 1))
        assertNotNull(t.lookup("E", 1))
        assertEquals(null, t.lookup("E", 5000))
    }
}

class CryptoTest {
    @Test
    fun nodeIdIsDerivedFromKeyAndStable() {
        val a = Identity.generate()
        val restored = Identity.restore(a.privateBytes, a.publicBytes)
        assertEquals(a.nodeId, restored.nodeId)
        assertEquals(8, a.nodeId.length)
        assertTrue(NodeIds.isValid(a.nodeId))
    }

    @Test
    fun endToEndEncryptionOnlyRecipientCanRead() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val eve = Identity.generate()
        val id = ByteArray(8) { 1 }
        val aad = CryptoService.aad(alice.nodeId, bob.nodeId, id)
        val blob = CryptoService.encrypt(alice, bob.publicBytes, bob.nodeId, aad, "Hello Mesh".toByteArray())

        val plain = CryptoService.decrypt(bob, alice.publicBytes, alice.nodeId, aad, blob)
        assertEquals("Hello Mesh", String(plain!!))

        assertNull(CryptoService.decrypt(eve, alice.publicBytes, alice.nodeId, aad, blob))
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(CryptoService.decrypt(bob, alice.publicBytes, alice.nodeId, aad, tampered))
        val wrongAad = CryptoService.aad(alice.nodeId, bob.nodeId, ByteArray(8) { 2 })
        assertNull(CryptoService.decrypt(bob, alice.publicBytes, alice.nodeId, wrongAad, blob))
    }

    @Test
    fun signaturesVerifyAndRejectTampering() {
        val a = Identity.generate()
        val sig = CryptoService.sign(a, "data".toByteArray())
        assertTrue(CryptoService.verify(a.publicBytes, "data".toByteArray(), sig))
        assertFalse(CryptoService.verify(a.publicBytes, "datA".toByteArray(), sig))
        assertFalse(CryptoService.verify(Identity.generate().publicBytes, "data".toByteArray(), sig))
    }
}
