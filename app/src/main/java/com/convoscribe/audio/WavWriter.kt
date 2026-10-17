package com.convoscribe.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Streaming PCM16 / 16 kHz / mono WAV writer.
 *
 * The 44-byte RIFF header is laid out on construction with
 * RIFF/data sizes set to 0; each [write] appends raw little-endian
 * samples past the header; [close] seeks back and patches the two
 * size fields so the file is a valid WAV. Because the header is
 * written up-front and the channel is kept open, callers can stream
 * for an arbitrary duration without buffering the whole recording in
 * memory.
 *
 * If the process is killed before [close] runs, the file is left on
 * disk with a header that has zero sizes. [repairHeader] detects that
 * shape and rebuilds the sizes from the actual file length, so a
 * crash-recovered file is still readable by [WavReader].
 *
 * With [append] set and an existing file of at least a header's
 * length, the writer keeps the audio already there and continues
 * after its last sample ([samplesWritten] starts at the existing
 * sample count), which is how a finished session is continued. The
 * header is not trusted: the sample count comes from the file
 * length, as in [repairHeader], and [close] patches the sizes.
 *
 * @param file target WAV file; created if missing.
 * @param append keep existing audio and write after it instead of
 *   truncating. Ignored when the file is missing or shorter than a
 *   header, in which case a fresh file is laid out.
 */
class WavWriter(file: File, append: Boolean = false) : Closeable {

    private val raf: RandomAccessFile = RandomAccessFile(file, "rw")
    private val channel: FileChannel = raf.channel

    /** Total PCM16 samples in the file's data chunk, including any kept by [append]. */
    var samplesWritten: Long = 0L
        private set

    init {
        if (append && raf.length() >= HEADER_SIZE) {
            // Continue an existing recording. A trailing odd byte
            // (a crash mid-sample) is dropped so samples stay aligned.
            samplesWritten = (raf.length() - HEADER_SIZE) / 2
            raf.setLength(HEADER_SIZE + samplesWritten * 2)
        } else {
            writeFreshHeader()
        }
    }

    private fun writeFreshHeader() {
        raf.setLength(0)
        // Truncate anything that was on disk and lay out the
        // 44-byte header with the two size fields zeroed. The data
        // chunk starts right after, so subsequent writes simply
        // append.
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(RIFF_BYTES)
        header.putInt(0) // RIFF chunk size, patched in close()
        header.put(WAVE_BYTES)
        header.put(FMT_BYTES)
        header.putInt(16) // fmt chunk size for PCM
        header.putShort(1.toShort()) // audio format = PCM
        header.putShort(1.toShort()) // channels = 1 (mono)
        header.putInt(AudioSpec.SAMPLE_RATE) // sample rate
        header.putInt(AudioSpec.SAMPLE_RATE * 2) // byte rate (16-bit mono)
        header.putShort(2.toShort()) // block align = channels * bits/8
        header.putShort(16.toShort()) // bits per sample
        header.put(DATA_BYTES)
        header.putInt(0) // data chunk size, patched in close()
        header.flip()
        channel.position(0)
        channel.write(header)
    }

    /**
     * Append [samples] to the data section. Each sample is written
     * little-endian; the writer does not modify the input array.
     */
    fun write(samples: ShortArray) {
        if (samples.isEmpty()) return
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            buf.putShort(s)
        }
        buf.flip()
        channel.position(HEADER_SIZE + samplesWritten * 2)
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
        samplesWritten += samples.size
    }

    /**
     * Patch the RIFF and data chunk sizes in the header and close the
     * underlying file. After this returns, the file is a valid WAV.
     * The writer is not reusable; create a new one for another file.
     */
    override fun close() {
        val dataBytes = samplesWritten * 2
        val riffSize = 36L + dataBytes
        val patch = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk size lives at offset 4 (4 bytes, little-endian).
        channel.position(RIFF_SIZE_OFFSET)
        patch.putInt(riffSize.toInt())
        patch.flip()
        channel.write(patch)
        // data chunk size lives at offset 40 (4 bytes, little-endian),
        // not contiguous with the RIFF size, so it needs its own
        // seek + write.
        channel.position(DATA_SIZE_OFFSET)
        patch.clear()
        patch.putInt(dataBytes.toInt())
        patch.flip()
        channel.write(patch)
        channel.force(true)
        channel.close()
        raf.close()
    }

    companion object {
        /** RIFF header is 44 bytes for PCM mono 16-bit. */
        const val HEADER_SIZE: Int = 44

        /** Offset of the RIFF chunk size field (4 bytes, little-endian). */
        const val RIFF_SIZE_OFFSET: Long = 4

        /** Offset of the data chunk size field (4 bytes, little-endian). */
        const val DATA_SIZE_OFFSET: Long = 40

        /**
         * Recompute the RIFF and data chunk sizes from the file
         * length. Use this on files left behind by a crash (or a
         * kill) before the writer had a chance to patch the header;
         * the rest of the bytes are already on disk.
         *
         * Throws [IllegalArgumentException] if the file is too small
         * to even contain a 44-byte header.
         */
        fun repairHeader(file: File) {
            val total = file.length()
            require(total >= HEADER_SIZE) {
                "Cannot repair: file is $total bytes, smaller than the 44-byte header"
            }
            val dataBytes = total - HEADER_SIZE
            val riffSize = 36L + dataBytes
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(RIFF_SIZE_OFFSET)
                raf.writeIntLE(riffSize.toInt())
                raf.seek(DATA_SIZE_OFFSET)
                raf.writeIntLE(dataBytes.toInt())
            }
        }

        private val RIFF_BYTES: ByteArray = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WAVE_BYTES: ByteArray = "WAVE".toByteArray(Charsets.US_ASCII)
        private val FMT_BYTES: ByteArray = "fmt ".toByteArray(Charsets.US_ASCII)
        private val DATA_BYTES: ByteArray = "data".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Little-endian `writeInt` for the values `RandomAccessFile.writeInt`
 * writes big-endian. We want the WAV header to be little-endian.
 */
private fun RandomAccessFile.writeIntLE(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}
