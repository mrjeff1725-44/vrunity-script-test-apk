package com.vrunity.vrapk

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// One action a trigger, a delay or a Foundation Block takes: what it is, the object,
// sound or delay it applies to, the second index a few of them carry, a number for the
// ones that need one, a vector for the ones that need that, and the files a surface
// swap points at.
class Act(val t: String, val i: Int, val j: Int, val f: Float, val v: FloatArray, val s: String, val s2: String)

// A zone in the scene: when the player is inside it the enter actions run, and when
// the player leaves it the exit actions run.
class Trigger(val x: Float, val y: Float, val z: Float, val hx: Float, val hy: Float, val hz: Float, val enter: List<Act>, val exit: List<Act>) {
    var inside = false
}

// A delay: a countdown that runs its actions when it reaches zero. A delay set to
// start on play begins counting as soon as the scene does; the rest wait to be
// started by a trigger or a Foundation Block.
class Delay(val seconds: Float, val onPlay: Boolean, val acts: List<Act>) {
    var time = if (onPlay) seconds else -1f
}

// Where the logic reaches the game: every action it can take.
interface LogicWorld {
    fun applyColor(index: Int, color: FloatArray)
    fun applyAlpha(index: Int, value: Float)
    fun applyTransform(index: Int, kind: Int, value: FloatArray)
    fun playSound(index: Int, play: Boolean)
    fun teleportTo(x: Float, y: Float, z: Float)
    fun worldChanged()
    // The AI: turning one on or off, sending it after the player, or retuning it while
    // the scene runs.
    fun aiOn(index: Int, on: Boolean)
    fun aiSpot(index: Int)
    fun aiSet(index: Int, kind: Int, value: Float)
    // A surface's clip: which sound it uses, how loud, fast and high it runs, and the
    // image or clip a block points the surface at while the scene runs.
    fun clipAudio(index: Int, mode: Int, sound: Int)
    fun clipTune(index: Int, kind: Int, value: Float)
    fun clipSet(index: Int, tex: String, video: String)
}

// The scene's logic, stepped once per frame with the player's head where it is.
class Logic(private val scene: Scene, private val world: LogicWorld) {

    fun step(dt: Float, headX: Float, headY: Float, headZ: Float) {
        // A zone is entered by the player's head or by the body under it, so walking
        // through a doorway-height box works as it does in the editor.
        val bodyY = headY - 0.8f
        for (i in scene.triggers.indices) {
            val zone = scene.triggers[i]
            val inside = contains(zone, headX, headY, headZ) || contains(zone, headX, bodyY, headZ)
            if (inside == zone.inside) continue
            zone.inside = inside
            if (inside) run(zone.enter, "Trigger entered: " + i)
            else run(zone.exit, "Trigger left: " + i)
        }
        if (dt <= 0f) return
        for (i in scene.delays.indices) {
            val delay = scene.delays[i]
            if (delay.time < 0f) continue
            delay.time -= dt
            if (delay.time > 0f) continue
            delay.time = -1f
            run(delay.acts, "Delay finished: " + i)
        }
    }

    private fun contains(zone: Trigger, x: Float, y: Float, z: Float): Boolean {
        return x >= zone.x - zone.hx && x <= zone.x + zone.hx &&
            y >= zone.y - zone.hy && y <= zone.y + zone.hy &&
            z >= zone.z - zone.hz && z <= zone.z + zone.hz
    }

    private fun run(acts: List<Act>, what: String) {
        if (acts.isEmpty()) return
        Log.i("VRUnityXR", what)
        for (a in acts) apply(a)
    }

    private fun apply(a: Act) {
        when (a.t) {
            "color" -> world.applyColor(a.i, a.v)
            "alpha" -> world.applyAlpha(a.i, a.f)
            "pos" -> world.applyTransform(a.i, 0, a.v)
            "rot" -> world.applyTransform(a.i, 1, a.v)
            "scale" -> world.applyTransform(a.i, 2, a.v)
            "sound" -> world.playSound(a.i, a.f > 0.5f)
            "aiOn" -> world.aiOn(a.i, a.f > 0.5f)
            "aiSpot" -> world.aiSpot(a.i)
            "aiDetect" -> world.aiSet(a.i, 0, a.f)
            "aiFov" -> world.aiSet(a.i, 1, a.f)
            "aiRoamSpeed" -> world.aiSet(a.i, 2, a.f)
            "aiChaseSpeed" -> world.aiSet(a.i, 3, a.f)
            "aiTurn" -> world.aiSet(a.i, 4, a.f)
            // The clip a surface shows: its sound source, its loudness, speed and
            // pitch, and a swap to another image or clip.
            "clipAudio" -> world.clipAudio(a.i, a.f.toInt(), a.j)
            "clipLevel" -> world.clipTune(a.i, 0, a.f)
            "clipSpeed" -> world.clipTune(a.i, 1, a.f)
            "clipPitch" -> world.clipTune(a.i, 2, a.f)
            "clipSet" -> world.clipSet(a.i, a.s, a.s2)
            "teleport" -> world.teleportTo(a.v[0], a.v[1], a.v[2])
            "delay" -> {
                val delay = scene.delays.getOrNull(a.i)
                if (delay != null) delay.time = delay.seconds
            }
            // The scene-wide settings: the sky it is framed under, its light and its
            // fog. A trigger changes them exactly as the Scene Settings panel would.
            "skyType" -> {
                scene.skyType = if (a.f > 0.5f) "procedural" else "flat"
                world.worldChanged()
            }
            "skyTop" -> {
                copy(a.v, scene.skyTop)
                scene.skyType = "procedural"
                world.worldChanged()
            }
            "skyHorizon" -> {
                copy(a.v, scene.skyHorizon)
                scene.skyType = "procedural"
                world.worldChanged()
            }
            "stars" -> {
                scene.skyStars = a.f
                scene.skyType = "procedural"
                world.worldChanged()
            }
            "lightOn" -> {
                scene.worldOn = a.f > 0.5f
                world.worldChanged()
            }
            "lightColor" -> {
                copy(a.v, scene.worldColor)
                world.worldChanged()
            }
            "lightIntensity" -> {
                scene.worldIntensity = a.f
                world.worldChanged()
            }
            "ambient" -> {
                scene.ambientIntensity = a.f
                world.worldChanged()
            }
            "fogOn" -> {
                scene.fogOn = if (a.f > 0.5f) 1f else 0f
                world.worldChanged()
            }
            "fogColor" -> {
                copy(a.v, scene.fogColor)
                scene.fogOn = 1f
                world.worldChanged()
            }
            "fogNear" -> {
                scene.fogNear = a.f
                scene.fogOn = 1f
                world.worldChanged()
            }
            "fogFar" -> {
                scene.fogFar = a.f
                scene.fogOn = 1f
                world.worldChanged()
            }
        }
    }

    private fun copy(from: FloatArray, to: FloatArray) {
        val count = minOf(3, from.size, to.size)
        for (k in 0 until count) to[k] = from[k]
    }

    companion object {

        // The scene's zones and delays, read from the scene file. They stay in the
        // order they were written, because an action that starts a delay points at
        // it by its place in the list.
        fun read(scene: Scene, root: JSONObject) {
            val zones = root.optJSONArray("triggers")
            if (zones != null) {
                for (i in 0 until zones.length()) {
                    val zone = zones.optJSONObject(i) ?: continue
                    val pos = vec(zone.optJSONArray("pos"))
                    val half = vec(zone.optJSONArray("half"))
                    scene.triggers.add(Trigger(
                        pos[0], pos[1], pos[2], half[0], half[1], half[2],
                        acts(zone.optJSONArray("enter")), acts(zone.optJSONArray("exit"))))
                }
            }
            val timers = root.optJSONArray("delays")
            if (timers != null) {
                for (i in 0 until timers.length()) {
                    val delay = timers.optJSONObject(i) ?: continue
                    scene.delays.add(Delay(
                        delay.optDouble("seconds", 1.0).toFloat(),
                        delay.optBoolean("onPlay", true),
                        acts(delay.optJSONArray("acts"))))
                }
            }
        }

        private fun acts(list: JSONArray?): List<Act> {
            val out = ArrayList<Act>()
            if (list == null) return out
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                out.add(Act(
                    a.optString("t", ""),
                    a.optInt("i", -1),
                    a.optInt("j", -1),
                    a.optDouble("f", 0.0).toFloat(),
                    vec(a.optJSONArray("v")),
                    a.optString("s", ""),
                    a.optString("s2", "")))
            }
            return out
        }

        private fun vec(a: JSONArray?): FloatArray {
            if (a == null || a.length() < 3) return floatArrayOf(0f, 0f, 0f)
            return floatArrayOf(a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat())
        }
    }
}
