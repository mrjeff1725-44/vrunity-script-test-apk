package com.vrunity.vrapk

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface

// One clip on one surface. The clip's frames arrive as an image the GPU reads
// directly, and the matrix the device hands over with each frame says how the picture
// is stored, so it lands on the surface the right way up.
class Video(val file: String) {
    var texture = 0
        private set
    private val matrix = FloatArray(16)
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var frame = false
    private var muted = true
    private var volume = 1f
    private var speed = 1f
    private var pitch = 1f

    // True once the device is really playing this clip: a device that cannot play it
    // leaves the surface with the still frame the scene was built with.
    var playing = false
        private set

    // The clip's own image, made on the thread that draws because it belongs to that
    // thread's GPU context.
    private fun create() {
        if (texture != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(texture)
        st.setOnFrameAvailableListener { frame = true }
        surfaceTexture = st
        Matrix.setIdentityM(matrix, 0)
    }

    fun open(context: Context, mute: Boolean, level: Float, rate: Float, tone: Float) {
        muted = mute
        volume = level.coerceIn(0f, 1f)
        speed = rate.coerceIn(0.1f, 4f)
        pitch = tone.coerceIn(0.5f, 2f)
        create()
        try {
            val st = surfaceTexture ?: return
            surface = Surface(st)
            val mp = MediaPlayer()
            // The clip sits in the app's own media folder, next to its models and
            // images, and plays on a loop for as long as the scene runs.
            val asset = context.assets.openFd("media/" + file)
            mp.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            asset.close()
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(if (muted) 0f else volume, if (muted) 0f else volume)
            mp.prepare()
            retune(mp)
            mp.start()
            player = mp
            playing = true
        } catch (t: Throwable) {
            Log.e("VRUnityXR", "Video not playing: " + file + " " + t)
            player = null
            playing = false
        }
    }

    // How fast the clip runs and how its sound sits, handed to the device's player.
    private fun retune(mp: MediaPlayer) {
        try {
            val params = mp.playbackParams
            params.speed = speed
            params.pitch = pitch
            mp.playbackParams = params
        } catch (t: Throwable) {
            // A device that will not retune plays the clip at its own pace.
            Log.e("VRUnityXR", "Video settings not applied: " + t)
        }
    }

    // The clip's newest frame, handed to the GPU. A frame that is not ready yet simply
    // stays as it is, so the second eye of a frame draws the same picture as the first.
    fun update() {
        if (!frame) return
        frame = false
        try {
            val st = surfaceTexture ?: return
            st.updateTexImage()
            st.getTransformMatrix(matrix)
        } catch (t: Throwable) {
            Log.e("VRUnityXR", "Video frame not ready: " + t)
        }
    }

    fun transform(): FloatArray = matrix

    // Handing the clip a new loudness, speed or pitch while the scene runs, and
    // starting or stopping it.
    fun tune(level: Float, rate: Float, tone: Float) {
        volume = level.coerceIn(0f, 1f)
        speed = rate.coerceIn(0.1f, 4f)
        pitch = tone.coerceIn(0.5f, 2f)
        val mp = player ?: return
        try {
            mp.setVolume(if (muted) 0f else volume, if (muted) 0f else volume)
            retune(mp)
        } catch (t: Throwable) {
            Log.e("VRUnityXR", "Video not retuned: " + t)
        }
    }

    // Which sound the surface hears: the clip's own track (0), the sound attached to
    // the surface, which the game plays in its place (1), or nothing at all (2).
    fun use(mode: Int) {
        muted = mode != 0
        val mp = player ?: return
        try {
            mp.setVolume(if (muted) 0f else volume, if (muted) 0f else volume)
        } catch (t: Throwable) {
            Log.e("VRUnityXR", "Video sound not changed: " + t)
        }
    }

    fun stop() {
        try { player?.stop() } catch (t: Throwable) {}
        try { player?.release() } catch (t: Throwable) {}
        try { surface?.release() } catch (t: Throwable) {}
        try { surfaceTexture?.release() } catch (t: Throwable) {}
        player = null
        playing = false
    }
}
