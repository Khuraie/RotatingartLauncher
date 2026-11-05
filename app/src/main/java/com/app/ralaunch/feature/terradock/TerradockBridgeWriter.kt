package com.app.ralaunch.feature.terradock

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import com.app.ralaunch.core.logging.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TERRADOCK 桥接写入器 —— `<RAL HOME>/ral_controls/state.bin`，512 字节双槽。
 *
 * 契约：`C:\Tmobile\terradock\bridge-spec.md`（冻结；勿改）。
 * - 路径解析顺序：
 *   1. 环境变量 `TERRADOCK_BRIDGE`（完整文件路径；mod 侧同样优先读它）
 *   2. `<ExternalStorage>/RALauncher`（RAL 的默认 HOME，见 GameLauncher.prepareDataDirectory）
 *   3. `HOME` 环境变量（RAL 启动游戏时把它设成数据目录）
 *   4. `context.filesDir`（最终回退，模组读不到则视为桥缺失）
 * - 每帧调用 [writeFrame]：单块 direct ByteBuffer，零分配，不 fsync
 *   （写穿页缓存即可；mod 用普通 read 读同一文件，无需 force —— 避免 60 Hz fsync 阻塞渲染）。
 * - 会话结束调用 [close]：把两槽清零（CRC 失效 → mod 立刻判定 stale 并回退鼠标），再删文件/空目录。
 */
class TerradockBridgeWriter(private val context: Context) {

    companion object {
        private const val TAG = "TerradockBridgeWriter"
        const val SUB_DIR = "ral_controls"
        const val FILE_NAME = "state.bin"
        const val ENV_OVERRIDE = "TERRADOCK_BRIDGE"
        /** RAL 数据目录名（与 GameLauncher.DEFAULT_DATA_DIR_NAME / ControlPackManager.STORAGE_DIR_NAME 一致） */
        const val RAL_HOME_DIR_NAME = "RALauncher"
    }

    var file: File? = null
        private set

    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(TerradockBridgeFormat.FILE_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
    private var seq: Int = 0
    private var frames: Long = 0
    private var openFailed = false

    val isOpen: Boolean
        get() = channel != null

    /** 解析桥接文件完整路径（不创建目录）。 */
    fun resolveBridgeFile(): File {
        System.getenv(ENV_OVERRIDE)?.takeIf { it.isNotBlank() }?.let {
            return File(it)
        }
        val extHome = File(Environment.getExternalStorageDirectory(), RAL_HOME_DIR_NAME)
        // 外部存储可用（或目录已存在）→ 与 RAL 的 HOME 保持一致
        if (extHome.exists() || runCatching { Environment.getExternalStorageDirectory().canWrite() }.getOrDefault(false)) {
            return File(File(extHome, SUB_DIR), FILE_NAME)
        }
        System.getenv("HOME")?.takeIf { it.isNotBlank() && File(it).isAbsolute }?.let {
            return File(File(it, SUB_DIR), FILE_NAME)
        }
        return File(File(context.filesDir, SUB_DIR), FILE_NAME)
    }

    /**
     * 打开（或重新打开）桥接文件。失败时只记一次日志并保持关闭状态，绝不抛出。
     * @return 是否可用
     */
    fun open(): Boolean {
        closeInternal(deleteFile = false)
        val target = resolveBridgeFile()
        file = target
        return try {
            target.parentFile?.mkdirs()
            val fileRaf = RandomAccessFile(target, "rw")
            if (fileRaf.length() != TerradockBridgeFormat.FILE_SIZE.toLong()) {
                fileRaf.setLength(TerradockBridgeFormat.FILE_SIZE.toLong())
            }
            val ch = fileRaf.channel
            // 先整块清零，保证未写过的槽 CRC 无效（mod 会忽略）
            TerradockBridgeFormat.zeroFill(buffer)
            buffer.position(0)
            buffer.limit(TerradockBridgeFormat.FILE_SIZE)
            ch.write(buffer, 0L)
            buffer.position(0)
            raf = fileRaf
            channel = ch
            seq = 0
            frames = 0
            openFailed = false
            AppLog.i(TAG, "TERRADOCK bridge opened: ${target.absolutePath} (512B, spec v${TerradockBridgeFormat.VERSION})")
            // 布局/CRC/seq 自检（只在打开时跑一次）
            val ok = TerradockBridgeFormat.selfTest()
            if (ok) {
                AppLog.i(TAG, "TERRADOCK bridge format self-test: PASS")
            } else {
                AppLog.e(TAG, "TERRADOCK bridge format self-test: FAIL (layout/CRC mismatch vs bridge-spec.md)")
            }
            true
        } catch (t: Throwable) {
            if (!openFailed) {
                openFailed = true
                AppLog.w(TAG, "TERRADOCK bridge open failed: ${target.absolutePath}: ${t.message}")
            }
            closeInternal(deleteFile = false)
            false
        }
    }

    /**
     * 写一帧。[timestampMs] 使用写入方时钟（Android 上为 `SystemClock.elapsedRealtime()`）。
     *
     * 零分配：只操作已分配的 direct buffer；不调用 flush/fsync。
     */
    fun writeFrame(state: TerradockInputState, timestampMs: Long = SystemClock.elapsedRealtime()) {
        val ch = channel ?: return
        seq += 1
        frames += 1
        val slotBase = TerradockBridgeFormat.slotBase(seq)
        TerradockBridgeFormat.writeSlot(
            buffer = buffer,
            slotBase = slotBase,
            seq = seq,
            timestampMs = timestampMs,
            stickLX = state.stickLX,
            stickLY = state.stickLY,
            stickRX = state.stickRX,
            stickRY = state.stickRY,
            held = state.held,
            pressed = state.pressed,
            released = state.released,
            screenW = state.screenW,
            screenH = state.screenH,
            uiScale = state.uiScale,
            flags = state.flags,
            touchIds = state.touchIds,
            touchFlags = state.touchFlags,
            touchX = state.touchX,
            touchY = state.touchY,
            touchPressure = state.touchPressure,
            touchCount = state.touchCount,
            zoom = state.zoom,
            aimMode = state.aimMode,
            smartCursor = if (state.smartCursor) 1 else 0,
            hotbarPage = state.hotbarPage
        )
        try {
            // 只写这一个 256 字节槽（position/limit 是纯内存操作，不分配）
            buffer.limit(slotBase + TerradockBridgeFormat.SLOT_SIZE)
            buffer.position(slotBase)
            var writePos = slotBase.toLong()
            while (buffer.hasRemaining()) {
                writePos += ch.write(buffer, writePos)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "TERRADOCK bridge write failed: ${t.message}")
        } finally {
            buffer.position(0)
            buffer.limit(TerradockBridgeFormat.FILE_SIZE)
        }
    }

    /** 只用于诊断：已写帧数。 */
    val frameCount: Long
        get() = frames

    /**
     * 结束会话：清零两槽 → 关闭 → 删除文件（尽量），让 mod 立即回退。
     */
    fun close() {
        val ch = channel
        if (ch != null) {
            try {
                // 两槽清零：magic 消失 + CRC 全零不匹配 → reader 判定无效/过期
                TerradockBridgeFormat.zeroFill(buffer)
                buffer.position(0)
                buffer.limit(TerradockBridgeFormat.FILE_SIZE)
                var writePos = 0L
                while (buffer.hasRemaining()) {
                    writePos += ch.write(buffer, writePos)
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "TERRADOCK bridge close-zero failed: ${t.message}")
            }
        }
        AppLog.i(TAG, "TERRADOCK bridge closed after $frames frame(s)")
        closeInternal(deleteFile = true)
    }

    private fun closeInternal(deleteFile: Boolean) {
        runCatching { channel?.close() }
        runCatching { raf?.close() }
        channel = null
        raf = null
        if (deleteFile) {
            val f = file ?: return
            runCatching {
                if (f.exists() && !f.delete()) {
                    AppLog.w(TAG, "TERRADOCK bridge file not deleted: ${f.absolutePath}")
                }
            }
            runCatching {
                // 目录空了就顺手删掉（不影响用户其它数据）
                val dir = f.parentFile
                if (dir != null && dir.isDirectory && dir.list()?.isEmpty() == true) {
                    dir.delete()
                }
            }
        }
    }
}
