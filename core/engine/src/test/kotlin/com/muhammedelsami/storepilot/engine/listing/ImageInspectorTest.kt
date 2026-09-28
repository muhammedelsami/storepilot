package com.muhammedelsami.storepilot.engine.listing

import com.muhammedelsami.storepilot.api.ImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageInspectorTest {
    @Test
    fun `reads PNG size and alpha`() {
        val opaque = ImageInspector.inspect(TestImages.png(1080, 1920))
        assertEquals(ImageFormat.PNG, opaque.format)
        assertEquals(1080 to 1920, opaque.width to opaque.height)
        assertEquals(false, opaque.hasAlpha)
        assertTrue(ImageInspector.inspect(TestImages.png(512, 512, alpha = true)).hasAlpha)
    }

    @Test
    fun `reads JPEG size`() {
        val info = ImageInspector.inspect(TestImages.jpeg(1024, 500))
        assertEquals(ImageFormat.JPEG, info.format)
        assertEquals(1024 to 500, info.width to info.height)
    }

    @Test
    fun `hashes the content`() {
        val bytes = TestImages.png(10, 10)
        val info = ImageInspector.inspect(bytes)
        assertEquals(bytes.size.toLong(), info.size)
        assertEquals(64, info.sha256.length)
        assertEquals(ImageInspector.sha256(bytes), info.sha256)
    }

    @Test
    fun `rejects other formats`() {
        assertFailsWith<IllegalArgumentException> { ImageInspector.inspect("GIF89a".toByteArray()) }
    }
}
