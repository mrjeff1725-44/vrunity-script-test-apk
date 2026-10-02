package com.vrunity.vrapk

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix

// The game on the GPU: the scene's geometry plus the shader that draws it. Both
// the headset's VR session and the phone's own screen mode draw through this, so
// the two always show the same game.
class Game(context: Context) : LogicWorld {
    private val context = context
    private val scene = Scene.load(context)
    // The scene's own logic: the zones the player walks through and the delays that
    // count down.
    private val logic = Logic(scene, this)
    // The AI the scene runs: it walks its own objects across the same surfaces the
    // player is stopped by.
    private val ai = AiSystem(scene)
    private val shapes = HashMap<String, Mesh>()
    // The models and images the scene came with, loaded once at start-up.
    private val models = HashMap<String, BakedModel>()
    private val textures = HashMap<String, Int>()
    private val sky = SkyRenderer()
    // Bound wherever a surface has no image of its own, so the shader always has
    // something to sample.
    private var white = 0
    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var aUv = 0
    private var uMvp = 0
    private var uColor = 0
    private var uRot = 0
    private var uAlpha = 0
    private var uTex = 0
    private var uUseTex = 0
    private var uUvRepeat = 0
    // The clip a video surface shows, and the matrix that lays each of its frames onto
    // the surface the way the clip is stored.
    private var uClip = 0
    private var uUseClip = 0
    private var uTexM = 0
    private var uSunDir0 = 0
    private var uSunColor0 = 0
    private var uSunDir1 = 0
    private var uSunColor1 = 0
    private var uAmbSky = 0
    private var uAmbGround = 0
    private var uFogColor = 0
    private var uFogRange = 0
    private var uFogOn = 0
    private val mvp = FloatArray(16)
    private val scratch = FloatArray(16)
    // The last moment the scene was stepped, so its animation moves evenly however
    // quickly the headset draws.
    private var lastFrame = -1f

    // What the scene's scripts can reach: the object each one belongs to, and every
    // other object by name. Hiding an object takes it out of the player's way as well
    // as out of the picture, the same as turning it off in the editor.
    private var hidden = false

    private fun solidsNow(): List<Solid> {
        if (!hidden) return scene.solids
        val out = ArrayList<Solid>()
        for (s in scene.solids) {
            if (s.item >= 0 && s.item < scene.items.size && scene.items[s.item].off) continue
            out.add(s)
        }
        return out
    }

    // The scene's scripts, run on the device itself.
    private val scripts = Scripts()

    private val scripter = object : ScriptWorld {
        override fun log(text: String) {
            android.util.Log.i("VRUnityXR", "Script: " + text)
        }

        override fun itemName(index: Int): String =
            if (index >= 0 && index < scene.items.size) scene.items[index].name else ""

        override fun find(name: String): Int {
            if (name.isEmpty()) return -1
            for (i in scene.items.indices) if (scene.items[i].name == name) return i
            return -1
        }

        override fun position(index: Int): FloatArray {
            val p = scene.items[index].pos
            return floatArrayOf(p[0], p[1], p[2])
        }

        override fun setPosition(index: Int, v: FloatArray) {
            val item = scene.items[index]
            item.pos[0] = v[0]
            item.pos[1] = v[1]
            item.pos[2] = v[2]
            Scene.compose(item)
        }

        // A script turns an object in degrees, as Unity does, while the scene keeps its
        // angles in radians.
        override fun rotation(index: Int): FloatArray {
            val r = scene.items[index].rot
            val d = 180f / Math.PI.toFloat()
            return floatArrayOf(r[0] * d, r[1] * d, r[2] * d)
        }

        override fun setRotation(index: Int, v: FloatArray) {
            val item = scene.items[index]
            val d = Math.PI.toFloat() / 180f
            item.rot[0] = v[0] * d
            item.rot[1] = v[1] * d
            item.rot[2] = v[2] * d
            Scene.compose(item)
        }

        override fun scale(index: Int): FloatArray {
            val s = scene.items[index].scale
            return floatArrayOf(s[0], s[1], s[2])
        }

        override fun setScale(index: Int, v: FloatArray) {
            val item = scene.items[index]
            item.scale[0] = v[0]
            item.scale[1] = v[1]
            item.scale[2] = v[2]
            Scene.compose(item)
        }

        override fun color(index: Int): FloatArray {
            val item = scene.items[index]
            return floatArrayOf(item.color[0], item.color[1], item.color[2], item.opacity)
        }

        override fun setColor(index: Int, c: FloatArray) {
            val item = scene.items[index]
            item.color[0] = c[0]
            item.color[1] = c[1]
            item.color[2] = c[2]
            item.opacity = c[3]
            // Anything not fully solid is drawn see-through, as it is in the editor.
            item.blend = c[3] < 0.999f
        }

        override fun visible(index: Int): Boolean = !scene.items[index].off

        override fun setVisible(index: Int, on: Boolean) {
            scene.items[index].off = !on
            hidden = true
        }

        override fun translate(index: Int, x: Float, y: Float, z: Float) {
            val item = scene.items[index]
            item.pos[0] += x
            item.pos[1] += y
            item.pos[2] += z
            Scene.compose(item)
        }

        override fun turn(index: Int, x: Float, y: Float, z: Float) {
            val item = scene.items[index]
            val d = Math.PI.toFloat() / 180f
            item.rot[0] += x * d
            item.rot[1] += y * d
            item.rot[2] += z * d
            Scene.compose(item)
        }

        override fun face(index: Int, x: Float, y: Float, z: Float) {
            val item = scene.items[index]
            item.rot[1] = Math.atan2((x - item.pos[0]).toDouble(), (z - item.pos[2]).toDouble()).toFloat()
            Scene.compose(item)
        }
    }

    init {
        // The scripts are read as soon as the game is made, so one that cannot be read
        // is reported before the scene is drawn.
        scripts.load(scene.scriptsJson, scripter)
    }

    val startX = scene.startX
    val startZ = scene.startZ
    val startYaw = scene.startYaw
    val eyeHeight = scene.eyeHeight

    // Safe to call again after the GPU context is recreated.
    fun setup() {
        if (program != 0) return
        shapes["cube"] = Mesh(Mesh.box())
        shapes["sphere"] = Mesh(Mesh.sphere())
        shapes["cylinder"] = Mesh(Mesh.cylinder())
        shapes["cone"] = Mesh(Mesh.cone())
        shapes["plane"] = Mesh(Mesh.plane())
        buildProgram()
        white = BakedModel.white()
        // Every model and image the scene was built with is read from the app's own
        // files. A file that is missing leaves that object out rather than stopping
        // the game.
        for (item in scene.items) {
            if (item.model.isEmpty() || models.containsKey(item.model)) continue
            val loaded = BakedModel.load(context, item.model, textures)
            if (loaded != null) models[item.model] = loaded
            else android.util.Log.e("VRUnityXR", "Missing game file: " + item.model)
        }
        for (item in scene.items) {
            loadTexture(item.tex)
        }
        // The clips the scene shows: the device plays them itself, straight onto the
        // surface each belongs to. A clip it cannot play leaves the still frame there,
        // so the surface is never blank.
        for (item in scene.items) {
            if (item.video.isEmpty()) continue
            val clip = Video(item.video)
            clip.open(context, item.clipMode != 0, item.clipLevel, item.clipSpeed, item.clipPitch)
            if (clip.playing) item.clip = clip
        }
        for (model in models.values) {
            for (part in model.parts) loadTexture(part.image)
        }
        sky.setup(scene)
        // The scene's own sounds, read from the app's own files.
        Audio.start(context, scene.sounds)
        // The scene's lights and fog are the same for every object, so they are set
        // once here instead of on every draw. A trigger that changes them pushes them
        // again the moment it runs.
        applyWorld()
        // The scene's scripts begin, with the scene standing around them.
        scripts.start()
    }

    // The scene's lighting and fog, pushed to the shader: the world's own light and
    // the scene's light object, the fill light, and the scene's fog.
    fun applyWorld() {
        if (program == 0) return
        GLES20.glUseProgram(program)
        val light = if (scene.worldOn) scene.worldIntensity else 0f
        val ambient = if (scene.worldOn) scene.ambientIntensity else 0f
        GLES20.glUniform3f(uSunDir0, scene.worldDir[0], scene.worldDir[1], scene.worldDir[2])
        GLES20.glUniform3f(uSunColor0, scene.worldColor[0] * light, scene.worldColor[1] * light, scene.worldColor[2] * light)
        GLES20.glUniform3f(uSunDir1, scene.objectDir[0], scene.objectDir[1], scene.objectDir[2])
        GLES20.glUniform3f(uSunColor1, scene.objectColor[0], scene.objectColor[1], scene.objectColor[2])
        GLES20.glUniform3f(uAmbSky, scene.ambientSky[0] * ambient, scene.ambientSky[1] * ambient, scene.ambientSky[2] * ambient)
        GLES20.glUniform3f(uAmbGround, scene.ambientGround[0] * ambient, scene.ambientGround[1] * ambient, scene.ambientGround[2] * ambient)
        GLES20.glUniform3f(uFogColor, scene.fogColor[0], scene.fogColor[1], scene.fogColor[2])
        GLES20.glUniform2f(uFogRange, scene.fogNear, scene.fogFar)
        GLES20.glUniform1f(uFogOn, scene.fogOn)
    }

    // An image a surface uses, decoded once and shared.
    private fun loadTexture(file: String) {
        if (file.isEmpty() || textures.containsKey(file)) return
        BakedModel.texture(context, file, textures)
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Shader failed: " + log)
        }
        return id
    }

    private fun buildProgram() {
        val vertex = "uniform mat4 uMvp;uniform mat4 uTexM;attribute vec3 aPos;attribute vec3 aNormal;attribute vec2 aUv;" +
            "varying vec3 vN;varying vec2 vUv;varying vec2 vClip;varying float vDepth;" +
            "void main(){vN=aNormal;vUv=aUv;vClip=(uTexM*vec4(aUv,0.0,1.0)).xy;vec4 cp=uMvp*vec4(aPos,1.0);vDepth=cp.w;gl_Position=cp;}"
        // Lit the way the editor lights it: the scene's own lights brought into this
        // object's frame, the scene's fill light, the same filmic curve the editor
        // renders through, and the scene's fog on top.
        // Texture coordinates arrive with the image's top row first, so the picture
        // is sampled as it is. An image is sRGB, and the lighting here is linear, so
        // it is brought into the same space the editor renders it in.
        // A surface shows either its own image or a clip playing on it. The clip's
        // frames arrive through the device's video image, so both are sampled here and
        // whichever one the surface has is the one it draws.
        val fragment = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;varying vec3 vN;varying vec2 vUv;varying vec2 vClip;varying float vDepth;" +
            "uniform vec3 uColor;uniform mat3 uRot;uniform float uAlpha;" +
            "uniform sampler2D uTex;uniform samplerExternalOES uClip;uniform float uUseTex;uniform float uUseClip;uniform vec2 uUvRepeat;" +
            "uniform vec3 uSunDir0;uniform vec3 uSunColor0;uniform vec3 uSunDir1;uniform vec3 uSunColor1;" +
            "uniform vec3 uAmbSky;uniform vec3 uAmbGround;uniform vec3 uFogColor;uniform vec2 uFogRange;uniform float uFogOn;" +
            "vec3 aces(vec3 c){c*=1.1/0.6;" +
            "mat3 inM=mat3(vec3(0.59719,0.07600,0.02840),vec3(0.35458,0.90834,0.13383),vec3(0.04823,0.01566,0.83777));" +
            "mat3 outM=mat3(vec3(1.60475,-0.10208,-0.00327),vec3(-0.53108,1.10813,-0.07276),vec3(-0.07367,-0.00605,1.07602));" +
            "vec3 v=inM*c;vec3 num=v*(v+0.0245786)-0.000090537;vec3 den=v*(0.983729*v+0.4329510)+0.238081;" +
            "return clamp(outM*(num/den),0.0,1.0);}" +
            "void main(){vec4 tx=mix(texture2D(uTex,vUv*uUvRepeat),texture2D(uClip,vClip),uUseClip);" +
            "float use=max(uUseTex,uUseClip);" +
            "vec3 albedo=uColor*mix(vec3(1.0),pow(tx.rgb,vec3(2.2)),use);" +
            "float alpha=uAlpha*mix(1.0,tx.a,use);" +
            "vec3 n=normalize(vN);" +
            "vec3 l0=normalize(uSunDir0*uRot);vec3 l1=normalize(uSunDir1*uRot);" +
            "vec3 irr=uSunColor0*max(dot(n,l0),0.0)+uSunColor1*max(dot(n,l1),0.0);" +
            "irr+=mix(uAmbGround,uAmbSky,n.y*0.5+0.5);" +
            "vec3 c=aces(albedo*irr*0.31830989);" +
            "float f=uFogOn*clamp((vDepth-uFogRange.x)/max(uFogRange.y-uFogRange.x,0.001),0.0,1.0);" +
            "gl_FragColor=vec4(pow(mix(c,uFogColor,f),vec3(0.4545)),alpha);}"
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Program failed: " + GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uRot = GLES20.glGetUniformLocation(program, "uRot")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uUseTex = GLES20.glGetUniformLocation(program, "uUseTex")
        uUvRepeat = GLES20.glGetUniformLocation(program, "uUvRepeat")
        uClip = GLES20.glGetUniformLocation(program, "uClip")
        uUseClip = GLES20.glGetUniformLocation(program, "uUseClip")
        uTexM = GLES20.glGetUniformLocation(program, "uTexM")
        uSunDir0 = GLES20.glGetUniformLocation(program, "uSunDir0")
        uSunColor0 = GLES20.glGetUniformLocation(program, "uSunColor0")
        uSunDir1 = GLES20.glGetUniformLocation(program, "uSunDir1")
        uSunColor1 = GLES20.glGetUniformLocation(program, "uSunColor1")
        uAmbSky = GLES20.glGetUniformLocation(program, "uAmbSky")
        uAmbGround = GLES20.glGetUniformLocation(program, "uAmbGround")
        uFogColor = GLES20.glGetUniformLocation(program, "uFogColor")
        uFogRange = GLES20.glGetUniformLocation(program, "uFogRange")
        uFogOn = GLES20.glGetUniformLocation(program, "uFogOn")
    }

    // A model attached to an object is drawn in that object's own frame, so it follows
    // the object — including when a script has just moved it.
    private fun followParents() {
        for (i in scene.items.indices) {
            val item = scene.items[i]
            val parent = item.parent
            if (parent < 0 || parent >= i) continue
            System.arraycopy(scene.items[parent].matrix, 0, item.matrix, 0, 16)
            System.arraycopy(scene.items[parent].rotM, 0, item.rotM, 0, 9)
        }
    }

    // One step of the game: the scene's animation moves forward, a model attached to
    // an object follows the object it belongs to, and the scene's sounds are placed
    // around the player's head. The scene's own logic runs here too, and where a
    // teleport action asked the player to stand is handed back to the caller.
    fun update(listenerX: Float, listenerY: Float, listenerZ: Float, rightX: Float, rightZ: Float): FloatArray? {
        val now = seconds()
        var dt = if (lastFrame < 0f) 0f else now - lastFrame
        lastFrame = now
        if (dt < 0f) dt = 0f
        if (dt > 0.1f) dt = 0.1f
        if (dt > 0f) {
            for (i in scene.items.indices) {
                val item = scene.items[i]
                val anim = item.anim ?: continue
                var time = anim.time + dt * anim.speed
                if (time >= anim.duration) time = if (anim.loop) time % anim.duration else anim.duration
                anim.time = time
                moveTo(anim, item)
            }
        }
        followParents()
        Audio.update(listenerX, listenerY, listenerZ, rightX, rightZ)
        // The scene's logic — the zones the player walks through and the delays that
        // count down — stepped with the player's head where it is.
        logic.step(dt, listenerX, listenerY, listenerZ)
        // The AI runs after the scene's own logic, so a trigger that turns one on or
        // sends it after the player takes effect the moment it fires.
        ai.step(dt, listenerX, listenerY, listenerZ)
        // The scripts the scene carries run last, so what they move is drawn this frame
        // — and anything attached to what they moved follows it.
        scripts.step(dt)
        followParents()
        val teleport = pendingTeleport
        pendingTeleport = null
        return teleport
    }

    // Puts an animated object where its keyframes say it is at this moment. The
    // keyframes are evenly spaced across the duration, so the pair to draw between
    // comes from the time and the two are blended.
    private fun moveTo(anim: Scene.Anim, item: Scene.Item) {
        val last = anim.count - 1
        if (last < 0) return
        val span = if (last > 0) anim.duration / last else 0f
        var index = if (span > 0f) (anim.time / span).toInt() else 0
        if (index < 0) index = 0
        if (index > last) index = last
        var blend = 0f
        if (span > 0f) {
            blend = (anim.time - index * span) / span
            if (blend < 0f) blend = 0f
            if (blend > 1f) blend = 1f
        }
        val next = if (index < last) index + 1 else index
        for (k in 0 until 9) {
            val from = anim.kfs[index * 9 + k]
            val to = anim.kfs[next * 9 + k]
            val value = from + (to - from) * blend
            when (k) {
                0 -> item.pos[0] = value
                1 -> item.pos[1] = value
                2 -> item.pos[2] = value
                3 -> item.rot[0] = value
                4 -> item.rot[1] = value
                5 -> item.rot[2] = value
                6 -> item.scale[0] = value
                7 -> item.scale[1] = value
                8 -> item.scale[2] = value
            }
        }
        Scene.compose(item)
    }

    // The scene's sounds stop with the game.
    fun stopAudio() {
        Audio.stop()
        // The clips stop with the session, the same as the scene's sounds.
        for (item in scene.items) {
            item.clip?.stop()
            item.clip = null
        }
    }

    // Where a teleport action asked the player to stand, handed to the caller after
    // the frame has been stepped.
    private var pendingTeleport: FloatArray? = null

    // The actions a trigger, a delay or a Foundation Block takes on the scene. Each
    // one was resolved at build time to the object or sound it applies to.
    override fun applyColor(index: Int, color: FloatArray) {
        val item = scene.items.getOrNull(index) ?: return
        item.color[0] = color[0]
        item.color[1] = color[1]
        item.color[2] = color[2]
    }

    override fun applyAlpha(index: Int, value: Float) {
        val item = scene.items.getOrNull(index) ?: return
        item.opacity = value.coerceIn(0f, 1f)
        // A surface that is less than solid is drawn with the see-through pass and a
        // solid one with the solid pass — the same rule the editor follows when a
        // block sets transparency.
        item.blend = item.opacity < 1f
    }

    override fun applyTransform(index: Int, kind: Int, value: FloatArray) {
        val item = scene.items.getOrNull(index) ?: return
        val to = when (kind) {
            0 -> item.pos
            1 -> item.rot
            else -> item.scale
        }
        for (k in 0 until 3) to[k] = value[k]
        Scene.compose(item)
    }

    override fun playSound(index: Int, play: Boolean) {
        Audio.play(index, play)
    }

    override fun teleportTo(x: Float, y: Float, z: Float) {
        pendingTeleport = floatArrayOf(x, y, z)
    }

    override fun worldChanged() {
        applyWorld()
    }

    // The AI a trigger acts on while the scene runs.
    override fun aiOn(index: Int, on: Boolean) {
        ai.active(index, on)
    }

    override fun aiSpot(index: Int) {
        ai.spot(index)
    }

    override fun aiSet(index: Int, kind: Int, value: Float) {
        ai.tune(index, kind, value)
    }

    // The clip on a surface: which sound it uses, how loud, fast and high it runs, and
    // a swap to another image or clip. The settings live on the surface itself, so a
    // block's change stands for the rest of the scene — including a clip swapped in
    // later, which is opened with them.
    override fun clipAudio(index: Int, mode: Int, sound: Int) {
        val item = scene.items.getOrNull(index) ?: return
        item.clipMode = mode
        item.clip?.use(mode)
        // The sound attached to the surface takes the place of the clip's own track.
        if (mode == 1 && sound >= 0) Audio.play(sound, true)
    }

    override fun clipTune(index: Int, kind: Int, value: Float) {
        val item = scene.items.getOrNull(index) ?: return
        when (kind) {
            0 -> item.clipLevel = value.coerceIn(0f, 1f)
            1 -> item.clipSpeed = value.coerceIn(0.1f, 4f)
            else -> item.clipPitch = value.coerceIn(0.5f, 2f)
        }
        item.clip?.tune(item.clipLevel, item.clipSpeed, item.clipPitch)
    }

    override fun clipSet(index: Int, tex: String, video: String) {
        val item = scene.items.getOrNull(index) ?: return
        item.clip?.stop()
        item.clip = null
        item.tex = tex
        loadTexture(tex)
        // No clip in the new look: the surface shows the image it was pointed at, or
        // nothing at all, exactly as clearing a texture does in the editor.
        if (video.isEmpty()) return
        val clip = Video(video)
        clip.open(context, item.clipMode != 0, item.clipLevel, item.clipSpeed, item.clipPitch)
        if (clip.playing) item.clip = clip
    }

    // Where the player ends up: stopped by what stands in the way, standing on
    // whatever is under them — the scene's own shapes, or its floor. A small step up
    // is taken, a drop is fallen.
    private var fallVel = 0f

    fun resolvePlayer(x: Float, z: Float, platformY: Float, body: Float, dt: Float): FloatArray {
        val pushed = Collide.push(solidsNow(), x, z, platformY + 0.05f, platformY + body, 0.32f)
        val ground = Collide.ground(solidsNow(), pushed[0], pushed[1], platformY + 0.55f)
        var y = platformY
        if (ground > y) {
            y = ground
            fallVel = 0f
        } else if (ground < y - 0.02f) {
            fallVel -= 9.8f * dt
            y += fallVel * dt
            if (y <= ground) {
                y = ground
                fallVel = 0f
            }
        } else {
            fallVel = 0f
        }
        return floatArrayOf(pushed[0], pushed[1], y)
    }

    fun clear() {
        GLES20.glClearColor(scene.bg[0], scene.bg[1], scene.bg[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    }

    // A flat colour for the eye images — used by the startup check that the picture
    // really is reaching the lenses.
    fun clearTo(r: Float, g: Float, b: Float) {
        GLES20.glClearColor(r, g, b, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    }

    // The sky behind everything: the same gradient, sun and stars the editor shows.
    fun drawSky(view: FloatArray, proj: FloatArray, camX: Float, camY: Float, camZ: Float) {
        sky.draw(view, proj, camX, camY, camZ, seconds())
    }

    // Draws the whole scene for one eye, given that eye's view and projection. Solid
    // objects are drawn first, then the see-through ones, so a scene that uses
    // opacity reads the same as it does in the editor.
    fun draw(view: FloatArray, proj: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        // A surface's own image lives on one unit, a clip's frames on the next.
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1i(uClip, 1)
        drawItems(view, proj, false)
        drawItems(view, proj, true)
    }

    private fun drawItems(view: FloatArray, proj: FloatArray, transparent: Boolean) {
        if (transparent) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDepthMask(true)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)
        for (i in scene.items.indices) {
            val item = scene.items[i]
            if (item.off) continue
            if (item.blend != transparent) continue
            Matrix.multiplyMM(scratch, 0, view, 0, item.matrix, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, scratch, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix3fv(uRot, 1, false, item.rotM, 0)
            GLES20.glUniform2f(uUvRepeat, item.uv, item.uv)
            // A surface with a clip draws the clip's newest frame; every other surface
            // draws the image it came with.
            val clip = item.clip
            if (clip != null) {
                clip.update()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, clip.texture)
                GLES20.glUniform1f(uUseClip, 1f)
                GLES20.glUniformMatrix4fv(uTexM, 1, false, clip.transform(), 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            } else {
                GLES20.glUniform1f(uUseClip, 0f)
            }
            val model = if (item.model.isEmpty()) null else models[item.model]
            if (model != null) {
                // A model is drawn from both sides, exactly as the editor draws it.
                // Its own colour is the one baked from the scene, changed by anything
                // a trigger has set on the object since.
                GLES20.glDisable(GLES20.GL_CULL_FACE)
                for (part in model.parts) {
                    // A texture the scene put on the object covers the model's own.
                    val named = if (item.tex.isNotEmpty()) item.tex else part.image
                    val texture = if (named.isEmpty()) 0 else textures[named] ?: 0
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (texture != 0) texture else white)
                    GLES20.glUniform1f(uUseTex, if (texture != 0) 1f else 0f)
                    GLES20.glUniform3f(uColor, part.tint[0] * item.color[0], part.tint[1] * item.color[1], part.tint[2] * item.color[2])
                    GLES20.glUniform1f(uAlpha, part.tint[3] * item.opacity)
                    part.mesh.draw(aPos, aNormal, aUv, part.start, part.count)
                }
                GLES20.glEnable(GLES20.GL_CULL_FACE)
                continue
            }
            val mesh = shapes[item.shape] ?: continue
            val texture = if (item.tex.isEmpty()) 0 else textures[item.tex] ?: 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (texture != 0) texture else white)
            GLES20.glUniform1f(uUseTex, if (texture != 0) 1f else 0f)
            GLES20.glUniform3f(uColor, item.color[0], item.color[1], item.color[2])
            GLES20.glUniform1f(uAlpha, item.opacity)
            mesh.draw(aPos, aNormal, aUv)
        }
        if (transparent) {
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    // Seconds since the app started, wrapped to an hour so the star twinkle keeps
    // working without the shader losing precision.
    private fun seconds(): Float = ((System.nanoTime() / 1000000L) % 3600000L) / 1000f
}
