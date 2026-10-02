package com.vrunity.vrapk

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val STRIDE = 32

// Point the attributes at one bound vertex buffer.
private fun bindLayout(name: Int, position: Int, normal: Int, uv: Int) {
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, name)
    GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
    GLES20.glEnableVertexAttribArray(position)
    GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, STRIDE, 12)
    GLES20.glEnableVertexAttribArray(normal)
    GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, STRIDE, 24)
    GLES20.glEnableVertexAttribArray(uv)
}

private fun releaseLayout(position: Int, normal: Int, uv: Int) {
    GLES20.glDisableVertexAttribArray(position)
    GLES20.glDisableVertexAttribArray(normal)
    GLES20.glDisableVertexAttribArray(uv)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
}

// A shape the device builds itself: drawn as it stands, vertex by vertex.
class VertexBuffer(vertices: FloatArray) {
    private val name = IntArray(1)
    private val count = vertices.size / 8

    init {
        check(vertices.size % 8 == 0) { "Invalid vertex data." }
        val data = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(vertices).position(0)
        GLES20.glGenBuffers(1, name, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, name[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, data, GLES20.GL_STATIC_DRAW)
        val error = GLES20.glGetError()
        check(name[0] != 0 && error == GLES20.GL_NO_ERROR) { "Vertex upload failed (OpenGL 0x" + Integer.toHexString(error) + ")." }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(position: Int, normal: Int, uv: Int) {
        bindLayout(name[0], position, normal, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        releaseLayout(position, normal, uv)
    }
}

// A model baked in the browser: its vertices are shared by every surface of the
// model, and each surface draws its own run of the index buffer.
class IndexedBuffer(vertices: FloatArray, indices: IntArray) {
    private val vbo = IntArray(1)
    private val ibo = IntArray(1)

    init {
        check(vertices.size % 8 == 0) { "Invalid vertex data." }
        val vdata = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vdata.put(vertices).position(0)
        GLES20.glGenBuffers(1, vbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, vdata, GLES20.GL_STATIC_DRAW)
        val idata = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        idata.put(indices).position(0)
        GLES20.glGenBuffers(1, ibo, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, idata, GLES20.GL_STATIC_DRAW)
        val error = GLES20.glGetError()
        check(vbo[0] != 0 && ibo[0] != 0 && error == GLES20.GL_NO_ERROR) { "Model upload failed (OpenGL 0x" + Integer.toHexString(error) + ")." }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    // The start is an index into the shared buffer, not a byte offset.
    fun draw(position: Int, normal: Int, uv: Int, start: Int, count: Int) {
        bindLayout(vbo[0], position, normal, uv)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_INT, start * 4)
        releaseLayout(position, normal, uv)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }
}
