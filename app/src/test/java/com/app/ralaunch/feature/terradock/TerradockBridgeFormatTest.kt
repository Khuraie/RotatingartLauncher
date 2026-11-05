package com.app.ralaunch.feature.terradock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Test

/**
 * TERRADOCK 桥接文件格式测试（对应 `terradock/bridge-spec.md` v1）。
 *
 * 纯 JVM 测试：TerradockBridgeFormat 不依赖 Android API，因此可以直接跑 `testDebugUnitTest`。
 */
class TerradockBridgeFormatTest {

    private fun newBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocate(TerradockBridgeFormat.FILE_SIZE)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        TerradockBridgeFormat.zeroFill(buf)
        return buf
    }

    private fun writeSampleSlot(
        buffer: ByteBuffer,
        seq: Int,
        stickLX: Float = 0f,
        held: Int = 0,
        pressed: Int = 0,
        released: Int = 0,
        touchCount: Int = 0,
        aimMode: Int = TerradockBridgeFormat.AIM_MODE_AND_USE,
        smartCursor: Int = 0,
        hotbarPage: Int = 0,
        zoom: Float = 1f,
        timestampMs: Long = 123456789L
    ): Int {
        val ids = IntArray(TerradockBridgeFormat.TOUCH_COUNT) { it }
        val flags = IntArray(TerradockBridgeFormat.TOUCH_COUNT) {
            if (it < touchCount) TerradockBridgeFormat.TOUCH_FLAG_DOWN else 0
        }
        val xs = FloatArray(TerradockBridgeFormat.TOUCH_COUNT) { it * 3f }
        val ys = FloatArray(TerradockBridgeFormat.TOUCH_COUNT) { it * 5f }
        val ps = FloatArray(TerradockBridgeFormat.TOUCH_COUNT) { 0.5f }
        val slotBase = TerradockBridgeFormat.slotBase(seq)
        TerradockBridgeFormat.writeSlot(
            buffer = buffer,
            slotBase = slotBase,
            seq = seq,
            timestampMs = timestampMs,
            stickLX = stickLX,
            stickLY = 0f,
            stickRX = 0f,
            stickRY = 0f,
            held = held,
            pressed = pressed,
            released = released,
            screenW = 2400f,
            screenH = 1080f,
            uiScale = 1f,
            flags = TerradockBridgeFormat.FLAG_ALIVE,
            touchIds = ids,
            touchFlags = flags,
            touchX = xs,
            touchY = ys,
            touchPressure = ps,
            touchCount = touchCount,
            zoom = zoom,
            aimMode = aimMode,
            smartCursor = smartCursor,
            hotbarPage = hotbarPage
        )
        return slotBase
    }

    @Test
    fun selfTestPasses() {
        assertTrue("format self-test must match bridge-spec.md", TerradockBridgeFormat.selfTest())
    }

    @Test
    fun slotLayoutMatchesSpecOffsets() {
        // 规范里的偏移表（bridge-spec.md），逐项硬编码以避免改动布局时静默漂移。
        assertEquals(0, TerradockBridgeFormat.OFF_MAGIC)
        assertEquals(4, TerradockBridgeFormat.OFF_VERSION)
        assertEquals(6, TerradockBridgeFormat.OFF_SLOT_SIZE)
        assertEquals(8, TerradockBridgeFormat.OFF_SEQ)
        assertEquals(12, TerradockBridgeFormat.OFF_TIMESTAMP)
        assertEquals(20, TerradockBridgeFormat.OFF_STICK_LX)
        assertEquals(24, TerradockBridgeFormat.OFF_STICK_LY)
        assertEquals(28, TerradockBridgeFormat.OFF_STICK_RX)
        assertEquals(32, TerradockBridgeFormat.OFF_STICK_RY)
        assertEquals(36, TerradockBridgeFormat.OFF_HELD)
        assertEquals(40, TerradockBridgeFormat.OFF_PRESSED)
        assertEquals(44, TerradockBridgeFormat.OFF_RELEASED)
        assertEquals(48, TerradockBridgeFormat.OFF_SCREEN_W)
        assertEquals(52, TerradockBridgeFormat.OFF_SCREEN_H)
        assertEquals(56, TerradockBridgeFormat.OFF_UI_SCALE)
        assertEquals(60, TerradockBridgeFormat.OFF_FLAGS)
        assertEquals(64, TerradockBridgeFormat.OFF_TOUCHES)
        assertEquals(64 + 10 * 16, TerradockBridgeFormat.OFF_ZOOM) // 224
        assertEquals(228, TerradockBridgeFormat.OFF_AIM_MODE)
        assertEquals(229, TerradockBridgeFormat.OFF_SMART_CURSOR)
        assertEquals(230, TerradockBridgeFormat.OFF_HOTBAR_PAGE)
        assertEquals(231, TerradockBridgeFormat.OFF_RESERVED0)
        assertEquals(232, TerradockBridgeFormat.OFF_RESERVED1)
        assertEquals(252, TerradockBridgeFormat.OFF_CRC)
        assertEquals(512, TerradockBridgeFormat.FILE_SIZE)
        assertEquals(256, TerradockBridgeFormat.SLOT_SIZE)
        assertEquals(252, TerradockBridgeFormat.CRC_LENGTH)
    }

    @Test
    fun writtenSlotRoundTrips() {
        val buffer = newBuffer()
        val seq = 1
        val base = writeSampleSlot(
            buffer, seq,
            stickLX = 0.5f,
            held = TerradockBridgeFormat.ACTION_JUMP or TerradockBridgeFormat.ACTION_FIRE,
            pressed = TerradockBridgeFormat.ACTION_JUMP,
            released = TerradockBridgeFormat.ACTION_HEAL,
            touchCount = 2,
            zoom = 1.25f,
            smartCursor = 1,
            hotbarPage = 1
        )
        assertEquals(TerradockBridgeFormat.SLOT_SIZE, base)
        assertEquals('T'.code.toByte(), buffer.get(base + TerradockBridgeFormat.OFF_MAGIC))
        assertEquals('R'.code.toByte(), buffer.get(base + TerradockBridgeFormat.OFF_MAGIC + 1))
        assertEquals('D'.code.toByte(), buffer.get(base + TerradockBridgeFormat.OFF_MAGIC + 2))
        assertEquals('K'.code.toByte(), buffer.get(base + TerradockBridgeFormat.OFF_MAGIC + 3))
        assertEquals(TerradockBridgeFormat.VERSION, buffer.getShort(base + 4).toInt())
        assertEquals(TerradockBridgeFormat.SLOT_SIZE, buffer.getShort(base + 6).toInt())
        assertEquals(seq, buffer.getInt(base + TerradockBridgeFormat.OFF_SEQ))
        assertEquals(123456789L, buffer.getLong(base + TerradockBridgeFormat.OFF_TIMESTAMP))
        assertEquals(0.5f, buffer.getFloat(base + TerradockBridgeFormat.OFF_STICK_LX), 0f)
        assertEquals(
            TerradockBridgeFormat.ACTION_JUMP or TerradockBridgeFormat.ACTION_FIRE,
            buffer.getInt(base + TerradockBridgeFormat.OFF_HELD)
        )
        assertEquals(TerradockBridgeFormat.ACTION_JUMP, buffer.getInt(base + TerradockBridgeFormat.OFF_PRESSED))
        assertEquals(TerradockBridgeFormat.ACTION_HEAL, buffer.getInt(base + TerradockBridgeFormat.OFF_RELEASED))
        assertEquals(2400f, buffer.getFloat(base + TerradockBridgeFormat.OFF_SCREEN_W), 0f)
        assertEquals(1080f, buffer.getFloat(base + TerradockBridgeFormat.OFF_SCREEN_H), 0f)
        assertEquals(1.25f, buffer.getFloat(base + TerradockBridgeFormat.OFF_ZOOM), 0f)
        assertEquals(
            TerradockBridgeFormat.AIM_MODE_AND_USE.toByte(),
            buffer.get(base + TerradockBridgeFormat.OFF_AIM_MODE)
        )
        assertEquals(1.toByte(), buffer.get(base + TerradockBridgeFormat.OFF_SMART_CURSOR))
        assertEquals(1.toByte(), buffer.get(base + TerradockBridgeFormat.OFF_HOTBAR_PAGE))
        assertEquals(0.toByte(), buffer.get(base + TerradockBridgeFormat.OFF_RESERVED0))
        for (k in 0 until 20) {
            assertEquals(0.toByte(), buffer.get(base + TerradockBridgeFormat.OFF_RESERVED1 + k))
        }
        // 触控记录
        val t0 = base + TerradockBridgeFormat.OFF_TOUCHES
        assertEquals(0.toByte(), buffer.get(t0))
        assertEquals(TerradockBridgeFormat.TOUCH_FLAG_DOWN.toByte(), buffer.get(t0 + 1))
        assertEquals(0f, buffer.getFloat(t0 + 2), 0f)
        assertEquals(3f, buffer.getFloat(t0 + 16 + 2), 0f)
        assertEquals(5f, buffer.getFloat(t0 + 16 + 6), 0f)
        assertEquals(0.5f, buffer.getFloat(t0 + 16 + 10), 0f)
        // 第 3 条起未使用 → 全零
        for (i in 2 until TerradockBridgeFormat.TOUCH_COUNT) {
            for (k in 0 until TerradockBridgeFormat.TOUCH_RECORD_SIZE) {
                assertEquals(
                    0.toByte(),
                    buffer.get(t0 + i * TerradockBridgeFormat.TOUCH_RECORD_SIZE + k)
                )
            }
        }
    }

    @Test
    fun crcMatchesJavaUtilCrc32() {
        val buffer = newBuffer()
        val base = writeSampleSlot(buffer, 2, stickLX = -0.75f, touchCount = 3)
        val expected = CRC32().apply {
            val copy = ByteArray(TerradockBridgeFormat.CRC_LENGTH)
            for (i in 0 until TerradockBridgeFormat.CRC_LENGTH) copy[i] = buffer.get(base + i)
            update(copy)
        }.value.toInt()
        assertEquals(expected, TerradockBridgeFormat.crc32(buffer, base, TerradockBridgeFormat.CRC_LENGTH))
        assertEquals(expected, buffer.getInt(base + TerradockBridgeFormat.OFF_CRC))
    }

    @Test
    fun crcDetectsEverySingleByteChangeInCoveredRange() {
        val buffer = newBuffer()
        val base = writeSampleSlot(buffer, 1, held = TerradockBridgeFormat.ACTION_JUMP)
        val good = buffer.getInt(base + TerradockBridgeFormat.OFF_CRC)
        for (offset in 0 until TerradockBridgeFormat.CRC_LENGTH) {
            val old = buffer.get(base + offset)
            buffer.put(base + offset, (old.toInt() xor 0x01).toByte())
            assertNotEquals(
                "CRC must change when byte $offset changes",
                good,
                TerradockBridgeFormat.crc32(buffer, base, TerradockBridgeFormat.CRC_LENGTH)
            )
            buffer.put(base + offset, old)
        }
        assertEquals(good, buffer.getInt(base + TerradockBridgeFormat.OFF_CRC))
    }

    @Test
    fun slotAlternatesAndSequenceWrapsAsInt32() {
        assertEquals(0, TerradockBridgeFormat.slotForSeq(0))
        assertEquals(1, TerradockBridgeFormat.slotForSeq(1))
        assertEquals(0, TerradockBridgeFormat.slotForSeq(2))
        assertEquals(TerradockBridgeFormat.SLOT_SIZE, TerradockBridgeFormat.slotBase(3))
        assertTrue(TerradockBridgeFormat.isNewer(2, 1))
        assertFalse(TerradockBridgeFormat.isNewer(1, 2))
        // 2^32 回绕：0xFFFFFFFE(-2) → 0xFFFFFFFF(-1) → 0 → 1
        assertTrue(TerradockBridgeFormat.isNewer(-1, -2))
        assertTrue(TerradockBridgeFormat.isNewer(0, -1))
        assertTrue(TerradockBridgeFormat.isNewer(1, 0))
        assertFalse(TerradockBridgeFormat.isNewer(-2, 1))
    }

    @Test
    fun zeroFillCreatesInvalidSlots() {
        val buffer = newBuffer()
        for (i in 0 until TerradockBridgeFormat.FILE_SIZE) {
            assertEquals(0.toByte(), buffer.get(i))
        }
        // 全零槽：magic 不匹配 → reader 会忽略（关键：未初始化的文件不能被当成有效帧）
        assertNotEquals('T'.code.toByte(), buffer.get(0))
        assertEquals(
            0,
            TerradockBridgeFormat.crc32(buffer, 0, TerradockBridgeFormat.CRC_LENGTH)
                .let { if (buffer.getInt(TerradockBridgeFormat.OFF_CRC) == it) 1 else 0 }
        )
    }
}
