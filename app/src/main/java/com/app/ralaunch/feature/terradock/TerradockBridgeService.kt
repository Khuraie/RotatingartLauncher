package com.app.ralaunch.feature.terradock

import android.app.Activity
import android.view.Choreographer
import com.app.ralaunch.core.common.SettingsAccess
import com.app.ralaunch.core.logging.AppLog

/**
 * TERRADOCK 桥接服务 —— 每个 Choreographer 帧（≈显示刷新率，通常 60 Hz）：
 *
 * 1. 采集屏幕尺寸 / 设置（瞄准模式、控件可见性）；
 * 2. 调 [TerradockRecordingBridge.tick] 做摇杆合成（WASD / 相对鼠标 / 自动开火）；
 * 3. 把 [TerradockInputState] 写进 `<RAL HOME>/ral_controls/state.bin`（512B 双槽，零分配）；
 * 4. 清边沿位。
 *
 * 生命周期：GameVirtualControlsManager.initialize() 启动，stop() 结束（会话结束/Activity 销毁）。
 * 关闭设置项 `terradockBridgeEnabled` 时不启动。
 */
object TerradockBridgeService {

    private const val TAG = "TerradockBridgeService"

    private var writer: TerradockBridgeWriter? = null
    private var bridge: TerradockRecordingBridge? = null
    private var activity: Activity? = null
    private var controlsVisibleProvider: () -> Boolean = { true }
    private var running = false
    private var lastFrameNanos = 0L
    private var frameCount: Long = 0
    private var reportEveryFrames: Long = 600

    private val state = TerradockInputState

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            try {
                onFrame(frameTimeNanos)
            } catch (t: Throwable) {
                AppLog.w(TAG, "TERRADOCK frame error: ${t.message}")
            }
            if (running) {
                runCatching { Choreographer.getInstance().postFrameCallback(this) }
            }
        }
    }

    val isRunning: Boolean
        get() = running

    /** 当前桥接文件路径（诊断用；未启动时按同样规则解析）。 */
    fun currentPath(activity: Activity): String =
        writer?.file?.absolutePath
            ?: TerradockBridgeWriter(activity.applicationContext).resolveBridgeFile().absolutePath

    /**
     * 启动桥接写入。必须在主线程调用。
     * @return 是否开始写盘
     */
    fun start(
        activity: Activity,
        bridge: TerradockRecordingBridge,
        controlsVisible: () -> Boolean = { true }
    ): Boolean {
        if (running) return true
        val enabled = runCatching { SettingsAccess.isTerradockBridgeEnabled }.getOrDefault(true)
        if (!enabled) {
            AppLog.i(TAG, "TERRADOCK bridge disabled by setting; not starting")
            return false
        }
        this.activity = activity
        this.bridge = bridge
        this.controlsVisibleProvider = controlsVisible
        val w = TerradockBridgeWriter(activity.applicationContext)
        val opened = w.open()
        writer = w
        state.reset()
        state.screenW = activity.resources.displayMetrics.widthPixels.toFloat()
        state.screenH = activity.resources.displayMetrics.heightPixels.toFloat()
        state.uiScale = 1f
        state.aimMode = readAimMode()
        state.smartCursor = false
        lastFrameNanos = 0L
        frameCount = 0
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        AppLog.i(
            TAG,
            "TERRADOCK bridge service started (open=$opened, path=${w.file?.absolutePath}, " +
                "aimMode=${state.aimMode})"
        )
        return opened
    }

    private fun readAimMode(): Int {
        val v = runCatching { SettingsAccess.terradockAimMode }.getOrDefault(1)
        return if (v == 0) TerradockBridgeFormat.AIM_MODE_ONLY else TerradockBridgeFormat.AIM_MODE_AND_USE
    }

    private fun onFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        }
        lastFrameNanos = frameTimeNanos
        frameCount += 1

        val act = activity
        if (act != null) {
            val dm = act.resources.displayMetrics
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                state.screenW = dm.widthPixels.toFloat()
                state.screenH = dm.heightPixels.toFloat()
            }
        }
        state.aimMode = readAimMode()
        state.flags = buildFlags()

        bridge?.tick(dt)
        writer?.writeFrame(state)
        state.clearEdges()

        if (frameCount % reportEveryFrames == 0L) {
            AppLog.d(
                TAG,
                "TERRADOCK frames=$frameCount held=0x${Integer.toHexString(state.held)} " +
                    "move=(${state.stickLX},${state.stickLY}) aim=(${state.stickRX},${state.stickRY}) " +
                    "flags=0x${Integer.toHexString(state.flags)}"
            )
        }
    }

    private fun buildFlags(): Int {
        var f = TerradockBridgeFormat.FLAG_ALIVE
        if (state.touchCount >= 2) f = f or TerradockBridgeFormat.FLAG_PINCHING
        if (state.autoFireActive) f = f or TerradockBridgeFormat.FLAG_AUTO_FIRE
        if (state.isActionHeld(TerradockBridgeFormat.ACTION_INVENTORY)) {
            f = f or TerradockBridgeFormat.FLAG_INVENTORY
        }
        if (state.isActionHeld(TerradockBridgeFormat.ACTION_MAP)) {
            f = f or TerradockBridgeFormat.FLAG_MAP
        }
        val visible = runCatching { controlsVisibleProvider() }.getOrDefault(true)
        if (visible) f = f or TerradockBridgeFormat.FLAG_CONTROLS_VISIBLE
        return f
    }

    /** 结束会话：松开所有合成输入 → 桥接文件清零并删除。必须在主线程调用。 */
    fun stop() {
        if (!running) {
            writer?.close()
            writer = null
            bridge = null
            return
        }
        running = false
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        runCatching { bridge?.releaseAll() }
        AppLog.i(TAG, "TERRADOCK bridge service stopping after $frameCount frame(s)")
        runCatching { writer?.close() }
        writer = null
        bridge = null
        activity = null
        state.reset()
    }
}
