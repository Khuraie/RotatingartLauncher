package com.app.ralaunch.feature.terradock

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TERRADOCK 输入桥接文件格式（冻结契约 v1）。
 *
 * 规范来源：`C:\Tmobile\terradock\bridge-spec.md`（Lead 所有，勿改）。
 * 消费者：本 launcher（写）+ TMLMobileFeel（C# 读）。
 *
 * 文件 = 2 槽 × 256 字节。每帧写 1 槽（槽号 = seq and 1），seq 自增。
 * 所有整数/浮点均为小端；struct 紧凑无填充。
 *
 * 本类只做序列化，不碰 Android API（因此可在 JVM 单元测试里验证布局/CRC）。
 */
object TerradockBridgeFormat {

    const val FILE_SIZE: Int = 512
    const val SLOT_SIZE: Int = 256
    const val VERSION: Int = 2
    const val MAGIC: String = "TRDK"
    const val TOUCH_COUNT: Int = 10
    const val TOUCH_RECORD_SIZE: Int = 16

    // ===== 槽内偏移（0 基） =====
    const val OFF_MAGIC = 0              // 4  char[4]
    const val OFF_VERSION = 4            // 2  u16
    const val OFF_SLOT_SIZE = 6          // 2  u16
    const val OFF_SEQ = 8                // 4  u32
    const val OFF_TIMESTAMP = 12         // 8  u64
    const val OFF_STICK_LX = 20          // 4  f32
    const val OFF_STICK_LY = 24          // 4  f32
    const val OFF_STICK_RX = 28          // 4  f32
    const val OFF_STICK_RY = 32          // 4  f32
    const val OFF_HELD = 36              // 4  u32
    const val OFF_PRESSED = 40           // 4  u32
    const val OFF_RELEASED = 44          // 4  u32
    const val OFF_SCREEN_W = 48          // 4  f32
    const val OFF_SCREEN_H = 52          // 4  f32
    const val OFF_UI_SCALE = 56          // 4  f32
    const val OFF_FLAGS = 60             // 4  u32
    const val OFF_TOUCHES = 64           // 10 * 16 = 160
    const val OFF_ZOOM = 224             // 4  f32
    const val OFF_AIM_MODE = 228         // 1  u8
    const val OFF_SMART_CURSOR = 229     // 1  u8
    const val OFF_HOTBAR_PAGE = 230      // 1  u8
    const val OFF_RESERVED0 = 231        // 1  u8
    const val OFF_RESERVED1 = 232        // 20 u8[20]
    const val OFF_CRC = 252              // 4  u32  CRC-32 IEEE of bytes 0..251

    /** CRC 覆盖范围：0..251（252 字节） */
    const val CRC_LENGTH: Int = 252

    // ===== 触控记录内偏移 =====
    const val TOUCH_OFF_ID = 0
    const val TOUCH_OFF_FLAGS = 1
    const val TOUCH_OFF_X = 2
    const val TOUCH_OFF_Y = 6
    const val TOUCH_OFF_PRESSURE = 10
    const val TOUCH_OFF_RESERVED = 14

    /** touch.flags: bit0 = down/primary，bit1 = 被控件消费 */
    const val TOUCH_FLAG_DOWN = 0x1
    const val TOUCH_FLAG_CONSUMED = 0x2

    // ===== flags（u32 at 60）=====
    const val FLAG_ALIVE = 0x1             // bit0 本帧桥接存活
    const val FLAG_INVENTORY = 0x2         // bit1 背包打开
    const val FLAG_MAP = 0x4               // bit2 地图打开
    const val FLAG_CONTROLS_VISIBLE = 0x8  // bit3 触摸控件可见
    const val FLAG_LEFT_HANDED = 0x10      // bit4 左手模式
    const val FLAG_AUTO_FIRE = 0x20        // bit5 自动开火（launcher 合成保持）
    const val FLAG_AIM_ASSIST = 0x40       // bit6 瞄准辅助
    const val FLAG_PINCHING = 0x80         // bit7 双指世界手势

    // ===== 动作位（held/pressed/released 共用） =====
    const val ACTION_JUMP = 1 shl 0
    const val ACTION_INTERACT = 1 shl 1
    const val ACTION_FIRE = 1 shl 2
    const val ACTION_HEAL = 1 shl 3
    const val ACTION_MANA = 1 shl 4
    const val ACTION_BUFF = 1 shl 5
    const val ACTION_MOUNT = 1 shl 6
    const val ACTION_GRAPPLE = 1 shl 7
    const val ACTION_SMART_CURSOR = 1 shl 8
    const val ACTION_AUTO_FIRE = 1 shl 9
    const val ACTION_AUTO_SELECT = 1 shl 10
    const val ACTION_TARGET_LOCK = 1 shl 11
    const val ACTION_ZOOM_IN = 1 shl 12
    const val ACTION_ZOOM_OUT = 1 shl 13
    const val ACTION_INVENTORY = 1 shl 14
    const val ACTION_MAP = 1 shl 15
    const val ACTION_EMOTE = 1 shl 16
    const val ACTION_LOADOUT = 1 shl 17
    const val ACTION_UP = 1 shl 18
    const val ACTION_DOWN = 1 shl 19
    const val ACTION_USE_TILE = 1 shl 20
    const val ACTION_PROFILE_SWITCH = 1 shl 21
    const val ACTION_MENU = 1 shl 22

    // ---- v2: actions imported from Terraria mobile (terradock/control-system/mobile-actions.json) ----
    const val ACTION_HOTBAR_NEXT = 1 shl 23
    const val ACTION_HOTBAR_PREV = 1 shl 24
    const val ACTION_PAGE_TOGGLE = 1 shl 25
    const val ACTION_CHAT = 1 shl 26
    const val ACTION_SETTINGS = 1 shl 27
    const val ACTION_MAP_ZOOM_IN = 1 shl 28
    const val ACTION_MAP_ZOOM_OUT = 1 shl 29
    const val ACTION_LOADOUT_CYCLE = 1 shl 30
    /** bit 31 stays reserved */

    /** 瞄准模式：0 = Aim Only，1 = Aim & Use */
    const val AIM_MODE_ONLY = 0
    const val AIM_MODE_AND_USE = 1

    private val CRC_TABLE: IntArray = IntArray(256).also { table ->
        for (n in 0 until 256) {
            var c = n
            for (k in 0 until 8) {
                c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            }
            table[n] = c
        }
    }

    /** 与 java.util.zip.CRC32 / zlib 相同的 CRC-32（IEEE，反射多项式 0xEDB88320）。 */
    fun crc32(buffer: ByteBuffer, offset: Int, length: Int): Int {
        var crc = -1
        for (i in 0 until length) {
            crc = (crc ushr 8) xor CRC_TABLE[(crc xor buffer.get(offset + i).toInt()) and 0xFF]
        }
        return crc.inv()
    }

    /** seq 比较（mod 2^32 回绕，按 int32 差值）：a 是否比 b 新。 */
    fun isNewer(a: Int, b: Int): Boolean = (a - b) > 0

    /** 槽号 = seq and 1 */
    fun slotForSeq(seq: Int): Int = seq and 1

    fun slotBase(seq: Int): Int = slotForSeq(seq) * SLOT_SIZE

    /** 把整块（2 槽）清零，用于打开文件时初始化。 */
    fun zeroFill(buffer: ByteBuffer) {
        for (i in 0 until FILE_SIZE) buffer.put(i, 0)
    }

    /**
     * 序列化一帧的有效负载到 [buffer] 的 [slotBase] 处（含 magic/version/CRC）。
     * 不改变 buffer 的 position/limit/order，零分配。
     */
    fun writeSlot(
        buffer: ByteBuffer,
        slotBase: Int,
        seq: Int,
        timestampMs: Long,
        stickLX: Float,
        stickLY: Float,
        stickRX: Float,
        stickRY: Float,
        held: Int,
        pressed: Int,
        released: Int,
        screenW: Float,
        screenH: Float,
        uiScale: Float,
        flags: Int,
        touchIds: IntArray,
        touchFlags: IntArray,
        touchX: FloatArray,
        touchY: FloatArray,
        touchPressure: FloatArray,
        touchCount: Int,
        zoom: Float,
        aimMode: Int,
        smartCursor: Int,
        hotbarPage: Int
    ) {
        val b = slotBase
        // magic "TRDK"
        buffer.put(b + OFF_MAGIC, MAGIC[0].code.toByte())
        buffer.put(b + OFF_MAGIC + 1, MAGIC[1].code.toByte())
        buffer.put(b + OFF_MAGIC + 2, MAGIC[2].code.toByte())
        buffer.put(b + OFF_MAGIC + 3, MAGIC[3].code.toByte())
        buffer.putShort(b + OFF_VERSION, VERSION.toShort())
        buffer.putShort(b + OFF_SLOT_SIZE, SLOT_SIZE.toShort())
        buffer.putInt(b + OFF_SEQ, seq)
        buffer.putLong(b + OFF_TIMESTAMP, timestampMs)
        buffer.putFloat(b + OFF_STICK_LX, stickLX)
        buffer.putFloat(b + OFF_STICK_LY, stickLY)
        buffer.putFloat(b + OFF_STICK_RX, stickRX)
        buffer.putFloat(b + OFF_STICK_RY, stickRY)
        buffer.putInt(b + OFF_HELD, held)
        buffer.putInt(b + OFF_PRESSED, pressed)
        buffer.putInt(b + OFF_RELEASED, released)
        buffer.putFloat(b + OFF_SCREEN_W, screenW)
        buffer.putFloat(b + OFF_SCREEN_H, screenH)
        buffer.putFloat(b + OFF_UI_SCALE, uiScale)
        buffer.putInt(b + OFF_FLAGS, flags)

        val count = touchCount.coerceIn(0, TOUCH_COUNT)
        for (i in 0 until TOUCH_COUNT) {
            val t = b + OFF_TOUCHES + i * TOUCH_RECORD_SIZE
            if (i >= count) {
                // 未使用的触控记录必须全零
                for (k in 0 until TOUCH_RECORD_SIZE) buffer.put(t + k, 0)
                continue
            }
            buffer.put(t + TOUCH_OFF_ID, (touchIds[i] and 0xFF).toByte())
            buffer.put(t + TOUCH_OFF_FLAGS, (touchFlags[i] and 0xFF).toByte())
            buffer.putFloat(t + TOUCH_OFF_X, touchX[i])
            buffer.putFloat(t + TOUCH_OFF_Y, touchY[i])
            buffer.putFloat(t + TOUCH_OFF_PRESSURE, touchPressure[i])
            buffer.putShort(t + TOUCH_OFF_RESERVED, 0)
        }

        buffer.putFloat(b + OFF_ZOOM, zoom)
        buffer.put(b + OFF_AIM_MODE, (aimMode and 0xFF).toByte())
        buffer.put(b + OFF_SMART_CURSOR, (smartCursor and 0xFF).toByte())
        buffer.put(b + OFF_HOTBAR_PAGE, (hotbarPage and 0xFF).toByte())
        buffer.put(b + OFF_RESERVED0, 0)
        // reserved1: 20 字节保持 0（打开时已清零；这里防御性重写）
        for (k in 0 until 20) buffer.put(b + OFF_RESERVED1 + k, 0)

        buffer.putInt(b + OFF_CRC, crc32(buffer, b, CRC_LENGTH))
    }

    /**
     * 布局 + CRC + seq 回绕自检。返回 true 表示格式与 bridge-spec v1 一致。
     * 由 [TerradockBridgeWriter] 在打开时调用并记录日志；也可在单元测试里直接调用。
     */
    fun selfTest(): Boolean {
        val buffer = ByteBuffer.allocate(FILE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        zeroFill(buffer)

        val ids = IntArray(TOUCH_COUNT) { it }
        val tFlags = IntArray(TOUCH_COUNT) { if (it == 0) TOUCH_FLAG_DOWN else 0 }
        val xs = FloatArray(TOUCH_COUNT) { it * 10f }
        val ys = FloatArray(TOUCH_COUNT) { it * 20f }
        val ps = FloatArray(TOUCH_COUNT) { 0.5f }

        val sampleSeq = 1
        writeSlot(
            buffer = buffer,
            slotBase = slotBase(sampleSeq),
            seq = sampleSeq,
            timestampMs = 0x0102030405060708L,
            stickLX = 0.5f, stickLY = -0.25f, stickRX = 1f, stickRY = 0f,
            held = ACTION_JUMP or ACTION_FIRE,
            pressed = ACTION_JUMP,
            released = 0,
            screenW = 2400f, screenH = 1080f, uiScale = 1f,
            flags = FLAG_ALIVE or FLAG_CONTROLS_VISIBLE,
            touchIds = ids, touchFlags = tFlags,
            touchX = xs, touchY = ys, touchPressure = ps,
            touchCount = 2,
            zoom = 1f,
            aimMode = AIM_MODE_AND_USE,
            smartCursor = 1,
            hotbarPage = 0
        )

        val base = slotBase(sampleSeq)
        if (base != SLOT_SIZE) return false
        // magic / version / slotSize
        if (buffer.get(base + 0) != 'T'.code.toByte()) return false
        if (buffer.get(base + 1) != 'R'.code.toByte()) return false
        if (buffer.get(base + 2) != 'D'.code.toByte()) return false
        if (buffer.get(base + 3) != 'K'.code.toByte()) return false
        if (buffer.getShort(base + OFF_VERSION).toInt() != VERSION) return false
        if (buffer.getShort(base + OFF_SLOT_SIZE).toInt() != SLOT_SIZE) return false
        if (buffer.getInt(base + OFF_SEQ) != sampleSeq) return false
        if (buffer.getLong(base + OFF_TIMESTAMP) != 0x0102030405060708L) return false
        if (buffer.getFloat(base + OFF_STICK_LX) != 0.5f) return false
        if (buffer.getFloat(base + OFF_STICK_LY) != -0.25f) return false
        if (buffer.getFloat(base + OFF_STICK_RX) != 1f) return false
        if (buffer.getFloat(base + OFF_STICK_RY) != 0f) return false
        if (buffer.getInt(base + OFF_HELD) != (ACTION_JUMP or ACTION_FIRE)) return false
        if (buffer.getInt(base + OFF_PRESSED) != ACTION_JUMP) return false
        if (buffer.getInt(base + OFF_RELEASED) != 0) return false
        if (buffer.getFloat(base + OFF_SCREEN_W) != 2400f) return false
        if (buffer.getFloat(base + OFF_SCREEN_H) != 1080f) return false
        if (buffer.getFloat(base + OFF_UI_SCALE) != 1f) return false
        if (buffer.getInt(base + OFF_FLAGS) != (FLAG_ALIVE or FLAG_CONTROLS_VISIBLE)) return false
        // 触控记录
        if (buffer.get(base + OFF_TOUCHES + TOUCH_OFF_ID) != 0.toByte()) return false
        if (buffer.get(base + OFF_TOUCHES + TOUCH_OFF_FLAGS) != TOUCH_FLAG_DOWN.toByte()) return false
        if (buffer.getFloat(base + OFF_TOUCHES + TOUCH_OFF_X) != 0f) return false
        if (buffer.getFloat(base + OFF_TOUCHES + 16 + TOUCH_OFF_X) != 10f) return false
        if (buffer.getFloat(base + OFF_TOUCHES + 16 + TOUCH_OFF_Y) != 20f) return false
        // 第 3 条（index 2）未使用 → 全零
        for (k in 0 until TOUCH_RECORD_SIZE) {
            if (buffer.get(base + OFF_TOUCHES + 2 * TOUCH_RECORD_SIZE + k) != 0.toByte()) return false
        }
        if (buffer.getFloat(base + OFF_ZOOM) != 1f) return false
        if (buffer.get(base + OFF_AIM_MODE) != AIM_MODE_AND_USE.toByte()) return false
        if (buffer.get(base + OFF_SMART_CURSOR) != 1.toByte()) return false
        if (buffer.get(base + OFF_HOTBAR_PAGE) != 0.toByte()) return false
        if (buffer.get(base + OFF_RESERVED0) != 0.toByte()) return false
        for (k in 0 until 20) {
            if (buffer.get(base + OFF_RESERVED1 + k) != 0.toByte()) return false
        }
        // CRC
        val stored = buffer.getInt(base + OFF_CRC)
        if (stored != crc32(buffer, base, CRC_LENGTH)) return false
        // 翻转 CRC 覆盖范围内 1 字节 → CRC 必须变化
        buffer.put(base + OFF_STICK_LX, (buffer.get(base + OFF_STICK_LX).toInt() xor 0x01).toByte())
        if (stored == crc32(buffer, base, CRC_LENGTH)) return false

        // seq 回绕 / 槽交替
        if (slotForSeq(0) != 0 || slotForSeq(1) != 1 || slotForSeq(2) != 0) return false
        if (!isNewer(2, 1)) return false
        // 2^32 回绕：0xFFFFFFFE -> 0 -> 1 视为递增
        if (!isNewer(0, -2)) return false // -2 == 0xFFFFFFFE
        if (isNewer(-2, 0)) return false
        if (isNewer(1, 2)) return false

        return true
    }
}
