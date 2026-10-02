package com.vrunity.vrapk

import android.content.Context
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject

// The scene as the game draws it: every shape with its world transform, its colour
// already in the space the shader lights in, and where the player starts.
class Scene private constructor() {
    val bg = floatArrayOf(0.06f, 0.08f, 0.14f)
    val items = ArrayList<Item>()
    // The sounds the scene carries, each heard from where its object stands.
    val sounds = ArrayList<Sound>()
    // The scripts the scene's objects carry, read and run by the device itself.
    var scriptsJson: JSONArray? = null
    var startX = 0f
    var startZ = 0f
    var startYaw = 0f
    var eyeHeight = 1.6f

    // The sky the scene is framed under, the lights that light it and the fog that
    // gives it depth — taken from the scene's own settings, so the headset shows the
    // same light the editor does instead of a flat background under a fixed lamp.
    var skyType = "flat"
    val skyTop = floatArrayOf(0.02f, 0.10f, 0.35f)
    val skyHorizon = floatArrayOf(0.42f, 0.62f, 0.85f)
    val skySunDir = floatArrayOf(0.3f, 0.8f, 0.2f)
    val skySunColor = floatArrayOf(1f, 0.95f, 0.85f)
    var skySunSize = 0.02f
    var skyStars = 0f
    var skyHaze = 0.25f
    var skyGlow = 1f
    val flatSky = floatArrayOf(0.02f, 0.02f, 0.03f)
    // The lights that light the scene: the world's own light — which a trigger can
    // change while the scene runs — and the scene's light object, if it has one. An
    // unused second light points straight up with no colour, so it adds nothing.
    val worldDir = floatArrayOf(0f, 1f, 0f)
    val worldColor = floatArrayOf(1f, 1f, 1f)
    var worldOn = true
    var worldIntensity = 1f
    val objectDir = floatArrayOf(0f, 1f, 0f)
    val objectColor = floatArrayOf(0f, 0f, 0f)
    // The scene's fill light, kept as the colours it is made of and its strength, so
    // a trigger can change the strength without losing the colours.
    val ambientSky = floatArrayOf(0f, 0f, 0f)
    val ambientGround = floatArrayOf(0f, 0f, 0f)
    var ambientIntensity = 0.35f
    var fogOn = 0f
    var fogNear = 10f
    var fogFar = 60f
    val fogColor = floatArrayOf(0.5f, 0.5f, 0.5f)
    // The scene's own logic: the trigger zones the player walks through and the
    // delays that count down, each carrying the actions it runs.
    val triggers = ArrayList<Trigger>()
    val delays = ArrayList<Delay>()
    // What the player and the AI are stopped by, and the AI the scene runs.
    val solids = ArrayList<Solid>()
    val ais = ArrayList<Ai>()

    // A surface to draw: either one of the device's own shapes, or a model baked
    // from the scene (an imported model, 3D text, a model attached to an object),
    // which arrives already in the object's own space. The item carries its own place
    // in the world and one matrix, which the scene's animation moves while it runs.
    class Item(
        // The object's own name, which a script finds it by.
        val name: String,
        val shape: String,
        val model: String,
        // The image this surface shows. A block can point it at another image or clip
        // while the scene runs, so it is not fixed once the scene has loaded.
        var tex: String,
        // A clip the surface shows instead of its image, and what that clip does:
        // whether its own sound is heard, and how loud, fast and high it runs.
        val video: String,
        val vsound: FloatArray,
        val uv: Float,
        // The see-through flag and the opacity belong to the scene, and a trigger can
        // change both while the scene runs.
        var blend: Boolean,
        val pos: FloatArray,
        val rot: FloatArray,
        val scale: FloatArray,
        val color: FloatArray,
        var opacity: Float,
        val anim: Anim?,
        val parent: Int,
    ) {
        val matrix = FloatArray(16)
        val rotM = FloatArray(9)
        // The clip this surface plays, once the device has opened it. Until then the
        // surface shows the still frame the scene was built with.
        var clip: Video? = null
        // What that clip does while it plays: which sound it uses and how loud, fast
        // and high it runs. A block can change all four while the scene runs.
        var clipMode = if (vsound[0] < 0.5f) 0 else 2
        var clipLevel = vsound.getOrElse(1) { 1f }
        var clipSpeed = vsound.getOrElse(2) { 1f }
        var clipPitch = vsound.getOrElse(3) { 1f }
        // An object a script hides keeps its place in the scene but is no longer drawn
        // and no longer stands in the player's way.
        var off = false
    }

    // An object the scene animates: one position, rotation and scale per keyframe,
    // evenly spaced across the duration and played at the object's own speed.
    class Anim(val duration: Float, val speed: Float, val loop: Boolean, val kfs: FloatArray) {
        val count = kfs.size / 9
        var time = 0f
    }

    // A sound attached to an object: a range of zero is heard across the whole scene.
    class Sound(val file: String, val pos: FloatArray, val range: Float, val volume: Float, val loop: Boolean, val auto: Boolean)

    companion object {

        private val scratch = FloatArray(16)

        fun load(context: Context): Scene {
            val scene = Scene()
            val text = context.assets.open("scene.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val bgArr = vec(root.optJSONArray("bg"), scene.bg)
            scene.bg[0] = bgArr[0]; scene.bg[1] = bgArr[1]; scene.bg[2] = bgArr[2]
            val start = root.optJSONObject("start")
            if (start != null) {
                val p = vec(start.optJSONArray("pos"), floatArrayOf(0f, 0f, 0f))
                scene.startX = p[0]
                scene.startZ = p[2]
                scene.startYaw = start.optDouble("yaw", 0.0).toFloat()
            }
            scene.eyeHeight = root.optDouble("eyeHeight", 1.6).toFloat()
            readSky(root, scene)
            readLights(root, scene)
            readFog(root, scene)
            readSounds(root, scene)
            readSolids(root, scene)
            readAi(root, scene)
            val list = root.optJSONArray("objects") ?: return scene
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val shape = o.optString("shape", "")
                val modelFile = o.optString("model", "")
                if (shape.isEmpty() && modelFile.isEmpty()) continue
                val item = Item(
                    o.optString("name", ""),
                    shape,
                    modelFile,
                    o.optString("tex", ""),
                    o.optString("video", ""),
                    vec(o.optJSONArray("vsound"), floatArrayOf(0f, 1f, 1f, 1f)),
                    o.optDouble("uv", 1.0).toFloat(),
                    o.optBoolean("blend", false),
                    vec(o.optJSONArray("pos"), floatArrayOf(0f, 0f, 0f)),
                    vec(o.optJSONArray("rot"), floatArrayOf(0f, 0f, 0f)),
                    vec(o.optJSONArray("scale"), floatArrayOf(1f, 1f, 1f)),
                    vec(o.optJSONArray("color"), floatArrayOf(0.5f, 0.5f, 0.5f)),
                    o.optDouble("opacity", 1.0).toFloat(),
                    readAnim(o.optJSONObject("anim")),
                    o.optInt("parent", -1))
                compose(item)
                scene.items.add(item)
            }
            // The scene's zones and delays, with the actions each one runs, and the
            // scripts its objects carry.
            Logic.read(scene, root)
            scene.scriptsJson = root.optJSONArray("scripts")
            return scene
        }

        // Where an item stands, in the form the shader wants it: one matrix for the
        // geometry and one for the object's own turn, which brings the scene's lights
        // into the item's frame — so a turned object is lit on the side that faces the
        // sun, exactly as the editor lights it.
        fun compose(item: Item) {
            val rotation = FloatArray(16)
            Matrix.setIdentityM(rotation, 0)
            Matrix.rotateM(rotation, 0, item.rot[0] * 57.29578f, 1f, 0f, 0f)
            Matrix.rotateM(rotation, 0, item.rot[1] * 57.29578f, 0f, 1f, 0f)
            Matrix.rotateM(rotation, 0, item.rot[2] * 57.29578f, 0f, 0f, 1f)
            Matrix.setIdentityM(item.matrix, 0)
            Matrix.translateM(item.matrix, 0, item.pos[0], item.pos[1], item.pos[2])
            Matrix.multiplyMM(scratch, 0, item.matrix, 0, rotation, 0)
            System.arraycopy(scratch, 0, item.matrix, 0, 16)
            Matrix.scaleM(item.matrix, 0, item.scale[0], item.scale[1], item.scale[2])
            item.rotM[0] = rotation[0]; item.rotM[1] = rotation[1]; item.rotM[2] = rotation[2]
            item.rotM[3] = rotation[4]; item.rotM[4] = rotation[5]; item.rotM[5] = rotation[6]
            item.rotM[6] = rotation[8]; item.rotM[7] = rotation[9]; item.rotM[8] = rotation[10]
        }

        // The keyframes an animated object carries, flattened to nine numbers each:
        // position, rotation and scale.
        private fun readAnim(a: JSONObject?): Anim? {
            if (a == null) return null
            val frames = a.optJSONArray("kfs") ?: return null
            if (frames.length() < 1) return null
            val values = FloatArray(frames.length() * 9)
            for (i in 0 until frames.length()) {
                val frame = frames.optJSONArray(i) ?: continue
                for (k in 0 until 9) values[i * 9 + k] = frame.optDouble(k, 0.0).toFloat()
            }
            val duration = a.optDouble("duration", 2.0).toFloat()
            return Anim(
                if (duration > 0.01f) duration else 0.01f,
                a.optDouble("speed", 1.0).toFloat(),
                a.optBoolean("loop", true),
                values)
        }

        // What stands in the way: the scene's own shapes and models, each reduced by
        // the build to a box, a ball, a pillar or a floor.
        private fun readSolids(root: JSONObject, scene: Scene) {
            val list = root.optJSONArray("solids") ?: return
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                scene.solids.add(Solid(
                    s.optInt("kind", 0),
                    s.optDouble("x", 0.0).toFloat(),
                    s.optDouble("y", 0.0).toFloat(),
                    s.optDouble("z", 0.0).toFloat(),
                    s.optDouble("hx", 0.5).toFloat(),
                    s.optDouble("hy", 0.5).toFloat(),
                    s.optDouble("hz", 0.5).toFloat(),
                    s.optDouble("r", 0.5).toFloat(),
                    s.optDouble("yaw", 0.0).toFloat(),
                    // The object this surface stands for, so a script that hides one
                    // takes it out of the player's way too.
                    s.optInt("item", -1)))
            }
        }

        // Every AI the scene runs: the object it drives, where it stands, how it
        // behaves, and the sounds it makes in each of its states.
        private fun readAi(root: JSONObject, scene: Scene) {
            val list = root.optJSONArray("ai") ?: return
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                val item = a.optInt("item", -1)
                if (item < 0) continue
                val face = a.optDouble("face", 0.0).toFloat()
                val ai = Ai(
                    item,
                    a.optDouble("x", 0.0).toFloat(),
                    a.optDouble("y", 0.0).toFloat(),
                    a.optDouble("z", 0.0).toFloat(),
                    face,
                    a.optDouble("restFace", face.toDouble()).toFloat(),
                    a.optDouble("height", 1.0).toFloat(),
                    a.optDouble("radius", 0.4).toFloat(),
                    a.optDouble("front", 0.0).toFloat(),
                    a.optDouble("turn", 15.0).toFloat(),
                    a.optDouble("detect", 12.0).toFloat(),
                    a.optDouble("fov", 100.0).toFloat(),
                    a.optDouble("roamSpeed", 2.0).toFloat(),
                    a.optDouble("chaseSpeed", 4.0).toFloat(),
                    a.optBoolean("roam", true),
                    a.optInt("roamSound", -1),
                    a.optInt("chaseSound", -1))
                ai.on = a.optBoolean("on", true)
                scene.ais.add(ai)
            }
        }

        private fun readSounds(root: JSONObject, scene: Scene) {
            val list = root.optJSONArray("sounds") ?: return
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val file = s.optString("file", "")
                if (file.isEmpty()) continue
                scene.sounds.add(Sound(
                    file,
                    vec(s.optJSONArray("pos"), floatArrayOf(0f, 0f, 0f)),
                    s.optDouble("range", 0.0).toFloat(),
                    s.optDouble("volume", 1.0).toFloat(),
                    s.optBoolean("loop", true),
                    s.optBoolean("auto", true)))
            }
        }

        private fun copy3(a: JSONArray?, out: FloatArray, at: Int = 0) {
            if (a == null || a.length() < 3) return
            out[at] = a.optDouble(0, 0.0).toFloat()
            out[at + 1] = a.optDouble(1, 0.0).toFloat()
            out[at + 2] = a.optDouble(2, 0.0).toFloat()
        }

        private fun readSky(root: JSONObject, scene: Scene) {
            val sky = root.optJSONObject("sky") ?: return
            scene.skyType = if (sky.optString("type", "flat") == "procedural") "procedural" else "flat"
            copy3(sky.optJSONArray("top"), scene.skyTop)
            copy3(sky.optJSONArray("horizon"), scene.skyHorizon)
            copy3(sky.optJSONArray("sunDir"), scene.skySunDir)
            copy3(sky.optJSONArray("sunColor"), scene.skySunColor)
            copy3(sky.optJSONArray("color"), scene.flatSky)
            scene.skySunSize = sky.optDouble("sunSize", 0.02).toFloat()
            scene.skyStars = sky.optDouble("starDensity", 0.0).toFloat()
            scene.skyHaze = sky.optDouble("hazeStrength", 0.25).toFloat()
            scene.skyGlow = sky.optDouble("sunGlow", 1.0).toFloat()
        }

        private fun readLights(root: JSONObject, scene: Scene) {
            val lights = root.optJSONObject("lights") ?: return
            val suns = lights.optJSONArray("suns")
            if (suns != null) {
                // The first light is the world's own and the second, when the scene
                // has one, is the scene's light object.
                val world = suns.optJSONObject(0)
                if (world != null) {
                    copy3(world.optJSONArray("dir"), scene.worldDir)
                    copy3(world.optJSONArray("color"), scene.worldColor)
                }
                val obj = suns.optJSONObject(1)
                if (obj != null) {
                    copy3(obj.optJSONArray("dir"), scene.objectDir)
                    copy3(obj.optJSONArray("color"), scene.objectColor)
                }
            }
            val settings = lights.optJSONObject("world")
            if (settings != null) {
                scene.worldOn = settings.optBoolean("on", true)
                scene.worldIntensity = settings.optDouble("intensity", 1.0).toFloat()
                copy3(settings.optJSONArray("color"), scene.worldColor)
                copy3(settings.optJSONArray("dir"), scene.worldDir)
            }
            val ambient = lights.optJSONObject("ambient")
            if (ambient != null) {
                copy3(ambient.optJSONArray("sky"), scene.ambientSky)
                copy3(ambient.optJSONArray("ground"), scene.ambientGround)
                scene.ambientIntensity = ambient.optDouble("intensity", 0.35).toFloat()
            }
        }

        private fun readFog(root: JSONObject, scene: Scene) {
            val fog = root.optJSONObject("fog") ?: return
            // The scene's own distances and colour are read even while the fog is
            // off, so a trigger that turns it on has the scene's values to use.
            scene.fogOn = if (fog.optBoolean("on", false)) 1f else 0f
            scene.fogNear = fog.optDouble("near", 10.0).toFloat()
            scene.fogFar = fog.optDouble("far", 60.0).toFloat()
            copy3(fog.optJSONArray("color"), scene.fogColor)
        }

        private fun vec(a: JSONArray?, fallback: FloatArray): FloatArray {
            if (a == null || a.length() < 3) return floatArrayOf(fallback[0], fallback[1], fallback[2])
            return floatArrayOf(a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat())
        }
    }
}
