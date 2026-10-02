package com.vrunity.vrapk

import java.util.PriorityQueue

// A surface that stands in the way: 0 a box, 1 a ball, 2 a pillar, 3 a floor. A floor
// is stood on, never pushed against.
class Solid(
    val kind: Int,
    val x: Float, val y: Float, val z: Float,
    val hx: Float, val hy: Float, val hz: Float,
    val r: Float,
    val yaw: Float,
    // The object this surface stands for, so one a script has hidden no longer stands
    // in the player's way.
    val item: Int,
)

object Collide {

    // Whether a body whose feet are at feet and whose head is at head would overlap a
    // surface standing at that spot on the ground.
    fun blocked(solids: List<Solid>, x: Float, z: Float, feet: Float, head: Float, radius: Float): Boolean {
        for (s in solids) {
            if (s.kind == 3) continue
            if (head < s.y - s.hy || feet > s.y + s.hy) continue
            val inside = if (s.kind == 0) overBox(s, x, z, radius) else overRound(s, x, z, radius)
            if (inside) return true
        }
        return false
    }

    // Where a body ends up after the surfaces have had their say: pushed out of
    // anything it has walked into, along the shortest way out.
    fun push(solids: List<Solid>, x: Float, z: Float, feet: Float, head: Float, radius: Float): FloatArray {
        var px = x
        var pz = z
        for (s in solids) {
            if (s.kind == 3) continue
            if (head <= s.y - s.hy || feet >= s.y + s.hy) continue
            if (s.kind == 0) {
                val local = toLocal(s, px, pz)
                val outX = s.hx + radius - Math.abs(local[0])
                val outZ = s.hz + radius - Math.abs(local[1])
                if (outX <= 0f || outZ <= 0f) continue
                var lx = local[0]
                var lz = local[1]
                if (outX < outZ) lx += if (local[0] < 0f) -outX else outX
                else lz += if (local[1] < 0f) -outZ else outZ
                px = s.x + lx * Math.cos(s.yaw.toDouble()).toFloat() - lz * Math.sin(s.yaw.toDouble()).toFloat()
                pz = s.z + lx * Math.sin(s.yaw.toDouble()).toFloat() + lz * Math.cos(s.yaw.toDouble()).toFloat()
            } else {
                val dx = px - s.x
                val dz = pz - s.z
                val reach = s.r + radius
                val d = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
                if (d >= reach) continue
                if (d < 0.0001f) {
                    px = s.x + reach
                } else {
                    px = s.x + dx / d * reach
                    pz = s.z + dz / d * reach
                }
            }
        }
        return floatArrayOf(px, pz)
    }

    // The surface a body stands on at a spot on the ground: the highest one at or
    // below the height it is at, plus a step small enough to walk up. A scene with
    // nothing under a spot leaves the body on the scene's own floor.
    fun ground(solids: List<Solid>, x: Float, z: Float, below: Float): Float {
        var best = Float.NEGATIVE_INFINITY
        val limit = below + 0.55f
        for (s in solids) {
            val top = when (s.kind) {
                0 -> if (overBox(s, x, z, 0f)) s.y + s.hy else Float.NEGATIVE_INFINITY
                2 -> if (overRound(s, x, z, 0f)) s.y + s.hy else Float.NEGATIVE_INFINITY
                1 -> {
                    if (!overRound(s, x, z, 0f)) Float.NEGATIVE_INFINITY
                    else {
                        val dx = x - s.x
                        val dz = z - s.z
                        val d = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
                        val t = if (s.r <= 0.0001f) 0f
                        else Math.sqrt((1.0 - (d / s.r).toDouble() * (d / s.r).toDouble()).coerceAtLeast(0.0)).toFloat()
                        s.y + s.hy * t
                    }
                }
                else -> if (overBox(s, x, z, 0f)) s.y else Float.NEGATIVE_INFINITY
            }
            if (top.isFinite() && top <= limit && top > best) best = top
        }
        return if (best.isFinite()) best else 0f
    }

    // Whether a straight line between two points is clear of what stands in the way.
    // Floors are looked over, never through. The line is walked in short steps, which
    // is exact enough for sight and costs nothing to run twenty times a second.
    fun clear(solids: List<Solid>, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        if (dist < 0.2f) return true
        val steps = (dist / 0.5f).toInt() + 1
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val x = ax + dx * t
            val y = ay + dy * t
            val z = az + dz * t
            for (s in solids) {
                if (s.kind == 3) continue
                if (y < s.y - s.hy || y > s.y + s.hy) continue
                val hit = if (s.kind == 0) overBox(s, x, z, 0f) else overRound(s, x, z, 0f)
                if (hit) return false
            }
        }
        return true
    }

    // A surface turned about the upright — the turn that moves what stands in the way
    // — is undone here, so every test is against a straight box.
    private fun toLocal(s: Solid, x: Float, z: Float): FloatArray {
        val dx = x - s.x
        val dz = z - s.z
        if (s.yaw == 0f) return floatArrayOf(dx, dz)
        val c = Math.cos(-s.yaw.toDouble()).toFloat()
        val sn = Math.sin(-s.yaw.toDouble()).toFloat()
        return floatArrayOf(dx * c - dz * sn, dx * sn + dz * c)
    }

    private fun overBox(s: Solid, x: Float, z: Float, radius: Float): Boolean {
        val local = toLocal(s, x, z)
        return Math.abs(local[0]) <= s.hx + radius && Math.abs(local[1]) <= s.hz + radius
    }

    private fun overRound(s: Solid, x: Float, z: Float, radius: Float): Boolean {
        val dx = x - s.x
        val dz = z - s.z
        val reach = s.r + radius
        return dx * dx + dz * dz <= reach * reach
    }
}

// The walkable map the scene's own surfaces describe: one square per metre, marked
// blocked wherever a body of the AI's own width would not fit. Everything the AI
// walks is decided on these squares, so a free square genuinely means it fits.
class Nav(val w: Int, val h: Int, val minX: Float, val minZ: Float, val grid: IntArray) {

    fun free(cx: Int, cz: Int): Boolean =
        cx >= 0 && cx < w && cz >= 0 && cz < h && grid[cz * w + cx] == 0

    fun cellX(x: Float): Int = Math.floor((x - minX).toDouble()).toInt()

    fun cellZ(z: Float): Int = Math.floor((z - minZ).toDouble()).toInt()

    fun wx(cx: Int): Float = minX + cx + 0.5f

    fun wz(cz: Int): Float = minZ + cz + 0.5f

    // A straight walk between two spots, square by square.
    fun clearLine(ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        var x0 = cellX(ax)
        var z0 = cellZ(az)
        val x1 = cellX(bx)
        val z1 = cellZ(bz)
        if (!free(x0, z0) || !free(x1, z1)) return false
        val dx = Math.abs(x1 - x0)
        val dz = Math.abs(z1 - z0)
        val sx = if (x0 < x1) 1 else -1
        val sz = if (z0 < z1) 1 else -1
        var err = dx - dz
        val steps = dx + dz + 2
        for (i in 0 until steps) {
            if (x0 == x1 && z0 == z1) return true
            val e2 = 2 * err
            if (e2 > -dz) { err -= dz; x0 += sx }
            if (e2 < dx) { err += dx; z0 += sz }
            if (!free(x0, z0)) return false
        }
        return true
    }

    // The shortest walk between two spots, as the squares it passes through (empty
    // when there is no way through at all). A goal that is inside something — a
    // player standing against a wall — is walked to at the nearest free square.
    fun path(ax: Float, az: Float, bx: Float, bz: Float): ArrayList<Int> {
        val sx = cellX(ax).coerceIn(0, w - 1)
        val sz = cellZ(az).coerceIn(0, h - 1)
        var gx = cellX(bx).coerceIn(0, w - 1)
        var gz = cellZ(bz).coerceIn(0, h - 1)
        val out = ArrayList<Int>()
        if (!free(gx, gz)) {
            var found = false
            for (ring in 1..6) {
                for (dx in -ring..ring) {
                    for (dz in -ring..ring) {
                        if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue
                        if (!free(gx + dx, gz + dz)) continue
                        gx += dx
                        gz += dz
                        found = true
                        break
                    }
                    if (found) break
                }
                if (found) break
            }
            if (!found) return out
        }
        val start = sz * w + sx
        val goal = gz * w + gx
        if (start == goal) return out
        val count = w * h
        val cost = FloatArray(count) { Float.MAX_VALUE }
        val from = IntArray(count) { -1 }
        val closed = BooleanArray(count)
        cost[start] = 0f
        val open = PriorityQueue<Int>(Comparator { a, b -> cost[a].compareTo(cost[b]) })
        open.add(start)
        val stepX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val stepZ = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        while (open.isNotEmpty()) {
            val cur = open.poll()
            if (cur == goal) break
            if (closed[cur]) continue
            closed[cur] = true
            val cx = cur % w
            val cz = cur / w
            for (k in 0 until 8) {
                val nx = cx + stepX[k]
                val nz = cz + stepZ[k]
                if (!free(nx, nz)) continue
                // A diagonal is only taken when both of its sides are free, so a body
                // never cuts a corner it could not walk through.
                if (k >= 4 && (!free(cx + stepX[k], cz) || !free(cx, cz + stepZ[k]))) continue
                val next = nz * w + nx
                if (closed[next]) continue
                val through = cost[cur] + if (k >= 4) 1.4142f else 1f
                if (through < cost[next]) {
                    cost[next] = through
                    from[next] = cur
                    open.add(next)
                }
            }
        }
        if (goal != start && from[goal] < 0) return out
        var node = goal
        while (node != start && node >= 0) {
            out.add(node)
            node = from[node]
        }
        out.reverse()
        return out
    }

    companion object {
        // The map the surfaces describe, with the margin the widest AI needs so a free
        // square always has room for it.
        fun build(solids: List<Solid>, radius: Float): Nav {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (s in solids) {
                val ex = if (s.kind == 0) Math.max(s.hx, s.hz) else s.r
                minX = minOf(minX, s.x - ex)
                maxX = Math.max(maxX, s.x + ex)
                minZ = minOf(minZ, s.z - ex)
                maxZ = Math.max(maxZ, s.z + ex)
            }
            if (minX > maxX || minZ > maxZ) {
                minX = -25f; maxX = 25f; minZ = -25f; maxZ = 25f
            }
            val pad = radius + 2f
            minX -= pad; maxX += pad; minZ -= pad; maxZ += pad
            val w = ((maxX - minX).toInt() + 2).coerceIn(8, 160)
            val h = ((maxZ - minZ).toInt() + 2).coerceIn(8, 160)
            val grid = IntArray(w * h)
            for (cz in 0 until h) {
                for (cx in 0 until w) {
                    val x = minX + cx + 0.5f
                    val z = minZ + cz + 0.5f
                    if (Collide.blocked(solids, x, z, 0.05f, 2.2f, radius)) grid[cz * w + cx] = 1
                }
            }
            return Nav(w, h, minX, minZ, grid)
        }
    }
}
