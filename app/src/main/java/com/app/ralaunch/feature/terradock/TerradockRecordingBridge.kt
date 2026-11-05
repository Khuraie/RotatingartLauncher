package com.app.ralaunch.feature.terradock

import com.app.ralaunch.core.common.SettingsAccess
import com.app.ralaunch.feature.controls.ControlData
import com.app.ralaunch.feature.controls.bridges.ControlInputBridge
import kotlin.math.hypot

/**
 * TERRADOCK 输入合成层 —— 装饰 [ControlInputBridge]：
 *
 * 1. **转发**：所有按键/鼠标/滚轮事件原样透传给真正的 SDL 桥（[delegate]），不改变既有手感；
 * 2. **记录**：把实际合成到游戏的动作写进 [TerradockInputState]（桥接文件里的 held/pressed/released
 *    就是真实发生的输入，而不是另建一套状态）；
 * 3. **摇杆合成**（每帧 [tick]）：
 *    - `move` 摇杆 → WASD（死区 0.12，八向 + 斜向组合，与桌面 Terraria 一致）；
 *    - `aim` 摇杆 → 相对鼠标移动（灵敏度复用 RAL 的 `mouseRightStickSpeed`，px/s）；
 *    - Aim & Use 模式下 `aim` 摇杆越过开火阈值（0.65）→ 保持按住左键（spec flags.bit5）；
 * 4. **launcher 专属动作**：ZOOM_IN/ZOOM_OUT/TARGET_LOCK 只进桥接位域，不往桌面转发
 *    （这三个键在控制包里没有桌面语义；缩放由 mod 读桥接后做相机缩放）。
 *
 * 动作位 ↔ 桌面输入的映射表见 bridge-spec.md。
 */
class TerradockRecordingBridge(
    private val delegate: ControlInputBridge,
    /** 是否吞掉「只属于 launcher」的按键（缩放/锁定）。由服务按开关设置传入。 */
    private val swallowLauncherOnlyKeys: Boolean = true
) : ControlInputBridge {

    companion object {
        const val TAG = "TerradockRecordingBridge"

        /** 移动摇杆死区（半径归一化） */
        const val MOVE_DEADZONE = 0.12f
        /** 瞄准摇杆死区 */
        const val AIM_DEADZONE = 0.12f
        /** 方向键触发阈值（死区归一化之后） */
        private const val AXIS_THRESHOLD = 0.38f
        /** Aim & Use 自动开火阈值（半径归一化） */
        const val AUTO_FIRE_THRESHOLD = 0.65f
        /** 满偏时相对鼠标速度（px/s）= 900 * (mouseRightStickSpeed / 100) */
        private const val MOUSE_SPEED_BASE = 900f

        private val MOVE_KEYS = arrayOf(
            ControlData.KeyCode.KEYBOARD_W,
            ControlData.KeyCode.KEYBOARD_D,
            ControlData.KeyCode.KEYBOARD_S,
            ControlData.KeyCode.KEYBOARD_A
        )

        /** 动作位 → 桌面按键（供参考/文档；实际由 [actionForKey] 反查） */
        fun isLauncherOnlyKey(keycode: ControlData.KeyCode): Boolean =
            keycode == ControlData.KeyCode.KEYBOARD_PAGEUP ||
                keycode == ControlData.KeyCode.KEYBOARD_PAGEDOWN ||
                keycode == ControlData.KeyCode.KEYBOARD_TAB ||
                keycode == ControlData.KeyCode.KEYBOARD_F1 ||
                keycode == ControlData.KeyCode.KEYBOARD_F2
    }

    private val state = TerradockInputState

    // 移动摇杆合成的 4 个键的按下状态 [W, D, S, A]
    private val moveDown = BooleanArray(4)
    /** 每帧复用的目标状态缓冲（避免每帧分配） */
    private val desiredMove = BooleanArray(4)

    // 显式开火来源计数（USE 按钮 / 触摸板左键 / AIM_MODE 按住）
    private var explicitFireSources = 0
    private var autoFire = false
    /** AIM_MODE 按钮（桥接 AUTO_FIRE，bit9）按住时由 launcher 保持左键 */
    private var autoFireButtonHeld = false
    private var fireSent = false

    // ===================== 每帧合成 =====================

    /** 由 [TerradockBridgeService] 在每个 Choreographer 帧调用，[dt] 秒。 */
    fun tick(dt: Float) {
        applyMoveStick()
        applyAimStick(dt)
        // flags.bit5：launcher 正在合成“保持开火”（瞄摇杆越界 或 AIM_MODE 按住）
        state.autoFireActive = autoFire || autoFireButtonHeld
    }

    private fun applyMoveStick() {
        val x = state.stickLX
        val y = state.stickLY
        val mag = hypot(x, y)
        var nx = 0f
        var ny = 0f
        if (mag > MOVE_DEADZONE) {
            // 死区重映射：0 在死区边缘，1 在满偏
            val scaled = ((mag - MOVE_DEADZONE) / (1f - MOVE_DEADZONE)).coerceIn(0f, 1f)
            nx = x / mag * scaled
            ny = y / mag * scaled
        }
        // 屏幕坐标：Y 向下为正 → W = 上 = ny 负
        val desired = desiredMove
        desired[0] = ny < -AXIS_THRESHOLD // W
        desired[1] = nx > AXIS_THRESHOLD  // D
        desired[2] = ny > AXIS_THRESHOLD  // S
        desired[3] = nx < -AXIS_THRESHOLD // A
        for (i in 0 until 4) {
            if (desired[i] == moveDown[i]) continue
            moveDown[i] = desired[i]
            delegate.sendKey(MOVE_KEYS[i], desired[i])
        }
        // 桥接位：上下微调（攀爬/下跳）
        state.setAction(TerradockBridgeFormat.ACTION_UP, desired[0])
        state.setAction(TerradockBridgeFormat.ACTION_DOWN, desired[2])
    }

    private fun applyAimStick(dt: Float) {
        val ax = state.stickRX
        val ay = state.stickRY
        val mag = hypot(ax, ay)

        if (mag > AIM_DEADZONE) {
            val gain = runCatching { SettingsAccess.mouseRightStickSpeed }.getOrDefault(200)
                .toFloat() / 100f
            val pxPerSec = MOUSE_SPEED_BASE * gain
            delegate.sendMouseMove(ax * pxPerSec * dt, ay * pxPerSec * dt)
        }

        val wantAutoFire = state.aimMode == TerradockBridgeFormat.AIM_MODE_AND_USE &&
            mag > AUTO_FIRE_THRESHOLD
        if (wantAutoFire != autoFire) {
            autoFire = wantAutoFire
            syncFire()
        }
    }

    private fun syncFire() {
        val want = explicitFireSources > 0 || autoFire
        if (want != fireSent) {
            fireSent = want
            delegate.sendMouseButton(ControlData.KeyCode.MOUSE_LEFT, want)
        }
        state.setAction(TerradockBridgeFormat.ACTION_FIRE, want)
    }

    // ===================== ControlInputBridge =====================

    override fun sendKey(keycode: ControlData.KeyCode, isDown: Boolean) {
        val action = actionForKey(keycode)
        if (action != 0) state.setAction(action, isDown)
        when (keycode) {
            ControlData.KeyCode.KEYBOARD_PAGEUP ->
                if (isDown) state.zoom = (state.zoom + 0.1f).coerceAtMost(2f)
            ControlData.KeyCode.KEYBOARD_PAGEDOWN ->
                if (isDown) state.zoom = (state.zoom - 0.1f).coerceAtLeast(0.5f)
            // AIM_MODE 按钮 → bridge bit9 AUTO_FIRE：按住期间 launcher 保持左键
            ControlData.KeyCode.KEYBOARD_F1 -> {
                autoFireButtonHeld = isDown
                explicitFireSources = if (isDown) {
                    (explicitFireSources + 1).coerceAtMost(4)
                } else {
                    (explicitFireSources - 1).coerceAtLeast(0)
                }
                syncFire()
            }
            else -> Unit
        }
        if (keycode == ControlData.KeyCode.KEYBOARD_LSHIFT ||
            keycode == ControlData.KeyCode.KEYBOARD_RSHIFT
        ) {
            state.smartCursor = isDown
        }
        if (swallowLauncherOnlyKeys && isLauncherOnlyKey(keycode)) {
            // launcher 侧动作：只上报桥接位，不转发桌面输入
            return
        }
        delegate.sendKey(keycode, isDown)
    }

    override fun sendMouseButton(button: ControlData.KeyCode, isDown: Boolean) {
        when (button) {
            ControlData.KeyCode.MOUSE_LEFT -> {
                explicitFireSources = if (isDown) {
                    (explicitFireSources + 1).coerceAtMost(4)
                } else {
                    (explicitFireSources - 1).coerceAtLeast(0)
                }
                syncFire()
            }
            ControlData.KeyCode.MOUSE_RIGHT -> {
                state.setAction(TerradockBridgeFormat.ACTION_INTERACT, isDown)
                delegate.sendMouseButton(button, isDown)
            }
            else -> delegate.sendMouseButton(button, isDown)
        }
    }

    override fun sendMouseMove(deltaX: Float, deltaY: Float) {
        delegate.sendMouseMove(deltaX, deltaY)
    }

    override fun sendMouseWheel(scrollY: Float) {
        delegate.sendMouseWheel(scrollY)
    }

    override fun sendMousePosition(x: Float, y: Float) {
        delegate.sendMousePosition(x, y)
    }

    override fun sendXboxLeftStick(x: Float, y: Float) {
        delegate.sendXboxLeftStick(x, y)
    }

    override fun sendXboxRightStick(x: Float, y: Float) {
        delegate.sendXboxRightStick(x, y)
    }

    override fun sendXboxButton(xboxButton: ControlData.KeyCode, isDown: Boolean) {
        delegate.sendXboxButton(xboxButton, isDown)
    }

    override fun sendXboxTrigger(xboxTrigger: ControlData.KeyCode, value: Float) {
        delegate.sendXboxTrigger(xboxTrigger, value)
    }

    override fun startTextInput() = delegate.startTextInput()

    override fun stopTextInput() = delegate.stopTextInput()

    /** TERRADOCK 摇杆模拟量（由 [VirtualJoystick] 在 terradockStick 模式下调用）。 */
    override fun sendStick(stick: String, x: Float, y: Float) {
        state.setStick(stick, x, y)
    }

    // ===================== 生命周期 =====================

    /** 会话结束 / 失焦：把合成后仍按住的键和鼠标全部松开，避免游戏里卡键。 */
    fun releaseAll() {
        for (i in 0 until 4) {
            if (moveDown[i]) {
                moveDown[i] = false
                delegate.sendKey(MOVE_KEYS[i], false)
            }
        }
        if (fireSent) {
            fireSent = false
            delegate.sendMouseButton(ControlData.KeyCode.MOUSE_LEFT, false)
        }
        explicitFireSources = 0
        autoFire = false
        autoFireButtonHeld = false
        state.autoFireActive = false
        // 松开所有由控件按下、仍在 held 里的键 / 鼠标
        val held = state.held
        for (bit in 0..31) {
            val action = 1 shl bit
            if ((held and action) == 0) continue
            val key = keyForAction(action) ?: continue
            delegate.sendKey(key, false)
        }
        if ((held and TerradockBridgeFormat.ACTION_INTERACT) != 0) {
            delegate.sendMouseButton(ControlData.KeyCode.MOUSE_RIGHT, false)
        }
        state.reset()
    }

    // ===================== 映射表 =====================

    private fun actionForKey(keycode: ControlData.KeyCode): Int = when (keycode) {
        ControlData.KeyCode.KEYBOARD_SPACE -> TerradockBridgeFormat.ACTION_JUMP
        ControlData.KeyCode.KEYBOARD_H -> TerradockBridgeFormat.ACTION_HEAL
        ControlData.KeyCode.KEYBOARD_J -> TerradockBridgeFormat.ACTION_MANA
        ControlData.KeyCode.KEYBOARD_B -> TerradockBridgeFormat.ACTION_BUFF
        ControlData.KeyCode.KEYBOARD_R -> TerradockBridgeFormat.ACTION_MOUNT
        ControlData.KeyCode.KEYBOARD_E -> TerradockBridgeFormat.ACTION_GRAPPLE
        ControlData.KeyCode.KEYBOARD_LSHIFT,
        ControlData.KeyCode.KEYBOARD_RSHIFT -> TerradockBridgeFormat.ACTION_SMART_CURSOR
        ControlData.KeyCode.KEYBOARD_ESCAPE -> TerradockBridgeFormat.ACTION_INVENTORY
        ControlData.KeyCode.KEYBOARD_M -> TerradockBridgeFormat.ACTION_MAP
        ControlData.KeyCode.KEYBOARD_PAGEUP -> TerradockBridgeFormat.ACTION_ZOOM_IN
        ControlData.KeyCode.KEYBOARD_PAGEDOWN -> TerradockBridgeFormat.ACTION_ZOOM_OUT
        ControlData.KeyCode.KEYBOARD_TAB -> TerradockBridgeFormat.ACTION_TARGET_LOCK
        // 控制包里的 AIM_MODE 按钮 → AUTO_FIRE（mod 读 ACTION 9）；AUTO_SELECT → AUTO_SELECT
        ControlData.KeyCode.KEYBOARD_F1 -> TerradockBridgeFormat.ACTION_AUTO_FIRE
        ControlData.KeyCode.KEYBOARD_F2 -> TerradockBridgeFormat.ACTION_AUTO_SELECT
        ControlData.KeyCode.MOUSE_RIGHT -> TerradockBridgeFormat.ACTION_INTERACT
        else -> 0
    }

    private fun keyForAction(action: Int): ControlData.KeyCode? = when (action) {
        TerradockBridgeFormat.ACTION_JUMP -> ControlData.KeyCode.KEYBOARD_SPACE
        TerradockBridgeFormat.ACTION_HEAL -> ControlData.KeyCode.KEYBOARD_H
        TerradockBridgeFormat.ACTION_MANA -> ControlData.KeyCode.KEYBOARD_J
        TerradockBridgeFormat.ACTION_BUFF -> ControlData.KeyCode.KEYBOARD_B
        TerradockBridgeFormat.ACTION_MOUNT -> ControlData.KeyCode.KEYBOARD_R
        TerradockBridgeFormat.ACTION_GRAPPLE -> ControlData.KeyCode.KEYBOARD_E
        TerradockBridgeFormat.ACTION_SMART_CURSOR -> ControlData.KeyCode.KEYBOARD_LSHIFT
        TerradockBridgeFormat.ACTION_INVENTORY -> ControlData.KeyCode.KEYBOARD_ESCAPE
        TerradockBridgeFormat.ACTION_MAP -> ControlData.KeyCode.KEYBOARD_M
        else -> null
    }
}
