package com.meshchat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityBackupTest {
    private val id = Identity.generate()
    private val pass = "correct horse".toCharArray()
    private fun file() = IdentityBackup.export(id, "রহিম \"Rahim\"", 10_000, true, pass, iterations = 2_000)

    @Test
    fun roundTripRestoresTheSameNodeId() {
        val r = IdentityBackup.restore(file(), pass)
        val d = r.data!!
        assertEquals(id.nodeId, d.nodeId)
        assertArrayEquals(id.privateBytes, d.privatePkcs8)
        assertEquals("রহিম \"Rahim\"", d.name)
        assertEquals(10_000L, d.dobEpochDay)
        assertTrue(d.showDob)
        assertEquals(id.nodeId, Identity.restore(d.privatePkcs8, d.publicX509).nodeId)
    }

    @Test
    fun wrongPassphraseIsRejected() {
        val r = IdentityBackup.restore(file(), "wrong pass".toCharArray())
        assertNull(r.data)
        assertEquals(RestoreError.WRONG_PASSPHRASE, r.error)
    }

    @Test
    fun fileNeverContainsTheKeyInTheClear() {
        val f = file()
        val b64 = java.util.Base64.getEncoder().encodeToString(id.privateBytes)
        assertFalse(f.contains(b64))
        assertFalse(f.contains("Rahim"))
        assertEquals(id.nodeId, IdentityBackup.peekNodeId(f))
    }

    @Test
    fun tamperedOrForeignFilesAreRejected() {
        val f = file()
        val broken = f.replace("\"nodeId\":\"${id.nodeId}\"", "\"nodeId\":\"00000000\"")
        assertEquals(RestoreError.WRONG_PASSPHRASE, IdentityBackup.restore(broken, pass).error)   // header is authenticated (AAD)
        assertEquals(RestoreError.BAD_FILE, IdentityBackup.restore("not json", pass).error)
        assertEquals(RestoreError.BAD_FILE, IdentityBackup.restore("{\"app\":\"Other\",\"version\":1}", pass).error)
        assertNull(IdentityBackup.peekNodeId("{}"))
    }

    @Test
    fun shortPassphraseIsRefused() {
        var thrown = false
        try { IdentityBackup.export(id, "x", 1, false, "abc".toCharArray()) } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun jsonHandlesEscapesAndNumbers() {
        val m = Json.read(Json.write(linkedMapOf("a" to "line\nbreak \\ \"q\" ✓", "n" to -5L, "b" to true)))
        assertNotNull(m)
        assertEquals("line\nbreak \\ \"q\" ✓", m!!["a"])
        assertEquals(-5L, m["n"])
        assertEquals(true, m["b"])
        assertNull(Json.read("{\"a\":1} trailing"))
    }
}
