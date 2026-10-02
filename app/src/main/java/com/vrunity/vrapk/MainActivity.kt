package com.vrunity.vrapk

import android.app.NativeActivity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager

class MainActivity : NativeActivity() {
    private var surface: VrSurfaceView? = null
    @Volatile private var closing = false
    private var waitingSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val startupWatch = object : Runnable {
        override fun run() {
            if (closing || isDestroyed) return
            if (Xr.startupState() == 12) { VrLaunchFailure.success(this@MainActivity); return }
            VrLaunchFailure.phase(this@MainActivity, Xr.startupDetail())
            if (SystemClock.elapsedRealtime() - waitingSince >= 30000L) {
                reportFailure("No first VR frame: " + Xr.startupDetail())
                return
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val previousFailure = VrLaunchFailure.begin(this)
        try {
            super.onCreate(savedInstanceState)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            fullscreen()
            if (previousFailure != null) { reportFailure(previousFailure); return }
            Xr.load()
        } catch (t: Throwable) {
            reportFailure(t.toString())
            return
        }
        waitingSince = SystemClock.elapsedRealtime()
        handler.postDelayed(startupWatch, 1000L)
        Thread({
            try {
                while (!closing && !isDestroyed && !Xr.nativeWindowReady()) SystemClock.sleep(20L)
                if (closing || isDestroyed) return@Thread
                // Always attempt OpenXR. Android's optional headtracking feature
                // is not a reliable gate for deciding whether to start the engine.
                val result = XrSession(this).run(::reportFailure)
                if (result == 0 && !packageManager.hasSystemFeature("android.hardware.vr.headtracking") &&
                    !android.os.Build.MANUFACTURER.equals("Oculus", true) &&
                    !android.os.Build.MANUFACTURER.equals("Meta", true)) {
                    runOnUiThread { if (!closing) startScreenMode() }
                } else if (result == 2) {
                    runOnUiThread { if (!closing && !isDestroyed) finish() }
                } else {
                    reportFailure(Xr.startupDetail())
                }
            } catch (t: Throwable) {
                Log.e("VRUnityXR", "VR startup failed", t)
                reportFailure(t.toString() + "\n" + Xr.startupDetail())
            }
        }, "VRUnity-XR").start()
    }

    private fun reportFailure(reason: String) {
        handler.post {
            if (closing || isDestroyed) return@post
            closing = true
            handler.removeCallbacks(startupWatch)
            Log.e("VRUnityXR", reason)
            VrLaunchFailure.failure(this, reason)
            startActivity(Intent(this, VrStartupErrorActivity::class.java)
                .putExtra("reason", reason).putExtra("enginePid", android.os.Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    // Keep the existing phone-holder mode only when OpenXR actually reports no
    // runtime on a non-headset. Never place a flat game behind Quest's VR overlay.
    private fun startScreenMode() {
        VrLaunchFailure.success(this)
        handler.removeCallbacks(startupWatch)
        val view = VrSurfaceView(this)
        surface = view
        setContentView(view)
        view.startSensors()
        fullscreen()
    }
    override fun onResume() {
        super.onResume()
        surface?.onResume()
        surface?.startSensors()
        fullscreen()
    }
    override fun onPause() {
        surface?.stopSensors()
        surface?.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        closing = true
        handler.removeCallbacks(startupWatch)
        // The scene's sounds stop with the game in screen mode; the headset session
        // stops its own when it closes.
        Audio.stop()
        super.onDestroy()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullscreen()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val view = surface
        if (view != null && keyCode == KeyEvent.KEYCODE_VOLUME_UP) { view.walk(1f); return true }
        if (view != null && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { view.walk(-1f); return true }
        return super.onKeyDown(keyCode, event)
    }
    private fun fullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
