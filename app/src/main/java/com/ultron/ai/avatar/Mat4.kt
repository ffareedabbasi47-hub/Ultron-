package com.ultron.ai.avatar

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Column-major 4x4 matrix helpers (same convention as OpenGL). Pure Kotlin: unit-testable on the JVM. */
object Mat4 {
    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0..3) for (r in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + r] * b[c * 4 + k]
            o[c * 4 + r] = s
        }
        return o
    }

    fun persp(fovy: Float, asp: Float, n: Float, f: Float): FloatArray {
        val t = 1f / tan(fovy / 2f)
        val o = FloatArray(16)
        o[0] = t / asp; o[5] = t; o[10] = (f + n) / (n - f); o[11] = -1f; o[14] = 2f * f * n / (n - f)
        return o
    }

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: FloatArray): FloatArray {
        val l = hypot(hypot(a[0], a[1]), a[2]).let { if (it == 0f) 1f else it }
        return floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
    }

    fun lookAt(e: FloatArray, c: FloatArray, u: FloatArray): FloatArray {
        val z = norm(sub(e, c)); val x = norm(cross(u, z)); val y = cross(z, x)
        return floatArrayOf(
            x[0], y[0], z[0], 0f, x[1], y[1], z[1], 0f, x[2], y[2], z[2], 0f,
            -dot(x, e), -dot(y, e), -dot(z, e), 1f,
        )
    }

    /** Translation * rotation(quaternion x,y,z,w) * scale. */
    fun trs(t: FloatArray, q: FloatArray, s: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        return floatArrayOf(
            (1 - (yy + zz)) * s[0], (xy + wz) * s[0], (xz - wy) * s[0], 0f,
            (xy - wz) * s[1], (1 - (xx + zz)) * s[1], (yz + wx) * s[1], 0f,
            (xz + wy) * s[2], (yz - wx) * s[2], (1 - (xx + yy)) * s[2], 0f,
            t[0], t[1], t[2], 1f,
        )
    }

    fun quatEuler(x: Float, y: Float, z: Float): FloatArray {
        val cx = cos(x / 2); val sx = sin(x / 2); val cy = cos(y / 2); val sy = sin(y / 2); val cz = cos(z / 2); val sz = sin(z / 2)
        return floatArrayOf(
            sx * cy * cz + cx * sy * sz, cx * sy * cz - sx * cy * sz,
            cx * cy * sz + sx * sy * cz, cx * cy * cz - sx * sy * sz,
        )
    }

    /** Inverse-transpose of the upper 3x3 (column-major, for normals). */
    fun normalMat(m: FloatArray): FloatArray {
        val c0 = floatArrayOf(m[0], m[1], m[2]); val c1 = floatArrayOf(m[4], m[5], m[6]); val c2 = floatArrayOf(m[8], m[9], m[10])
        val a = cross(c1, c2); val b = cross(c2, c0); val c = cross(c0, c1)
        val d = dot(c0, a).let { if (it == 0f) 1f else it }
        return floatArrayOf(a[0] / d, a[1] / d, a[2] / d, b[0] / d, b[1] / d, b[2] / d, c[0] / d, c[1] / d, c[2] / d)
    }

    fun point(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
    )
}
