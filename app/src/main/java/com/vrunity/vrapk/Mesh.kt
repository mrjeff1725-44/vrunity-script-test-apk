package com.vrunity.vrapk

// The game's geometry, built on the device at start-up: positions, normals and
// texture coordinates in one buffer, eight floats per vertex.
class Mesh(vertices: FloatArray) {
    private val gpu = VertexBuffer(vertices)

    fun draw(posHandle: Int, normHandle: Int, uvHandle: Int) { gpu.draw(posHandle, normHandle, uvHandle) }

    companion object {
        // A texture coordinate. The images are uploaded with their top row first, so
        // the top of a picture is the smaller number here.
        private fun uv(u: Float, w: Float): FloatArray = floatArrayOf(u, w)

        private fun push(v: ArrayList<Float>, p: FloatArray, n: FloatArray, uv: FloatArray) {
            v.add(p[0]); v.add(p[1]); v.add(p[2])
            v.add(n[0]); v.add(n[1]); v.add(n[2])
            v.add(uv[0]); v.add(uv[1])
        }

        private fun unit(p: FloatArray): FloatArray {
            val m = Math.hypot(Math.hypot(p[0].toDouble(), p[1].toDouble()), p[2].toDouble()).toFloat()
            if (m < 0.00001f) return floatArrayOf(0f, 1f, 0f)
            return floatArrayOf(p[0] / m, p[1] / m, p[2] / m)
        }

        private fun tri(v: ArrayList<Float>, a: FloatArray, b: FloatArray, c: FloatArray, na: FloatArray, nb: FloatArray, nc: FloatArray, ua: FloatArray, ub: FloatArray, uc: FloatArray) {
            push(v, a, na, ua); push(v, b, nb, ub); push(v, c, nc, uc)
        }

        private fun at(c: FloatArray, a: FloatArray, sa: Float, b: FloatArray, sb: Float): FloatArray {
            return floatArrayOf(c[0] + a[0] * sa + b[0] * sb, c[1] + a[1] * sa + b[1] * sb, c[2] + a[2] * sa + b[2] * sb)
        }

        fun box(): FloatArray {
            val v = ArrayList<Float>()
            val faces = arrayOf(
                floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
                floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
                floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f))
            for (n in faces) {
                val u = if (Math.abs(n[0]) > 0.5f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
                val w = floatArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
                val c = floatArrayOf(n[0] * 0.5f, n[1] * 0.5f, n[2] * 0.5f)
                val p0 = at(c, u, 0.5f, w, 0.5f)
                val p1 = at(c, u, -0.5f, w, 0.5f)
                val p2 = at(c, u, -0.5f, w, -0.5f)
                val p3 = at(c, u, 0.5f, w, -0.5f)
                // The whole image on each face, the right way up.
                tri(v, p0, p1, p2, n, n, n, uv(1f, 0f), uv(0f, 0f), uv(0f, 1f))
                tri(v, p0, p2, p3, n, n, n, uv(1f, 0f), uv(0f, 1f), uv(1f, 1f))
            }
            return v.toFloatArray()
        }

        private fun spherePoint(ring: Double, sector: Double): FloatArray {
            return floatArrayOf(
                (0.5 * Math.sin(ring) * Math.cos(sector)).toFloat(),
                (0.5 * Math.cos(ring)).toFloat(),
                (0.5 * Math.sin(ring) * Math.sin(sector)).toFloat())
        }

        private fun outward(p: FloatArray): FloatArray {
            return floatArrayOf(p[0] * 2f, p[1] * 2f, p[2] * 2f)
        }

        fun sphere(): FloatArray {
            val v = ArrayList<Float>()
            val rings = 18
            val sectors = 26
            for (r in 0 until rings) {
                val r0 = Math.PI * r / rings
                val r1 = Math.PI * (r + 1) / rings
                for (s in 0 until sectors) {
                    val s0 = 2.0 * Math.PI * s / sectors
                    val s1 = 2.0 * Math.PI * (s + 1) / sectors
                    val a = spherePoint(r0, s0)
                    val b = spherePoint(r0, s1)
                    val c = spherePoint(r1, s1)
                    val d = spherePoint(r1, s0)
                    // Around the equator and from pole to pole.
                    val u0 = (s0 / (2.0 * Math.PI)).toFloat()
                    val u1 = (s1 / (2.0 * Math.PI)).toFloat()
                    val w0 = (r0 / Math.PI).toFloat()
                    val w1 = (r1 / Math.PI).toFloat()
                    tri(v, a, c, b, outward(a), outward(c), outward(b), uv(u0, w0), uv(u1, w1), uv(u0, w1))
                    tri(v, a, d, c, outward(a), outward(d), outward(c), uv(u0, w0), uv(u1, w0), uv(u1, w1))
                }
            }
            return v.toFloatArray()
        }

        fun cylinder(): FloatArray {
            val v = ArrayList<Float>()
            val sectors = 30
            for (s in 0 until sectors) {
                val t0 = 2.0 * Math.PI * s / sectors
                val t1 = 2.0 * Math.PI * (s + 1) / sectors
                val x0 = (0.5 * Math.cos(t0)).toFloat()
                val z0 = (0.5 * Math.sin(t0)).toFloat()
                val x1 = (0.5 * Math.cos(t1)).toFloat()
                val z1 = (0.5 * Math.sin(t1)).toFloat()
                val n0 = floatArrayOf(x0 * 2f, 0f, z0 * 2f)
                val n1 = floatArrayOf(x1 * 2f, 0f, z1 * 2f)
                val a = floatArrayOf(x0, -0.5f, z0)
                val b = floatArrayOf(x1, -0.5f, z1)
                val c = floatArrayOf(x1, 0.5f, z1)
                val d = floatArrayOf(x0, 0.5f, z0)
                val ub = (s.toDouble() / sectors).toFloat()
                val ue = ((s + 1).toDouble() / sectors).toFloat()
                tri(v, a, b, c, n0, n1, n1, uv(ub, 1f), uv(ue, 1f), uv(ue, 0f))
                tri(v, a, c, d, n0, n1, n0, uv(ub, 1f), uv(ue, 0f), uv(ub, 0f))
                val top = floatArrayOf(0f, 1f, 0f)
                val bottom = floatArrayOf(0f, -1f, 0f)
                tri(v, floatArrayOf(0f, 0.5f, 0f), d, c, top, top, top, uv(0.5f, 0.5f), uv(ub, 0f), uv(ue, 0f))
                tri(v, floatArrayOf(0f, -0.5f, 0f), b, a, bottom, bottom, bottom, uv(0.5f, 0.5f), uv(ue, 1f), uv(ub, 1f))
            }
            return v.toFloatArray()
        }

        fun cone(): FloatArray {
            val v = ArrayList<Float>()
            val sectors = 30
            val apex = floatArrayOf(0f, 0.5f, 0f)
            for (s in 0 until sectors) {
                val t0 = 2.0 * Math.PI * s / sectors
                val t1 = 2.0 * Math.PI * (s + 1) / sectors
                val a = floatArrayOf((0.5 * Math.cos(t0)).toFloat(), -0.5f, (0.5 * Math.sin(t0)).toFloat())
                val b = floatArrayOf((0.5 * Math.cos(t1)).toFloat(), -0.5f, (0.5 * Math.sin(t1)).toFloat())
                val side = unit(floatArrayOf((a[0] + b[0]) * 1.4f, 0.45f, (a[2] + b[2]) * 1.4f))
                val ub = (s.toDouble() / sectors).toFloat()
                val ue = ((s + 1).toDouble() / sectors).toFloat()
                tri(v, a, b, apex, side, side, side, uv(ub, 1f), uv(ue, 1f), uv((ub + ue) * 0.5f, 0f))
                val bottom = floatArrayOf(0f, -1f, 0f)
                tri(v, floatArrayOf(0f, -0.5f, 0f), b, a, bottom, bottom, bottom, uv(0.5f, 0.5f), uv(ue, 1f), uv(ub, 1f))
            }
            return v.toFloatArray()
        }

        fun plane(): FloatArray {
            val v = ArrayList<Float>()
            val n = floatArrayOf(0f, 0f, 1f)
            val p0 = floatArrayOf(-0.5f, -0.5f, 0f)
            val p1 = floatArrayOf(0.5f, -0.5f, 0f)
            val p2 = floatArrayOf(0.5f, 0.5f, 0f)
            val p3 = floatArrayOf(-0.5f, 0.5f, 0f)
            tri(v, p0, p1, p2, n, n, n, uv(0f, 1f), uv(1f, 1f), uv(1f, 0f))
            tri(v, p0, p2, p3, n, n, n, uv(0f, 1f), uv(1f, 0f), uv(0f, 0f))
            return v.toFloatArray()
        }
    }
}
