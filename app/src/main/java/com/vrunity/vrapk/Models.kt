package com.vrunity.vrapk

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

// One surface of a baked model: which run of indices it draws, its colour and the
// image on it.
class BakedPart(val mesh: IndexedBuffer, val start: Int, val count: Int, val tint: FloatArray, val image: String)

class BakedModel(val parts: List<BakedPart>) {
    companion object {
        private const val MAGIC = 0x31524D56 // 'VRM1'

        // Everything the build downloaded sits beside the game's own files.
        fun read(context: Context, file: String): ByteArray? {
            return try {
                context.assets.open("media/" + file).use { it.readBytes() }
            } catch (t: Throwable) {
                null
            }
        }

        private fun text(buffer: ByteBuffer): String {
            val length = buffer.int
            if (length <= 0 || length > 4096) return ""
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        // A missing or damaged file leaves that model out of the scene rather than
        // stopping the game.
        fun load(context: Context, file: String, textures: MutableMap<String, Int>): BakedModel? {
            val bytes = read(context, file) ?: return null
            if (bytes.size < 20) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != MAGIC) return null
            buffer.int // format version
            val vertexCount = buffer.int
            val indexCount = buffer.int
            val partCount = buffer.int
            if (vertexCount <= 0 || indexCount <= 0 || partCount <= 0) return null
            if (vertexCount * 32 + indexCount * 4 + 20 > bytes.size) return null

            val vertices = FloatArray(vertexCount * 8)
            buffer.asFloatBuffer().get(vertices)
            buffer.position(buffer.position() + vertexCount * 32)
            val indices = IntArray(indexCount)
            buffer.asIntBuffer().get(indices)
            buffer.position(buffer.position() + indexCount * 4)

            val mesh = IndexedBuffer(vertices, indices)
            val parts = ArrayList<BakedPart>(partCount)
            for (i in 0 until partCount) {
                val start = buffer.int
                val count = buffer.int
                val r = buffer.float
                val g = buffer.float
                val b = buffer.float
                val a = buffer.float
                buffer.get() // the editor draws this geometry from both sides
                val image = text(buffer)
                text(buffer) // the surface's name, kept for reference only
                parts.add(BakedPart(mesh, start, count, floatArrayOf(r, g, b, a), image))
            }
            return BakedModel(parts)
        }

        // An image on a surface, decoded once and kept for the next surface that
        // uses it. Repeats, so a texture can tile across a large object.
        fun texture(context: Context, file: String, textures: MutableMap<String, Int>): Int {
            if (file.isEmpty()) return 0
            val known = textures[file]
            if (known != null) return known
            val bytes = read(context, file) ?: return 0
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return 0
            val name = IntArray(1)
            GLES20.glGenTextures(1, name, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, name[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            bitmap.recycle()
            textures[file] = name[0]
            return name[0]
        }

        // What a surface with no image of its own is bound to, so the shader always
        // has something to sample.
        fun white(): Int {
            val name = IntArray(1)
            GLES20.glGenTextures(1, name, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, name[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            val pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            pixel.put(0xFF.toByte()); pixel.put(0xFF.toByte()); pixel.put(0xFF.toByte()); pixel.put(0xFF.toByte())
            pixel.position(0)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
            return name[0]
        }
    }
}
