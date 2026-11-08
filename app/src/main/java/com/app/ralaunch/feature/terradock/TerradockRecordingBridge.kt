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

        /** 手势时间窗（毫秒）：单击判定 / 双击间隔 / 旧版上滑 / 双击后的飞行保持窗 */
        private const val TAP_MAX_MS = 220L
        private const val DOUBLE_TAP_MS = 320L
        private const val SWIPE_MAX_MS = 300L
        private const val FLY_HOLD_WINDOW_MS = 600L

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
                keycode == ControlData.KeyCode.KEYBOARD_F2 ||
                // v2: mobile controls that only exist as bridge actions in the port
                keycode == ControlData.KeyCode.KEYBOARD_F3 ||   // PAGE_TOGGLE
                keycode == ControlData.KeyCode.KEYBOARD_F5 ||   // LOADOUT_CYCLE
                keycode == ControlData.KeyCode.KEYBOARD_F6 ||   // EMOTE
                keycode == ControlData.KeyCode.KEYBOARD_EQUALS ||   // MAP_ZOOM_IN
                keycode == ControlData.KeyCode.KEYBOARD_MINUS       // MAP_ZOOM_OUT
        // NOTE: mouse wheel is forwarded on purpose (desktop Terraria scrolls the hotbar with it),
        // so HOTBAR_NEXT/PREV must not be swallowed. SETTINGS forwards Escape (opens the game menu).
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

    // ===================== 左摇杆手势（手机版 Up To Jump / 双击冲刺） =====================
    /**
     * 手机版 `GUIVirtualInputController.LeftStickJumpMode`：DoubleTap（默认）/ SingleTap /
     * Up（旧版上滑）/ Disabled。桌面 Terraria 没有这些手势，所以由 launcher 合成：
     *  - 上跳：按设置给 Space（单击 / 双击 / 上滑）；双击后继续按住 → 持续 Space（翅膀飞行/滑翔）
     *  - 左右双击 → 连按两次 A/D（桌面靠双击方向键触发冲刺）
     * 下双击（套装加成）桌面没有对应语义，不合成。
     */
    enum class UpToJumpMode { DoubleTap, SingleTap, Up, Disabled }

    @Volatile
    var upToJumpMode: UpToJumpMode = UpToJumpMode.DoubleTap

    /** 上一次从控件包读到的 LeftStickJump 字符串（只在变化时重算枚举，避免每帧字符串比较） */
    private var lastJumpModeSetting: String? = null

    /** 把控件包里的手机版设置同步到手势模式。 */
    private fun syncPackSettings() {
        val raw = TerradockControlPack.upToJumpModeSetting
        if (raw == lastJumpModeSetting) return
        lastJumpModeSetting = raw
        upToJumpMode = when (raw) {
            "Single Tap" -> UpToJumpMode.SingleTap
            "Swipe" -> UpToJumpMode.Up
            "Disabled" -> UpToJumpMode.Disabled
            else -> UpToJumpMode.DoubleTap
        }
    }

    private var upWasDown = false
    private var upDownAt = 0L
    private var lastUpTapAt = 0L
    /** 双击上跳成功后的一小段窗口：在此期间重新按住上 = 持续 Space（飞行/滑翔） */
    private var flyArmedUntil = 0L
    private var jumpHeld = false
    private var leftWasDown = false
    private var rightWasDown = false
    private var lastLeftTapAt = 0L
    private var lastRightTapAt = 0L

    // ===================== 每帧合成 =====================

    /** 由 [TerradockBridgeService] 在每个 Choreographer 帧调用，[dt] 秒。 */
    fun tick(dt: Float) {
        syncPackSettings()
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
        // 手机版手势（上跳 / 双击冲刺）
        applyMoveStickGestures(
            System.nanoTime() / 1_000_000L,
            desired[0], desired[3], desired[1], desired[2]
        )
    }

    /**
     * 左摇杆手势状态机。[nowMs] 为单调毫秒。
     * 只在 [upToJumpMode] 允许时才合成跳跃，避免和用户的点击习惯冲突。
     */
    private fun applyMoveStickGestures(nowMs: Long, up: Boolean, left: Boolean, right: Boolean, down: Boolean) {
        // ---------- 上方向：跳跃 ----------
        if (up && !upWasDown) upDownAt = nowMs
        if (!up && upWasDown) {
            val heldMs = nowMs - upDownAt
            when (upToJumpMode) {
                UpToJumpMode.Disabled -> Unit
                // 旧版上滑：短促推上再松开
                UpToJumpMode.Up -> if (heldMs <= SWIPE_MAX_MS) tapJump()
                UpToJumpMode.SingleTap -> if (heldMs <= TAP_MAX_MS) tapJump()
                UpToJumpMode.DoubleTap -> {
                    if (heldMs <= TAP_MAX_MS) {
                        if (lastUpTapAt != 0L && nowMs - lastUpTapAt <= DOUBLE_TAP_MS) {
                            // 双击成立：起跳，并打开"继续按住 = 飞行"的窗口
                            tapJump()
                            flyArmedUntil = nowMs + FLY_HOLD_WINDOW_MS
                            lastUpTapAt = 0L
                        } else {
                            lastUpTapAt = nowMs
                        }
                    }
                }
            }
        }
        // 双击后重新按住上（窗口内）→ 保持 Space（飞行/滑翔）
        val holdingFly = upToJumpMode == UpToJumpMode.DoubleTap &&
            up && flyArmedUntil != 0L && nowMs <= flyArmedUntil
        if (holdingFly != jumpHeld) {
            jumpHeld = holdingFly
            delegate.sendKey(ControlData.KeyCode.KEYBOARD_SPACE, holdingFly)
            state.setAction(TerradockBridgeFormat.ACTION_JUMP, holdingFly)
        }
        if (flyArmedUntil != 0L && nowMs > flyArmedUntil) flyArmedUntil = 0L
        upWasDown = up

        // ---------- 左右方向：双击冲刺（桌面按双击 A/D 触发） ----------
        if (left && !leftWasDown) {
            if (lastLeftTapAt != 0L && nowMs - lastLeftTapAt <= DOUBLE_TAP_MS) {
                doubleTapDirection(MOVE_KEYS[3])   // A
                lastLeftTapAt = 0L
            } else {
                lastLeftTapAt = nowMs
            }
        }
        if (right && !rightWasDown) {
            if (lastRightTapAt != 0L && nowMs - lastRightTapAt <= DOUBLE_TAP_MS) {
                doubleTapDirection(MOVE_KEYS[1])   // D
                lastRightTapAt = 0L
            } else {
                lastRightTapAt = nowMs
            }
        }
        leftWasDown = left
        rightWasDown = right
        // 下方向：桌面没有“双击下 = 套装加成”的等价操作，不合成。
    }

    /** 合成一次跳跃点击（Space 按下 + 松开）。 */
    private fun tapJump() {
        delegate.sendKey(ControlData.KeyCode.KEYBOARD_SPACE, true)
        delegate.sendKey(ControlData.KeyCode.KEYBOARD_SPACE, false)
    }

    /** 合成一次方向键“第二下”（键已按住）：松开再按下，游戏即可识别双击。 */
    private fun doubleTapDirection(key: ControlData.KeyCode) {
        delegate.sendKey(key, false)
        delegate.sendKey(key, true)
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
            // 手机版“换页”按钮（PAGE_TOGGLE）：切换快捷栏/背包页
            ControlData.KeyCode.KEYBOARD_F3 -> {
                if (isDown) state.hotbarPage = if (state.hotbarPage == 0) 1 else 0
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
        // 手机版 HOTBAR_PREV/NEXT：滚轮本身前向转发（桌面 Terraria 用滚轮切快捷栏），
        // 同时把方向位写进桥接供 mod 显示。
        if (scrollY != 0f) {
            val bit = if (scrollY > 0f) TerradockBridgeFormat.ACTION_HOTBAR_NEXT
            else TerradockBridgeFormat.ACTION_HOTBAR_PREV
            state.setAction(bit, true)
            state.setAction(bit, false)
        }
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
        if (jumpHeld) {
            jumpHeld = false
            delegate.sendKey(ControlData.KeyCode.KEYBOARD_SPACE, false)
        }
        upWasDown = false
        leftWasDown = false
        rightWasDown = false
        upDownAt = 0L
        lastUpTapAt = 0L
        flyArmedUntil = 0L
        lastLeftTapAt = 0L
        lastRightTapAt = 0L
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
        // ---- v2 (imported from Terraria mobile) ----
        ControlData.KeyCode.MOUSE_WHEEL_DOWN -> TerradockBridgeFormat.ACTION_HOTBAR_NEXT
        ControlData.KeyCode.MOUSE_WHEEL_UP -> TerradockBridgeFormat.ACTION_HOTBAR_PREV
        ControlData.KeyCode.KEYBOARD_F3 -> TerradockBridgeFormat.ACTION_PAGE_TOGGLE
        ControlData.KeyCode.KEYBOARD_RETURN -> TerradockBridgeFormat.ACTION_CHAT
        ControlData.KeyCode.KEYBOARD_F4 -> TerradockBridgeFormat.ACTION_SETTINGS
        ControlData.KeyCode.KEYBOARD_EQUALS -> TerradockBridgeFormat.ACTION_MAP_ZOOM_IN
        ControlData.KeyCode.KEYBOARD_MINUS -> TerradockBridgeFormat.ACTION_MAP_ZOOM_OUT
        ControlData.KeyCode.KEYBOARD_F5 -> TerradockBridgeFormat.ACTION_LOADOUT_CYCLE
        ControlData.KeyCode.KEYBOARD_F6 -> TerradockBridgeFormat.ACTION_EMOTE
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
