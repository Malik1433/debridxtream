package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StereoUpmixAudioProcessorTest {

    private val stereo48k = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    @Before
    fun setUp() = AudioWedgeEscape.resetForTest()

    @After
    fun tearDown() = AudioWedgeEscape.resetForTest()

    @Test
    fun `inert while escape is not engaged`() {
        val processor = StereoUpmixAudioProcessor()
        val output = processor.configure(stereo48k)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, output)
        processor.flush()
        assertFalse(processor.isActive)
    }

    @Test
    fun `engaged stereo becomes 6-channel at the same sample rate`() {
        AudioWedgeEscape.engage()
        val processor = StereoUpmixAudioProcessor()
        val output = processor.configure(stereo48k)
        assertEquals(6, output.channelCount)
        assertEquals(48_000, output.sampleRate)
        assertEquals(C.ENCODING_PCM_16BIT, output.encoding)
        processor.flush()
        assertTrue(processor.isActive)
    }

    @Test
    fun `engaged but non-PCM16 or multichannel input stays untouched`() {
        AudioWedgeEscape.engage()
        val processor = StereoUpmixAudioProcessor()
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        )
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        )
    }

    @Test
    fun `stereo frames land on front left-right with silent rears`() {
        AudioWedgeEscape.engage()
        val processor = StereoUpmixAudioProcessor()
        processor.configure(stereo48k)
        processor.flush()

        // Two frames: (L=0x0102, R=0x0304), (L=0x0506, R=0x0708) in native order.
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putShort(0x0102).putShort(0x0304).putShort(0x0506).putShort(0x0708)
        input.flip()

        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.nativeOrder())

        assertEquals(0, input.remaining())
        assertEquals(2 * 12, out.remaining())
        // Frame 1: FL, FR, then FC/LFE/BL/BR silent.
        assertEquals(0x0102.toShort(), out.short)
        assertEquals(0x0304.toShort(), out.short)
        repeat(4) { assertEquals(0.toShort(), out.short) }
        // Frame 2.
        assertEquals(0x0506.toShort(), out.short)
        assertEquals(0x0708.toShort(), out.short)
        repeat(4) { assertEquals(0.toShort(), out.short) }
    }

    @Test
    fun `mono frames are duplicated to both front channels`() {
        AudioWedgeEscape.engage()
        val processor = StereoUpmixAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        val input = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder())
        input.putShort(0x7FFF)
        input.flip()

        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.nativeOrder())

        assertEquals(12, out.remaining())
        assertEquals(0x7FFF.toShort(), out.short)
        assertEquals(0x7FFF.toShort(), out.short)
        repeat(4) { assertEquals(0.toShort(), out.short) }
    }
}
