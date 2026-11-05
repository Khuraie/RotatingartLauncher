package com.app.ralaunch.feature.terradock

/**
 * TERRADOCK 输入状态快照 —— 桥接写入与合成共用的唯一状态源。
 *
 * 只有主线程（View 触摸事件 / Choreographer 帧回调）读写，因此不加锁；
 * 字段加 @Volatile 只是让诊断线程读到一致值。
 *
 * 设计原则（见任务书）：**桥接文件里的按钮位域必须就是实际合成到 SDL 的输入**，
 * 所以所有 sendKey/sendMouseButton 都先经过 [TerradockRecordingBridge] 记录到这里，
 * 再由 [TerradockBridgeService] 每帧原样写盘。
 */
object TerradockInputState {

    // ===== 摇杆（半径归一化，-1..1；Y 向下为正，屏幕坐标）=====
    @Volatile var stickLX: Float = 0f
    @Volatile var stickLY: Float = 0f
    @Volatile var stickRX: Float = 0f
    @Volatile var stickRY: Float = 0f

    // ===== 动作位域 =====
    @Volatile var held: Int = 0
    @Volatile var pressed: Int = 0
    @Volatile var released: Int = 0

    // ===== 表面/显示 =====
    @Volatile var screenW: Float = 0f
    @Volatile var screenH: Float = 0f
    @Volatile var uiScale: Float = 1f
    @Volatile var flags: Int = 0

    // ===== 其他 =====
    @Volatile var zoom: Float = 1f
    @Volatile var aimMode: Int = TerradockBridgeFormat.AIM_MODE_AND_USE
    @Volatile var smartCursor: Boolean = false
    @Volatile var hotbarPage: Int = 0

    // ===== 触控记录（最多 10）=====
    val touchIds = IntArray(TerradockBridgeFormat.TOUCH_COUNT)
    val touchFlags = IntArray(TerradockBridgeFormat.TOUCH_COUNT)
    val touchX = FloatArray(TerradockBridgeFormat.TOUCH_COUNT)
    val touchY = FloatArray(TerradockBridgeFormat.TOUCH_COUNT)
    val touchPressure = FloatArray(TerradockBridgeFormat.TOUCH_COUNT)
    @Volatile var touchCount: Int = 0

    /** 当前帧是否还有“自动开火”源（瞄摇杆越界 / AUTO_FIRE 动作） */
    @Volatile var autoFireActive: Boolean = false

    fun setAction(action: Int, down: Boolean) {
        if (action == 0) return
        val wasHeld = (held and action) != 0
        if (down) {
            if (!wasHeld) {
                held = held or action
                pressed = pressed or action
            }
        } else {
            if (wasHeld) {
                held = held and action.inv()
                released = released or action
            }
        }
    }

    fun isActionHeld(action: Int): Boolean = (held and action) != 0

    /** 每帧写盘之后清掉边沿位。 */
    fun clearEdges() {
        pressed = 0
        released = 0
    }

    /** 会话结束/失焦：清空一切，避免 mod 侧残留按住状态。 */
    fun reset() {
        stickLX = 0f
        stickLY = 0f
        stickRX = 0f
        stickRY = 0f
        held = 0
        pressed = 0
        released = 0
        flags = 0
        zoom = 1f
        smartCursor = false
        autoFireActive = false
        touchCount = 0
        for (i in 0 until TerradockBridgeFormat.TOUCH_COUNT) {
            touchIds[i] = 0
            touchFlags[i] = 0
            touchX[i] = 0f
            touchY[i] = 0f
            touchPressure[i] = 0f
        }
    }

    /**
     * 记录一次触摸事件的全部指针（最多 10 条）。
     *
     * [pointerIds]/[xs]/[ys]/[pressures] 长度 >= [count]；
     * [consumed] 表示该触点是否被某个虚拟控件消费（对应 touch.flags bit1）。
     */
    fun setTouches(
        count: Int,
        pointerIds: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        pressures: FloatArray,
        consumed: BooleanArray
    ) {
        val n = count.coerceIn(0, TerradockBridgeFormat.TOUCH_COUNT)
        touchCount = n
        for (i in 0 until n) {
            touchIds[i] = pointerIds[i]
            touchX[i] = xs[i]
            touchY[i] = ys[i]
            touchPressure[i] = pressures[i].coerceIn(0f, 1f)
            var f = TerradockBridgeFormat.TOUCH_FLAG_DOWN
            if (consumed[i]) f = f or TerradockBridgeFormat.TOUCH_FLAG_CONSUMED
            touchFlags[i] = f
        }
    }

    fun setStick(stick: String, x: Float, y: Float) {
        when (stick) {
            "move" -> {
                stickLX = x.coerceIn(-1f, 1f)
                stickLY = y.coerceIn(-1f, 1f)
            }
            "aim" -> {
                stickRX = x.coerceIn(-1f, 1f)
                stickRY = y.coerceIn(-1f, 1f)
            }
        }
    }
}
