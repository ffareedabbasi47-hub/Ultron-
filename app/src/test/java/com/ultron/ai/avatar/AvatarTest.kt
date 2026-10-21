package com.ultron.ai.avatar

import com.ultron.ai.data.avatar.AvatarIds
import com.ultron.ai.data.avatar.GlbFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AvatarTest {
    private fun header(len: Long, version: Int = 2, magic: Int = 0x46546C67): ByteArray {
        val b = ByteArray(12)
        fun put(o: Int, v: Long) { for (i in 0..3) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        put(0, magic.toLong() and 0xFFFFFFFFL); put(4, version.toLong()); put(8, len)
        return b
    }

    @Test fun validGlbHeaderPasses() = assertNull(GlbFiles.checkHeader(header(1000), 1000))
    @Test fun wrongMagicRejected() = assertNotNull(GlbFiles.checkHeader(header(1000, magic = 0x12345678), 1000))
    @Test fun wrongVersionRejected() = assertNotNull(GlbFiles.checkHeader(header(1000, version = 1), 1000))
    @Test fun truncatedRejected() = assertNotNull(GlbFiles.checkHeader(header(1000), 640))
    @Test fun tooShortRejected() = assertNotNull(GlbFiles.checkHeader(ByteArray(5), 5))
    @Test fun oversizeRejected() = assertNotNull(GlbFiles.checkHeader(header(GlbFiles.MAX_BYTES + 1), GlbFiles.MAX_BYTES + 1))

    @Test fun sanitizeNames() {
        assertEquals("My-Model-v2.glb", GlbFiles.sanitize("My Model (v2).GLB"))
        assertEquals("model.glb", GlbFiles.sanitize(""))
        assertEquals("model.glb", GlbFiles.sanitize(null))
        assertEquals("passwd.glb", GlbFiles.sanitize("../../etc/passwd"))
        assertEquals("robot.glb", GlbFiles.sanitize("robot.glb?download=1#x"))
        assertTrue(GlbFiles.isSafeFileName(GlbFiles.sanitize("तस्वीर 🤖")))
    }

    @Test fun traversalNamesNeverSafe() {
        for (bad in listOf("../a.glb", "a/b.glb", "a\\b.glb", ".hidden.glb", "a..b.glb", "x.glb.exe", "", "a.GLB"))
            assertFalse(bad, GlbFiles.isSafeFileName(bad))
        assertTrue(GlbFiles.isSafeFileName("robot-2.glb"))
    }

    @Test fun avatarIds() {
        assertEquals("robot.glb", AvatarIds.fileOf(AvatarIds.custom("robot.glb")))
        assertNull(AvatarIds.fileOf("custom:../x.glb"))
        assertNull(AvatarIds.fileOf(AvatarIds.CORE))
    }

    @Test fun httpsOnly() {
        assertTrue(GlbFiles.isHttpsUrl("https://example.com/a.glb"))
        assertFalse(GlbFiles.isHttpsUrl("http://example.com/a.glb"))
        assertFalse(GlbFiles.isHttpsUrl("file:///sdcard/a.glb"))
        assertFalse(GlbFiles.isHttpsUrl("https://exa mple.com"))
    }

    @Test fun geometryIsWellFormed() {
        val meshes = listOf(
            Geometry.superE(1f, .5f, .7f, .5f, .5f), Geometry.cyl(.3f, .5f, 1f, 24), Geometry.torus(1f, .1f, 32, 8), Geometry.box(1f, 2f, 3f),
        )
        for (m in meshes) {
            assertTrue(m.triangles > 0)
            assertTrue(m.idx.all { it in 0 until m.p.size / 3 })
            var k = 0
            while (k < m.n.size) {
                val l = sqrt(m.n[k] * m.n[k] + m.n[k + 1] * m.n[k + 1] + m.n[k + 2] * m.n[k + 2])
                assertTrue("normal length $l", abs(l - 1f) < 1e-3f)
                k += 3
            }
            assertTrue(m.p.all { it.isFinite() } && m.n.all { it.isFinite() })
        }
    }

    @Test fun sphereWindingFacesOutward() {
        val m = Geometry.superE(1f, 1f, 1f, 1f, 1f, 24, 12)
        var outward = 0; var total = 0
        var i = 0
        while (i < m.idx.size) {
            val a = m.idx[i] * 3; val b = m.idx[i + 1] * 3; val c = m.idx[i + 2] * 3
            val ux = m.p[b] - m.p[a]; val uy = m.p[b + 1] - m.p[a + 1]; val uz = m.p[b + 2] - m.p[a + 2]
            val vx = m.p[c] - m.p[a]; val vy = m.p[c + 1] - m.p[a + 1]; val vz = m.p[c + 2] - m.p[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            if (nx * nx + ny * ny + nz * nz < 1e-12f) { i += 3; continue } // zero-area pole triangles
            val cx = m.p[a] + m.p[b] + m.p[c]; val cy = m.p[a + 1] + m.p[b + 1] + m.p[c + 1]; val cz = m.p[a + 2] + m.p[b + 2] + m.p[c + 2]
            if (nx * cx + ny * cy + nz * cz > 0) outward++
            total++; i += 3
        }
        assertTrue("outward $outward/$total", outward.toDouble() / total > 0.98)
    }

    @Test fun sceneBuildsAndAnimatesWithoutNaN() {
        val s = UltronScene()
        assertTrue(s.nodes.count { it.mesh != null } > 40)
        for (mood in AvatarMood.values()) {
            s.mood = mood
            repeat(120) { s.animate(1f / 30f) }
            for (n in s.nodes) assertTrue("${n.name} world finite", n.world.all { it.isFinite() })
            assertTrue(s.eyeIntensity.isFinite())
        }
        // accent colour converges to the state colour
        s.mood = AvatarMood.SPEAKING
        repeat(300) { s.animate(1f / 30f) }
        assertTrue(abs(s.accentColor[0] - 1f) < .05f && abs(s.accentColor[1] - .16f) < .05f)
    }

    @Test fun reducedMotionIsStatic() {
        val s = UltronScene(reducedMotion = true)
        s.animate(.5f)
        val a = s.nodes.first { it.name == "Head" }.world.copyOf()
        s.animate(.5f)
        val b = s.nodes.first { it.name == "Head" }.world
        assertTrue(a.indices.all { abs(a[it] - b[it]) < 1e-5f })
    }
}
