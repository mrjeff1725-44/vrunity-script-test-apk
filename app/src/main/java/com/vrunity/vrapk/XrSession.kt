package com.vrunity.vrapk

import android.app.Activity
import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// The game running inside the headset's own VR session: the runtime hands over each
// eye's view and the controllers, and the game draws the scene into both eyes. This
// is what opens when the app starts on a headset.
class XrSession(private val activity: Activity) {
    private val viewData = FloatArray(22)
    private val stick = FloatArray(4)
    private val fbo = IntArray(2)
    private val depth = IntArray(2)
    private var fboW = 0
    private var fboH = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val rotM = FloatArray(16)
    private val eyeM = FloatArray(16)
    private val playerM = FloatArray(16)
    private val world = FloatArray(16)
    private val scratch = FloatArray(16)

    private var playerX = 0f
    private var playerZ = 0f
    private var playerY = 0f
    private var playerYaw = 0f
    private var lastNs = 0L
    private var frameCount = 0

    private val TURN_RATE = 1.7f   // radians per second
    private val WALK_SPEED = 2.6f  // metres per second
    private val NEAR = 0.05f

    // 2 = the headset ran the game, 1 = VR opened but never handed over a frame,
    // 0 = there is no VR runtime here and the screen mode should be used instead.
    fun run(onFailure: (String) -> Unit): Int {
        var submitted = 0
        var game: Game? = null
        try {
            if (!Xr.start(activity)) return 0
            Xr.markStartupStage(13)
            VrLaunchFailure.phase(activity, Xr.startupDetail())
            val scene = Game(activity)
            game = scene
            Xr.markStartupStage(14)
            VrLaunchFailure.phase(activity, Xr.startupDetail())
            scene.setup()
            playerX = scene.startX
            playerZ = scene.startZ
            playerYaw = scene.startYaw
            lastNs = System.nanoTime()
            submitted = loop(scene)
        } catch (t: Throwable) {
            // Report BEFORE teardown: a driver failure can block native cleanup,
            // and reporting only after finally hid the original exception.
            onFailure(t.toString() + "\n" + Xr.startupDetail())
            throw t
        } finally {
            // The scene's sounds stop with the session, before the runtime is torn down.
            game?.stopAudio()
            Xr.stop()
        }
        return if (submitted > 0) 2 else 1
    }

    private fun loop(game: Game): Int {
        var submitted = 0
        while (!activity.isFinishing && !activity.isDestroyed) {
            val status = Xr.poll(viewData)
            if (status < 0) {
                check(submitted > 0) { "The headset ended VR before the first frame." }
                return submitted
            }
            // Startup is watched outside this thread: poll may block inside the
            // runtime, so a deadline checked after poll cannot bound startup.
            // Empty frames are normal while the session is being set up.
            if (status == 0) continue
            val now = System.nanoTime()
            var dt = (now - lastNs) / 1000000000f
            lastNs = now
            if (dt > 0.1f) dt = 0.1f
            Xr.input(stick)
            steer(dt)
            drawEyes(game, dt)
            check(Xr.endFrame() >= 0) { "The headset rejected the VR frame." }
            submitted++
        }
        return submitted
    }

    private fun dead(v: Float): Float = if (Math.abs(v) < 0.15f) 0f else v

    // The controllers walk the player: the left stick moves, the right stick turns.
    // Movement is relative to where the head is looking, so pushing forward always
    // goes the way the player is facing.
    private fun steer(dt: Float) {
        val mx = dead(stick[0])
        val my = dead(stick[1])
        val tx = dead(stick[2])
        if (tx != 0f) playerYaw -= tx * TURN_RATE * dt
        val total = playerYaw + headYaw(0)
        val forward = -my
        val strafe = mx
        if (Math.hypot(forward.toDouble(), strafe.toDouble()) < 0.05) return
        val dx = forward * -sin(total) + strafe * cos(total)
        val dz = forward * -cos(total) + strafe * -sin(total)
        playerX += dx * WALK_SPEED * dt
        playerZ += dz * WALK_SPEED * dt
        // An odd pose from the runtime must never poison the view matrix — one NaN in
        // the player transform makes the whole scene vanish.
        if (playerX.isNaN()) playerX = 0f
        if (playerZ.isNaN()) playerZ = 0f
        if (playerYaw.isNaN()) playerYaw = 0f
    }

    private fun headYaw(eye: Int): Float {
        val o = eye * 11
        val x = viewData[o + 7]
        val y = viewData[o + 8]
        val z = viewData[o + 9]
        val w = viewData[o + 10]
        return atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + z * z)).toFloat()
    }

    private fun quatMatrix(m: FloatArray, x: Float, y: Float, z: Float, w: Float) {
        val n = Math.sqrt((x * x + y * y + z * z + w * w).toDouble()).toFloat()
        val s = if (n > 0.00001f) 2f / (n * n) else 0f
        val xx = x * s * x; val xy = y * s * x; val xz = z * s * x; val xw = w * s * x
        val yy = y * s * y; val yz = z * s * y; val yw = w * s * y
        val zz = z * s * z; val zw = w * s * z
        m[0] = 1f - (yy + zz); m[1] = xy + zw; m[2] = xz - yw; m[3] = 0f
        m[4] = xy - zw; m[5] = 1f - (xx + zz); m[6] = yz + xw; m[7] = 0f
        m[8] = xz + yw; m[9] = yz - xw; m[10] = 1f - (xx + yy); m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }

    private fun targets(w: Int, h: Int) {
        if (fboW > 0) {
            GLES20.glDeleteFramebuffers(2, fbo, 0)
            GLES20.glDeleteRenderbuffers(2, depth, 0)
        }
        fboW = w
        fboH = h
        GLES20.glGenFramebuffers(2, fbo, 0)
        GLES20.glGenRenderbuffers(2, depth, 0)
        for (i in 0 until 2) {
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth[i])
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depth[i])
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawEyes(game: Game, dt: Float) {
        val w = Xr.eyeWidth()
        val h = Xr.eyeHeight()
        check(w > 0 && h > 0) { "The headset returned invalid eye dimensions." }
        if (fboW != w || fboH != h) targets(w, h)
        frameCount++
        // A floor-relative space already reports the eyes at their real height. One
        // that is not floor-relative starts at the head, so the eyes are lifted to the
        // scene's own height instead of sitting on the ground.
        playerY = if (Xr.floorSpace()) 0f else game.eyeHeight
        // The scene moves forward once per frame, with the player's head as the ear
        // that hears it.
        val teleport = game.update(playerX, playerY + game.eyeHeight, playerZ, view[0], view[2])
        if (teleport != null) {
            playerX = teleport[0]
            playerY = teleport[1] + (if (Xr.floorSpace()) 0f else game.eyeHeight)
            playerZ = teleport[2]
        }
        // The player walks the scene: stopped by what stands in the way, standing on
        // whatever is under them, falling when they step off an edge.
        val stand = game.resolvePlayer(playerX, playerZ, playerY, game.eyeHeight + 0.1f, dt)
        playerX = stand[0]
        playerZ = stand[1]
        playerY = stand[2]
        // A pose that is not a number would make the frustum degenerate and hide the
        // whole scene, so it is replaced with a sane view instead.
        for (i in 0 until 22) {
            if (viewData[i].isNaN() || viewData[i].isInfinite()) {
                viewData[i] = when (i % 11) {
                    0 -> -0.9f
                    1 -> 0.9f
                    2 -> 0.9f
                    3 -> -0.9f
                    10 -> 1f
                    else -> 0f
                }
            }
        }
        Matrix.setIdentityM(playerM, 0)
        Matrix.translateM(playerM, 0, playerX, playerY, playerZ)
        Matrix.rotateM(playerM, 0, Math.toDegrees(playerYaw.toDouble()).toFloat(), 0f, 1f, 0f)
        for (eye in 0 until 2) {
            val tex = Xr.eyeTexture(eye)
            check(tex != 0) { "The headset did not supply an eye image." }
            val o = eye * 11
            // Each eye gets the runtime's own field of view for that lens.
            Matrix.frustumM(proj, 0,
                tan(viewData[o].toDouble()).toFloat() * NEAR,
                tan(viewData[o + 1].toDouble()).toFloat() * NEAR,
                tan(viewData[o + 3].toDouble()).toFloat() * NEAR,
                tan(viewData[o + 2].toDouble()).toFloat() * NEAR,
                NEAR, 600f)
            quatMatrix(rotM, viewData[o + 7], viewData[o + 8], viewData[o + 9], viewData[o + 10])
            Matrix.setIdentityM(eyeM, 0)
            Matrix.translateM(eyeM, 0, viewData[o + 4], viewData[o + 5], viewData[o + 6])
            Matrix.multiplyMM(world, 0, eyeM, 0, rotM, 0)
            // The eye in the scene, then the scene from that eye.
            Matrix.multiplyMM(scratch, 0, playerM, 0, world, 0)
            Matrix.invertM(view, 0, scratch, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[eye])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "The headset eye framebuffer is incomplete."
            }
            GLES20.glViewport(0, 0, w, h)
            // The first moments are a flat colour, so a blank headset can be told
            // apart from the scene simply not being drawn.
            if (frameCount <= 40) game.clearTo(1f, 0f, 1f) else game.clear()
            // The sky first, worked out for this eye's exact view, then the scene over it.
            game.drawSky(view, proj, scratch[12], scratch[13], scratch[14])
            game.draw(view, proj)
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "Eye rendering failed (OpenGL 0x" + Integer.toHexString(error) + ")." }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }
}
