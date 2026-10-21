package com.ultron.ai.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Triangle mesh with per-vertex normals. [idx] is a triangle list. */
class Mesh(val p: FloatArray, val n: FloatArray, val idx: IntArray) {
    val triangles get() = idx.size / 3
}

/** Procedural primitives used to build the original ULTRON avatar (no external assets). */
object Geometry {
    private fun fx(w: Float, m: Float): Float = sign(w) * abs(w).pow(m)

    /** Orients triangles outward (closed shapes) and computes smooth normals. */
    fun finish(p: FloatArray, idxIn: IntArray, mergeAll: Boolean = false, orient: Boolean = true): Mesh {
        val idx = idxIn.copyOf()
        if (orient) {
            var vol = 0.0
            var i = 0
            while (i < idx.size) {
                val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
                vol += p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) -
                    p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c]) +
                    p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c])
                i += 3
            }
            if (vol < 0) {
                i = 0
                while (i < idx.size) { val t = idx[i + 1]; idx[i + 1] = idx[i + 2]; idx[i + 2] = t; i += 3 }
            }
        }
        val n = FloatArray(p.size)
        var i = 0
        while (i < idx.size) {
            val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (k in intArrayOf(a, b, c)) { n[k] += nx; n[k + 1] += ny; n[k + 2] += nz }
            i += 3
        }
        if (mergeAll) {
            // weld normals of coincident vertices (seam/pole vertices) so shading has no cracks
            fun key(k: Int): Long {
                val x = Math.round(p[k] * 2000f).toLong() and 0xFFFFF
                val y = Math.round(p[k + 1] * 2000f).toLong() and 0xFFFFF
                val z = Math.round(p[k + 2] * 2000f).toLong() and 0xFFFFF
                return (x shl 40) or (y shl 20) or z
            }
            val acc = HashMap<Long, FloatArray>()
            var k = 0
            while (k < p.size) {
                val s = acc.getOrPut(key(k)) { FloatArray(3) }
                s[0] += n[k]; s[1] += n[k + 1]; s[2] += n[k + 2]; k += 3
            }
            k = 0
            while (k < p.size) { val s = acc.getValue(key(k)); n[k] = s[0]; n[k + 1] = s[1]; n[k + 2] = s[2]; k += 3 }
        }
        var k = 0
        while (k < n.size) {
            val l = sqrt(n[k] * n[k] + n[k + 1] * n[k + 1] + n[k + 2] * n[k + 2]).let { if (it == 0f) 1f else it }
            n[k] /= l; n[k + 1] /= l; n[k + 2] /= l; k += 3
        }
        return Mesh(p, n, idx)
    }

    fun superE(a: Float, b: Float, c: Float, e1: Float, e2: Float, su: Int = 48, sv: Int = 28): Mesh {
        val p = ArrayList<Float>((sv + 1) * su * 3)
        val idx = ArrayList<Int>()
        for (j in 0..sv) {
            val v = (-PI / 2 + PI * j / sv).toFloat()
            for (i in 0 until su) {
                val u = (-PI + 2 * PI * i / su).toFloat()
                p.add(a * fx(cos(v), e1) * fx(cos(u), e2))
                p.add(b * fx(sin(v), e1))
                p.add(c * fx(cos(v), e1) * fx(sin(u), e2))
            }
        }
        for (j in 0 until sv) for (i in 0 until su) {
            val i2 = (i + 1) % su
            val A = j * su + i; val B = j * su + i2; val C = (j + 1) * su + i; val D = (j + 1) * su + i2
            idx.add(A); idx.add(B); idx.add(C); idx.add(B); idx.add(D); idx.add(C)
        }
        return finish(p.toFloatArray(), idx.toIntArray(), mergeAll = true)
    }

    fun cyl(rt: Float, rb: Float, h: Float, seg: Int = 32): Mesh {
        val p = ArrayList<Float>()
        val idx = ArrayList<Int>()
        val hh = h / 2
        for (i in 0 until seg) {
            val u = (2 * PI * i / seg).toFloat(); val c = cos(u); val s = sin(u)
            p.add(rt * c); p.add(hh); p.add(rt * s); p.add(rb * c); p.add(-hh); p.add(rb * s)
        }
        for (i in 0 until seg) {
            val i2 = (i + 1) % seg
            idx.add(i * 2); idx.add(i2 * 2); idx.add(i * 2 + 1); idx.add(i2 * 2); idx.add(i2 * 2 + 1); idx.add(i * 2 + 1)
        }
        val t0 = p.size / 3
        p.add(0f); p.add(hh); p.add(0f)
        for (i in 0 until seg) { val u = (2 * PI * i / seg).toFloat(); p.add(rt * cos(u)); p.add(hh); p.add(rt * sin(u)) }
        for (i in 0 until seg) { idx.add(t0); idx.add(t0 + 1 + i); idx.add(t0 + 1 + (i + 1) % seg) }
        val b0 = p.size / 3
        p.add(0f); p.add(-hh); p.add(0f)
        for (i in 0 until seg) { val u = (2 * PI * i / seg).toFloat(); p.add(rb * cos(u)); p.add(-hh); p.add(rb * sin(u)) }
        for (i in 0 until seg) { idx.add(b0); idx.add(b0 + 1 + (i + 1) % seg); idx.add(b0 + 1 + i) }
        return finish(p.toFloatArray(), idx.toIntArray())
    }

    fun torus(R: Float, r: Float, su: Int = 64, sv: Int = 14): Mesh {
        val p = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (i in 0 until su) {
            val u = (2 * PI * i / su).toFloat()
            for (j in 0 until sv) {
                val v = (2 * PI * j / sv).toFloat()
                p.add((R + r * cos(v)) * cos(u)); p.add(r * sin(v)); p.add((R + r * cos(v)) * sin(u))
            }
        }
        for (i in 0 until su) for (j in 0 until sv) {
            val i2 = (i + 1) % su; val j2 = (j + 1) % sv
            val A = i * sv + j; val B = i2 * sv + j; val C = i * sv + j2; val D = i2 * sv + j2
            idx.add(A); idx.add(B); idx.add(C); idx.add(B); idx.add(D); idx.add(C)
        }
        return finish(p.toFloatArray(), idx.toIntArray())
    }

    /** Flat-shaded box (24 vertices so every face keeps a hard normal). */
    fun box(w: Float, h: Float, d: Float): Mesh {
        val x = w / 2; val y = h / 2; val z = d / 2
        val faces = listOf(
            floatArrayOf(1f, 0f, 0f) to arrayOf(floatArrayOf(x, -y, -z), floatArrayOf(x, y, -z), floatArrayOf(x, y, z), floatArrayOf(x, -y, z)),
            floatArrayOf(-1f, 0f, 0f) to arrayOf(floatArrayOf(-x, -y, z), floatArrayOf(-x, y, z), floatArrayOf(-x, y, -z), floatArrayOf(-x, -y, -z)),
            floatArrayOf(0f, 1f, 0f) to arrayOf(floatArrayOf(-x, y, z), floatArrayOf(x, y, z), floatArrayOf(x, y, -z), floatArrayOf(-x, y, -z)),
            floatArrayOf(0f, -1f, 0f) to arrayOf(floatArrayOf(-x, -y, -z), floatArrayOf(x, -y, -z), floatArrayOf(x, -y, z), floatArrayOf(-x, -y, z)),
            floatArrayOf(0f, 0f, 1f) to arrayOf(floatArrayOf(-x, -y, z), floatArrayOf(x, -y, z), floatArrayOf(x, y, z), floatArrayOf(-x, y, z)),
            floatArrayOf(0f, 0f, -1f) to arrayOf(floatArrayOf(x, -y, -z), floatArrayOf(-x, -y, -z), floatArrayOf(-x, y, -z), floatArrayOf(x, y, -z)),
        )
        val p = ArrayList<Float>(); val n = ArrayList<Float>(); val idx = ArrayList<Int>()
        for ((nn, vs) in faces) {
            val o = p.size / 3
            for (v in vs) { p.add(v[0]); p.add(v[1]); p.add(v[2]); n.add(nn[0]); n.add(nn[1]); n.add(nn[2]) }
            idx.add(o); idx.add(o + 1); idx.add(o + 2); idx.add(o); idx.add(o + 2); idx.add(o + 3)
        }
        return Mesh(p.toFloatArray(), n.toFloatArray(), idx.toIntArray())
    }
}
