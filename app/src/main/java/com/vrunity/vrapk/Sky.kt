package com.vrunity.vrapk

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// One full-screen triangle drawn at the far plane: every pixel works out which way
// the eye is looking through the inverse of the eye's view-projection, and then
// shades the sky the scene was built under.
class SkyRenderer {
    private var program = 0
    private var aPos = 0
    private var uInvVp = 0
    private var uCam = 0
    private var uTop = 0
    private var uHorizon = 0
    private var uSunDir = 0
    private var uSunColor = 0
    private var uSunSize = 0
    private var uStar = 0
    private var uHaze = 0
    private var uGlow = 0
    private var uTime = 0
    private var uFlat = 0
    private var uFlatColor = 0
    private val vp = FloatArray(16)
    private val invVp = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(3 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Sky shader failed: " + log)
        }
        return id
    }

    fun setup(scene: Scene) {
        if (program != 0) return
        val vertex = "attribute vec2 aPos;varying vec2 vClip;void main(){vClip=aPos;gl_Position=vec4(aPos,1.0,1.0);}"
        val fragment =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "varying vec2 vClip;" +
            "uniform mat4 uInvVp;uniform vec3 uCam;uniform vec3 uTop;uniform vec3 uHorizon;uniform vec3 uSunDir;" +
            "uniform vec3 uSunColor;uniform float uSunSize;uniform float uStar;uniform float uHaze;uniform float uGlow;" +
            "uniform float uTime;uniform float uFlat;uniform vec3 uFlatColor;" +
            "float hash21(vec2 p){p=fract(p*vec2(123.34,345.45));p+=dot(p,p+34.345);return fract(p.x*p.y);}" +
            "void main(){" +
            "if(uFlat>0.5){gl_FragColor=vec4(uFlatColor,1.0);return;}" +
            "vec4 p=uInvVp*vec4(vClip,1.0,1.0);float w=p.w;if(w<0.0001)w=0.0001;" +
            "vec3 d=normalize(p.xyz/w-uCam);float h=d.y;vec3 sd=normalize(uSunDir);" +
            "float upDot=max(h,0.0);vec3 skyCol=mix(uHorizon,uTop,pow(upDot,0.4));vec3 groundCol=uHorizon*0.2;" +
            "vec3 col=mix(groundCol,skyCol,smoothstep(-0.15,0.05,h));float haze=pow(1.0-abs(h),6.0);" +
            "col+=uHorizon*haze*uHaze;float sDot=max(dot(d,sd),0.0);" +
            "float sun=smoothstep(1.0-uSunSize,1.0-uSunSize*0.15,sDot);float glow=pow(sDot,48.0)*0.9*uGlow;" +
            "float halo=pow(sDot,6.0)*0.2*uGlow;float bloom=pow(sDot,2.0)*0.06*uGlow;" +
            "col+=uSunColor*(sun+glow+halo+bloom);" +
            "if(uStar>0.0&&h>0.0){vec2 sph=vec2(atan(d.z,d.x)*30.0,asin(clamp(d.y,-1.0,1.0))*60.0);" +
            "vec2 cell=floor(sph);float sv=hash21(cell);if(sv>0.992){vec2 cp=fract(sph)-0.5;float sd2=length(cp);" +
            "float sb=smoothstep(0.5,0.0,sd2)*(sv-0.992)/0.008;float tw=0.6+0.4*sin(uTime*2.0+sv*100.0);" +
            "col+=vec3(sb*tw*uStar);}}" +
            "gl_FragColor=vec4(pow(col,vec3(1.0/2.2)),1.0);}"
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Sky program failed: " + GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uInvVp = GLES20.glGetUniformLocation(program, "uInvVp")
        uCam = GLES20.glGetUniformLocation(program, "uCam")
        uTop = GLES20.glGetUniformLocation(program, "uTop")
        uHorizon = GLES20.glGetUniformLocation(program, "uHorizon")
        uSunDir = GLES20.glGetUniformLocation(program, "uSunDir")
        uSunColor = GLES20.glGetUniformLocation(program, "uSunColor")
        uSunSize = GLES20.glGetUniformLocation(program, "uSunSize")
        uStar = GLES20.glGetUniformLocation(program, "uStar")
        uHaze = GLES20.glGetUniformLocation(program, "uHaze")
        uGlow = GLES20.glGetUniformLocation(program, "uGlow")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uFlat = GLES20.glGetUniformLocation(program, "uFlat")
        uFlatColor = GLES20.glGetUniformLocation(program, "uFlatColor")
        quad.clear()
        quad.put(-1f); quad.put(-1f)
        quad.put(3f); quad.put(-1f)
        quad.put(-1f); quad.put(3f)
        quad.position(0)
        apply(scene)
    }

    // The scene's sky, pushed to the shader: the same gradient, sun and stars the
    // editor shows. Called at start-up and again whenever a trigger changes the sky
    // while the scene runs.
    fun apply(scene: Scene) {
        if (program == 0) return
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uTop, scene.skyTop[0], scene.skyTop[1], scene.skyTop[2])
        GLES20.glUniform3f(uHorizon, scene.skyHorizon[0], scene.skyHorizon[1], scene.skyHorizon[2])
        GLES20.glUniform3f(uSunDir, scene.skySunDir[0], scene.skySunDir[1], scene.skySunDir[2])
        GLES20.glUniform3f(uSunColor, scene.skySunColor[0], scene.skySunColor[1], scene.skySunColor[2])
        GLES20.glUniform3f(uFlatColor, scene.flatSky[0], scene.flatSky[1], scene.flatSky[2])
        GLES20.glUniform1f(uSunSize, scene.skySunSize)
        GLES20.glUniform1f(uStar, scene.skyStars)
        GLES20.glUniform1f(uHaze, scene.skyHaze)
        GLES20.glUniform1f(uGlow, scene.skyGlow)
        GLES20.glUniform1f(uFlat, if (scene.skyType == "procedural") 0f else 1f)
    }

    fun draw(view: FloatArray, proj: FloatArray, camX: Float, camY: Float, camZ: Float, time: Float) {
        if (program == 0) return
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        if (!Matrix.invertM(invVp, 0, vp, 0)) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uInvVp, 1, false, invVp, 0)
        GLES20.glUniform3f(uCam, camX, camY, camZ)
        GLES20.glUniform1f(uTime, time)
        // The sky sits on the far plane, so the depth test has to accept an exact
        // match — a freshly cleared buffer would otherwise reject it — and it then
        // draws over the clear colour without hiding any of the scene.
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
