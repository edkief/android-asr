package com.convoscribe.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Convert between [FloatArray] speaker embeddings and the
 * little-endian float32 byte arrays Room stores on disk.
 *
 * The embedding model (WeSpeaker ResNet34, 256-dim) ships a
 * `FloatArray` whose length is fixed by the model. The database
 * needs raw bytes (Room's supported primitives do not include
 * `FloatArray`), so we go through a [ByteBuffer] in native byte
 * order. The conversion is symmetric: bytes → floats uses the
 * same order and the byte length is always `4 * size`, so a
 * corrupted row will fail loudly with a [java.nio.BufferUnderflowException]
 * instead of silently producing nonsense cosine scores.
 */

/**
 * Bytes per single-precision float. Pulled out as a constant
 * so the conversion can be inverted to a `FloatArray` size
 * without a magic number.
 */
const val EMBEDDING_FLOAT_BYTES: Int = 4

/**
 * Pack [this] as little-endian float32. The resulting array's
 * length is `size * [EMBEDDING_FLOAT_BYTES]`.
 */
fun FloatArray.toBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * EMBEDDING_FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    buf.asFloatBuffer().put(this)
    return buf.array()
}

/**
 * Unpack a little-endian float32 [ByteArray] into a
 * [FloatArray]. The byte length must be a multiple of
 * [EMBEDDING_FLOAT_BYTES]; otherwise the [ByteBuffer] will
 * throw a [java.nio.BufferUnderflowException] at read time,
 * which is the correct loud-failure behaviour for a corrupt row.
 */
fun ByteArray.toFloats(): FloatArray {
    val floats = FloatArray(size / EMBEDDING_FLOAT_BYTES)
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
    return floats
}
