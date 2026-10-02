package com.vrunity.vrapk

// An AI the scene runs: the object it drives, where it is looking and what it is
// doing right now.
class Ai(
    val item: Int,
    var x: Float, var y: Float, var z: Float,
    // The way the AI faces, and the way the scene had it facing when it was placed.
    var face: Float,
    val restFace: Float,
    val height: Float,
    val radius: Float,
    val front: Float,
    var turn: Float,
    var detect: Float,
    var fov: Float,
    var roamSpeed: Float,
    var chaseSpeed: Float,
    val roamOn: Boolean,
    val roamSound: Int,
    val chaseSound: Int,
) {
    // Whether it is running at all — what the Inspector set, until a trigger says
    // otherwise while the scene runs.
    var on = true
    // 0 roaming, 1 chasing, 2 searching where the player was last seen.
    var mode = 0
    var hasTarget = false
    var targetX = 0f
    var targetZ = 0f
    var hasSeen = false
    var seenX = 0f
    var seenZ = 0f
    var roamPause = 0f
    var roamTimer = 0f
    var sweep = 0f
    var searchTimer = 0f
    var grace = 0f
    var velY = 0f
    var path = ArrayList<Int>()
    var pathIdx = 0
    var repath = 0f
    var aimX = 0f
    var aimZ = 0f
    var lastX = 0f
    var lastZ = 0f
    var still = 0f
    var sound = -1
}

// Every AI the scene runs, stepped together twenty times a second: the walking, the
// ground under it and the sight lines are all worked out here, not on the GPU.
class AiSystem(private val scene: Scene) {

    private val ais = scene.ais
    private val solids = scene.solids
    private var nav: Nav? = null
    private var accum = 0f
    private val player = FloatArray(3)

    fun step(dt: Float, headX: Float, headY: Float, headZ: Float) {
        if (ais.isEmpty()) return
        accum += dt
        if (accum < 0.05f) return
        val tick = accum
        accum = 0f
        player[0] = headX
        player[1] = headY
        player[2] = headZ
        var widest = 0.35f
        for (ai in ais) if (ai.radius > widest) widest = ai.radius
        val grid = nav ?: Nav.build(solids, widest)
        nav = grid
        for (ai in ais) {
            if (!ai.on) {
                quiet(ai)
                continue
            }
            walk(ai, grid, tick)
        }
    }

    // A trigger turning an AI on or off while the scene runs.
    fun active(index: Int, on: Boolean) {
        val ai = ais.getOrNull(index) ?: return
        ai.on = on
        if (!on) {
            quiet(ai)
            ai.mode = 0
            ai.hasTarget = false
            ai.hasSeen = false
            ai.path = ArrayList()
        }
    }

    // A trigger pointing an AI straight at the player.
    fun spot(index: Int) {
        val ai = ais.getOrNull(index) ?: return
        ai.on = true
        ai.mode = 1
        ai.hasSeen = true
        ai.seenX = player[0]
        ai.seenZ = player[2]
        ai.searchTimer = 10f
        ai.path = ArrayList()
        ai.repath = 0f
        ai.grace = 0f
        sound(ai)
    }

    // A trigger retuning a live AI: 0 detect range, 1 field of view, 2 roam speed,
    // 3 chase speed, 4 rotation speed.
    fun tune(index: Int, kind: Int, value: Float) {
        val ai = ais.getOrNull(index) ?: return
        when (kind) {
            0 -> ai.detect = value
            1 -> ai.fov = value.coerceIn(0f, 360f)
            2 -> ai.roamSpeed = value
            3 -> ai.chaseSpeed = value
            else -> ai.turn = value
        }
    }

    private fun walk(ai: Ai, grid: Nav, dt: Float) {
        if (ai.lastX == 0f && ai.lastZ == 0f) {
            ai.lastX = ai.x
            ai.lastZ = ai.z
        }
        // Standing on whatever is under it, and falling when it walks off an edge.
        val ground = Collide.ground(solids, ai.x, ai.z, ai.y + 0.7f)
        if (ai.y > ground + 0.02f) {
            ai.velY -= 9.8f * dt
            ai.y += ai.velY * dt
            if (ai.y <= ground) {
                ai.y = ground
                ai.velY = 0f
            }
        } else {
            ai.y = ground
            ai.velY = 0f
        }

        // Where its eyes are and which way they point: the object's own turn plus the
        // front the scene gave it.
        val eyeY = ai.y + ai.height * 0.85f
        val dx = player[0] - ai.x
        val dz = player[2] - ai.z
        val gap = Math.hypot(dx.toDouble(), dz.toDouble()).toFloat()
        val fwdX = -Math.sin(ai.face.toDouble()).toFloat()
        val fwdZ = -Math.cos(ai.face.toDouble()).toFloat()
        val toX = if (gap > 0.0001f) dx / gap else 0f
        val toZ = if (gap > 0.0001f) dz / gap else 0f
        val halfView = Math.cos(ai.fov * Math.PI / 360.0).toFloat()

        // Sight: in range, inside the view (a chase is not limited to the cone), and
        // nothing solid in the way.
        var seen = false
        if (ai.detect > 0f) {
            val inView = ai.mode == 1 || (toX * fwdX + toZ * fwdZ) > halfView
            if (gap < ai.detect && inView && Collide.clear(solids, ai.x, eyeY, ai.z, player[0], player[1], player[2])) {
                seen = true
                ai.grace = 0f
            } else if (ai.mode == 1) {
                // The line of sight broke, or the player stepped out of range, while
                // being chased — the AI keeps after them for a few seconds, so ducking
                // behind a wall is no instant escape.
                ai.grace += dt
                if (ai.grace < 8f) seen = true
            }
        }

        val was = ai.mode
        if (seen) {
            ai.mode = 1
            ai.hasSeen = true
            ai.seenX = player[0]
            ai.seenZ = player[2]
            ai.searchTimer = 0f
        } else if (ai.mode == 1) {
            ai.mode = 2
            val away = if (ai.hasSeen) Math.hypot((ai.seenX - ai.x).toDouble(), (ai.seenZ - ai.z).toDouble()).toFloat() else 5f
            ai.searchTimer = Math.max(5f, away / Math.max(ai.chaseSpeed, 0.1f) + 3f)
            ai.path = ArrayList()
            ai.repath = 0f
        }
        if (ai.mode != was) sound(ai)

        if (ai.mode == 1 && ai.hasSeen) {
            // Straight at the player when nothing is in the way — that route is
            // shorter than walking the squares — and around the walls when there is.
            if (grid.clearLine(ai.x, ai.z, ai.seenX, ai.seenZ)) {
                ai.path = ArrayList()
                ai.aimX = ai.seenX
                ai.aimZ = ai.seenZ
                moveToward(ai, ai.aimX, ai.aimZ, ai.chaseSpeed, dt)
            } else {
                follow(ai, grid, ai.seenX, ai.seenZ, ai.chaseSpeed, dt)
            }
            turnToward(ai, ai.aimX, ai.aimZ, dt)
            watch(ai)
            place(ai)
            return
        }

        if (ai.mode == 2 && ai.hasSeen) {
            follow(ai, grid, ai.seenX, ai.seenZ, ai.chaseSpeed, dt)
            turnToward(ai, ai.aimX, ai.aimZ, dt)
            ai.searchTimer -= dt
            val reached = Math.hypot((ai.x - ai.seenX).toDouble(), (ai.z - ai.seenZ).toDouble()).toFloat() < 1.5f
            if (reached || ai.searchTimer <= 0f || ai.path.isEmpty()) {
                ai.mode = 0
                ai.hasSeen = false
                ai.hasTarget = false
                ai.path = ArrayList()
                sound(ai)
            }
            watch(ai)
            place(ai)
            return
        }

        // Roaming: wander the area towards a spot across it, then pause and sweep the
        // view before picking the next one.
        if (!ai.roamOn) {
            sound(ai)
            place(ai)
            return
        }
        if (ai.roamPause > 0f) {
            ai.roamPause -= dt
            ai.sweep += dt
            ai.face = ai.restFace + Math.sin((ai.sweep * 2.5f).toDouble()).toFloat() * 0.7f
            place(ai)
            return
        }
        if (!ai.hasTarget) {
            pick(ai, grid)
            ai.roamTimer = 45f
        }
        if (ai.hasTarget) {
            follow(ai, grid, ai.targetX, ai.targetZ, ai.roamSpeed, dt)
            turnToward(ai, ai.aimX, ai.aimZ, dt)
            ai.roamTimer -= dt
            val reached = Math.hypot((ai.x - ai.targetX).toDouble(), (ai.z - ai.targetZ).toDouble()).toFloat() < 1.5f
            if (reached || ai.roamTimer <= 0f || ai.path.isEmpty()) {
                ai.hasTarget = false
                ai.path = ArrayList()
                ai.roamPause = 1.5f + Math.random().toFloat() * 1.5f
                ai.sweep = 0f
            }
        } else {
            ai.face = ai.restFace
        }
        watch(ai)
        place(ai)
    }

    // A spot to wander to: an open square a good stretch away, so the AI walks the
    // area it was placed in rather than shuffling on the spot.
    private fun pick(ai: Ai, grid: Nav) {
        val cx = grid.cellX(ai.x)
        val cz = grid.cellZ(ai.z)
        if (choose(ai, grid, cx, cz, 18f, 30f)) return
        if (choose(ai, grid, cx, cz, 4f, 14f)) return
        ai.hasTarget = false
    }

    private fun choose(ai: Ai, grid: Nav, cx: Int, cz: Int, near: Float, wide: Float): Boolean {
        for (attempt in 0 until 200) {
            val angle = Math.random() * Math.PI * 2
            val distance = near + Math.random() * wide
            val tx = Math.round(cx + Math.cos(angle) * distance).toInt()
            val tz = Math.round(cz + Math.sin(angle) * distance).toInt()
            if (!grid.free(tx, tz)) continue
            ai.targetX = grid.wx(tx)
            ai.targetZ = grid.wz(tz)
            ai.hasTarget = true
            return true
        }
        return false
    }

    // Walking the squares towards a point: a fresh route is worked out as the route
    // runs out or the target moves, and squares already reached are passed over.
    private fun follow(ai: Ai, grid: Nav, tx: Float, tz: Float, speed: Float, dt: Float) {
        ai.repath -= dt
        if (ai.path.isEmpty() || ai.repath <= 0f) {
            ai.path = grid.path(ai.x, ai.z, tx, tz)
            ai.pathIdx = 0
            ai.repath = 0.8f
        }
        if (ai.path.isEmpty()) {
            ai.aimX = tx
            ai.aimZ = tz
            return
        }
        while (ai.pathIdx < ai.path.size) {
            val cell = ai.path[ai.pathIdx]
            val wx = grid.wx(cell % grid.w)
            val wz = grid.wz(cell / grid.w)
            if (Math.hypot((ai.x - wx).toDouble(), (ai.z - wz).toDouble()) < 1.2) ai.pathIdx++ else break
        }
        if (ai.pathIdx >= ai.path.size) {
            ai.path = ArrayList()
            ai.aimX = tx
            ai.aimZ = tz
            return
        }
        val cell = ai.path[ai.pathIdx]
        ai.aimX = grid.wx(cell % grid.w)
        ai.aimZ = grid.wz(cell / grid.w)
        moveToward(ai, ai.aimX, ai.aimZ, speed, dt)
    }

    // One step towards a point: the whole step when there is room, then sideways
    // along whichever axis is still free, so the AI slides along a wall instead of
    // stopping dead against it.
    private fun moveToward(ai: Ai, tx: Float, tz: Float, speed: Float, dt: Float) {
        var dx = tx - ai.x
        var dz = tz - ai.z
        val len = Math.hypot(dx.toDouble(), dz.toDouble()).toFloat()
        if (len < 0.05f) return
        dx /= len
        dz /= len
        val step = speed * dt
        val nx = ai.x + dx * step
        val nz = ai.z + dz * step
        val feet = ai.y + 0.1f
        val head = ai.y + ai.height
        if (!Collide.blocked(solids, nx, nz, feet, head, ai.radius)) {
            ai.x = nx
            ai.z = nz
            return
        }
        if (!Collide.blocked(solids, nx, ai.z, feet, head, ai.radius)) {
            ai.x = nx
            return
        }
        if (!Collide.blocked(solids, ai.x, nz, feet, head, ai.radius)) {
            ai.z = nz
        }
    }

    // Turning to face where it is walking, at the rotation speed the scene set.
    private fun turnToward(ai: Ai, tx: Float, tz: Float, dt: Float) {
        val dx = tx - ai.x
        val dz = tz - ai.z
        if (Math.abs(dx) + Math.abs(dz) < 0.02f) return
        val want = (Math.atan2(dx.toDouble(), dz.toDouble()) + Math.PI).toFloat()
        var off = want - ai.face
        off = Math.atan2(Math.sin(off.toDouble()), Math.cos(off.toDouble())).toFloat()
        ai.face += off * (1f - Math.exp((-ai.turn * dt).toDouble()).toFloat())
    }

    // Wedged against something: the route is thrown away so the next step finds a way
    // round, and a wander target it cannot reach is dropped rather than ground at.
    private fun watch(ai: Ai) {
        val moved = Math.hypot((ai.x - ai.lastX).toDouble(), (ai.z - ai.lastZ).toDouble()).toFloat()
        ai.lastX = ai.x
        ai.lastZ = ai.z
        if (moved > 0.02f) {
            ai.still = 0f
            return
        }
        ai.still += 0.05f
        if (ai.still < 1f) return
        ai.still = 0f
        ai.path = ArrayList()
        ai.pathIdx = 0
        ai.repath = 0f
        if (ai.mode == 0) ai.hasTarget = false
    }

    // The object itself, put where the AI has walked to and turned so the face the
    // scene gave it points the way it is going.
    private fun place(ai: Ai) {
        val item = scene.items.getOrNull(ai.item) ?: return
        item.pos[0] = ai.x
        item.pos[1] = ai.y
        item.pos[2] = ai.z
        item.rot[0] = 0f
        item.rot[1] = ai.face - ai.front
        item.rot[2] = 0f
        Scene.compose(item)
    }

    // The sound the AI carries for what it is doing: its roaming sound while it
    // wanders, its chase sound once it is after the player. A sound is heard from
    // where the AI is, so it moves with it.
    private fun sound(ai: Ai) {
        val want = if (ai.mode == 0) ai.roamSound else ai.chaseSound
        if (want != ai.sound) {
            if (ai.sound >= 0) Audio.play(ai.sound, false)
            ai.sound = want
            if (want >= 0) Audio.play(want, true)
        }
        if (ai.sound >= 0) Audio.move(ai.sound, ai.x, ai.y + ai.height * 0.6f, ai.z)
    }

    private fun quiet(ai: Ai) {
        if (ai.sound >= 0) Audio.play(ai.sound, false)
        ai.sound = -1
    }
}
