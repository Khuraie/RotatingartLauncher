package com.app.ralaunch.feature.terradock

import android.content.Context
import com.app.ralaunch.core.common.SettingsAccess
import com.app.ralaunch.core.logging.AppLog
import com.app.ralaunch.feature.controls.ControlData
import com.app.ralaunch.feature.controls.packs.ControlLayout
import com.app.ralaunch.feature.controls.packs.ControlPackInfo
import com.app.ralaunch.feature.controls.packs.ControlPackManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * TERRADOCK 控制包（P0/P1）：
 *  `assets/terradock/control-pack.json`（由 `tools/terradock/gen_control_pack.py` 从
 *  `C:\Tmobile\terradock\control-pack-ref.json` 生成，参考画布 2400×1080）
 *  → RAL 原生 [ControlLayout]（`ControlData` 归一化 0..1，复用 RAL 控件/编辑器/桥接）。
 *
 * 坐标映射（与 mod 侧完全一致，Lead 2026-11 约定）：
 *  - 每个元素九宫格锚定：TL/TC/TR、CL/C/C/R、BL/BC/BR（参考坐标下 anchorX ∈ {0,1200,2400}，
 *    anchorY ∈ {0,540,1080}）。生成器规则：X 取最近锚点（等距取较小值），Y 同理，
 *    但右上系统按钮列整体按列顶元素锚定（map/bag/gear/pause = TR）。
 *  - `u = surfaceHeightPx / 1080`
 *  - `pos_px = anchorPointPx + (cx - anchorX_ref, cy - anchorY_ref) * u`，尺寸同样 * u。
 *  - 存储形态仍是 RAL 的归一化值：`x = (centerX - sizePx/2) / surfaceW`，
 *    `y = (centerY - sizePx/2) / surfaceH`，`w = h = sizePx / surfaceH`
 *    （RAL 的宽高以屏高为基准，见 ControlLayout.xToPx/widthToPx）。
 *
 * 只有当用户还没配置过控件包时才把它设为默认；设置项 `terradockControlPackEnabled` 可关闭。
 */
object TerradockControlPack {

    private const val TAG = "TerradockControlPack"

    /** 安装后的控件包 ID（ControlPacks/installed/<PACK_ID>/） */
    const val PACK_ID = "terradock_pack"

    /** 内置资源路径 */
    const val ASSET_PATH = "terradock/control-pack.json"

    /** 内置资源目录（模板包与主包同目录） */
    const val ASSET_DIR = "terradock"

    /** 从手机版导入的模板包前缀：control-pack-tpl-<style>.json */
    const val TEMPLATE_PREFIX = "control-pack-tpl-"

    /** 参考画布（与 control-pack-ref.json 一致） */
    const val REF_W = 2400f
    const val REF_H = 1080f

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ===================== 资源 JSON 模型 =====================

    @Serializable
    data class PackAsset(
        val format: String = "terradock-control-pack",
        val version: Int = 2,
        val name: String = "Terradock touch v2",
        val reference: Reference = Reference(),
        val sticks: List<StickSpec> = emptyList(),
        val buttons: List<ButtonSpec> = emptyList(),
        val hotbar: HotbarSpec? = null,
        /** 分组/轮盘控件（Terraria 手机版 VirtualControlsGroupConfiguration_Layout） */
        val groups: List<GroupSpec> = emptyList(),
        /** 手机版设置默认值（GUISettingID → 值），见 terradock/control-system/mobile-settings.json */
        val settings: Map<String, String> = emptyMap(),
        /** 手机版控制模板清单（Default/Classic/Xenopunk/Roboarms/Nescre/AngryHippo/Custom） */
        val templates: List<TemplateSpec> = emptyList()
    )

    @Serializable
    data class TemplateSpec(val id: String = "", val label: String = "")

    @Serializable
    data class GroupSpec(
        val id: String = "",
        /** 中心动作（桥接动作名，如 FIRE/EMOTE） */
        val action: String = "",
        val anchor: String = "BC",
        val cx: Float = 0f,
        val cy: Float = 0f,
        val ox: Float = 0f,
        val oy: Float = 0f,
        /** 中心按钮直径（参考画布 px） */
        val centre: Float = 96f,
        val sectors: List<SectorSpec> = emptyList()
    )

    @Serializable
    data class SectorSpec(
        val action: String = "",
        val keycode: String = "UNKNOWN",
        val label: String = ""
    )

    @Serializable
    data class Reference(val w: Float = 2400f, val h: Float = 1080f)

    @Serializable
    data class StickSpec(
        val id: String = "",
        /** TERRADOCK 摇杆角色：`move`（→WASD）或 `aim`（→相对鼠标 + 自动开火） */
        val mode: String = "",
        /** 九宫格锚点（由生成器写入，如 "BL"/"BR"） */
        val anchor: String = "BL",
        val cx: Float = 0f,
        val cy: Float = 0f,
        /** 参考画布中的摇杆半径（px） */
        val radius: Float = 150f,
        val deadzone: Float = 0.12f
    )

    @Serializable
    data class ButtonSpec(
        val id: String = "",
        val action: String = "",
        val anchor: String = "BL",
        val cx: Float = 0f,
        val cy: Float = 0f,
        /** 参考画布中的按钮直径（px） */
        val size: Float = 100f,
        /** ControlData.KeyCode 枚举名（如 MOUSE_LEFT / KEYBOARD_SPACE） */
        val keycode: String = "UNKNOWN",
        val label: String = "",
        /** true = 只上报桥接动作位、不转发桌面输入（缩放/锁定等 launcher 侧动作） */
        val bridgeOnly: Boolean = false
    )

    @Serializable
    data class HotbarSpec(
        val anchor: String = "TL",
        val x: Float = 12f,
        val y: Float = 12f,
        val cols: Int = 6,
        val rows: Int = 2,
        val cell: Float = 150f,
        val gap: Float = 8f,
        /** 每个格子合成的按键枚举名；空串 = 不合成（该格不创建控件） */
        val keys: List<String> = emptyList(),
        /** 可选：槽位图标/数量（mod 侧绘制用，launcher 忽略） */
        @SerialName("slots") val slotsHint: List<Slot>? = null
    )

    @Serializable
    data class Slot(val i: Int = 0, val icon: String = "", val count: Int = 0, val selected: Boolean = false)

    // ===================== 资源加载 =====================

    fun loadAsset(context: Context, path: String = ASSET_PATH): PackAsset? = try {
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        json.decodeFromString<PackAsset>(text).also {
            AppLog.i(TAG, "Loaded $path v${it.version} (${it.buttons.size} buttons, ${it.sticks.size} sticks, ${it.groups.size} groups)")
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "Failed to load $ASSET_PATH: ${t.message}")
        null
    }

    // ===================== 默认控件包安装 =====================

    /**
     * 把内置控制包安装/更新到 `ControlPacks/installed/terradock_pack/`，
     * 并在用户尚未配置过控件包时设为当前包。
     *
     * @return 是否让它成为当前布局
     */
    fun ensureInstalledAsDefault(context: Context, manager: ControlPackManager): Boolean {
        val enabled = runCatching { SettingsAccess.isTerradockControlPackEnabled }.getOrDefault(true)
        val selected = runCatching { manager.getSelectedPackId() }.getOrNull()

        if (!enabled) {
            if (selected == PACK_ID) {
                // 关闭时把选择交还给其它包（没有就留空，由 RAL 的正常逻辑处理）
                val other = manager.listPackIds().firstOrNull { it != PACK_ID }
                manager.setSelectedPackId(other)
                AppLog.i(TAG, "Terradock control pack disabled; selection moved to ${other ?: "<none>"}")
            }
            return false
        }

        val asset = loadAsset(context) ?: return false
        val installed = manager.getPackInfo(PACK_ID)
        if (installed == null || installed.versionCode != asset.version) {
            val ok = install(context, manager, asset)
            if (!ok) return false
        }

        val userConfigured = runCatching { manager.hasUserSelectedPack() }.getOrDefault(false)
        if (!userConfigured || selected == PACK_ID) {
            manager.setSelectedPackId(PACK_ID)
            AppLog.i(TAG, "Terradock control pack is the active default (userConfigured=$userConfigured)")
            installTemplates(context, manager)
            return true
        }
        installTemplates(context, manager)
        AppLog.i(TAG, "User already configured control pack '$selected'; leaving it untouched")
        return false
    }

    private fun install(
        context: Context,
        manager: ControlPackManager,
        asset: PackAsset,
        packId: String = PACK_ID
    ): Boolean {
        return try {
            val layout = buildLayout(context, asset, packId = packId)
            manager.savePackLayout(packId, layout)
            // 覆盖 manifest 的版本/名称信息（savePackLayout 只写 name/updatedAt）
            val manifest = File(File(manager.packsDir, packId), ControlPackInfo.MANIFEST_FILE_NAME)
            val info = ControlPackInfo(
                id = packId,
                name = asset.name,
                author = "TERRADOCK",
                version = "1.0.${asset.version}",
                versionCode = asset.version,
                description = if (packId == PACK_ID)
                    "TERRADOCK touch controls generated from control-pack-ref.json (2400x1080 reference canvas)"
                else
                    "TERRADOCK mobile touch-control template '${asset.name}' (imported from Terraria mobile)"
            )
            info.saveTo(manifest)
            AppLog.i(TAG, "Installed Terradock control pack '$packId' v${asset.version} -> ${manifest.absolutePath}")
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "Failed to install Terradock control pack '$packId': ${t.message}")
            false
        }
    }

    /**
     * 安装从手机版导入的控件模板（assets/terradock/control-pack-tpl-*.json）。
     * 每个硬件样式一个包，作为普通控件包出现在 RAL 的控件包列表里供用户选择；
     * 不改变当前选择。
     */
    private fun installTemplates(context: Context, manager: ControlPackManager) {
        val files = runCatching { context.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
            .filter { it.startsWith(TEMPLATE_PREFIX) && it.endsWith(".json") }
            .sorted()
        files.forEach { file ->
            val suffix = file.removePrefix(TEMPLATE_PREFIX).removeSuffix(".json")
            if (suffix.isEmpty()) return@forEach
            val packId = "${PACK_ID}_$suffix"
            val asset = loadAsset(context, "$ASSET_DIR/$file") ?: return@forEach
            val installed = manager.getPackInfo(packId)
            if (installed == null || installed.versionCode != asset.version) {
                install(context, manager, asset, packId)
            }
        }
        AppLog.i(TAG, "Terradock templates available: ${files.size}")
    }

    // ===================== 参考坐标 → RAL 控件 =====================

    /**
     * 生成 RAL 原生布局。[surfaceW]/[surfaceH] 默认取当前显示尺寸（与 ControlLayout 一致）。
     */
    fun buildLayout(
        context: Context,
        asset: PackAsset,
        surfaceW: Float = context.resources.displayMetrics.widthPixels.toFloat(),
        surfaceH: Float = context.resources.displayMetrics.heightPixels.toFloat(),
        packId: String = PACK_ID
    ): ControlLayout {
        val layout = ControlLayout(name = asset.name)
        layout.id = packId
        val controls = mutableListOf<ControlData>()
        val u = surfaceH / (asset.reference.h.takeIf { it > 0f } ?: REF_H)

        asset.sticks.forEach { spec ->
            if (spec.mode.isEmpty()) return@forEach
            // 摇杆直径 = 2 * radius（参考单位）* u
            val sizePx = spec.radius * 2f * u
            val (cx, cy) = mapPoint(spec.anchor, spec.cx, spec.cy, surfaceW, surfaceH, u, asset.reference)
            val joystick = ControlData.Joystick().apply {
                name = spec.id
                this.x = (cx - sizePx / 2f) / surfaceW
                this.y = (cy - sizePx / 2f) / surfaceH
                this.width = sizePx / surfaceH
                this.height = sizePx / surfaceH
                this.mode = ControlData.Joystick.Mode.KEYBOARD // 合成由 Terradock 接管
                this.terradockStick = spec.mode
                this.stickKnobSize = 0.4f
                this.stickOpacity = 0.7f
            }
            controls.add(joystick)
        }

        asset.buttons.forEach { spec ->
            val keycode = runCatching { ControlData.KeyCode.valueOf(spec.keycode) }
                .getOrElse { ControlData.KeyCode.UNKNOWN }
            if (keycode == ControlData.KeyCode.UNKNOWN) {
                AppLog.w(TAG, "Skip button '${spec.id}': unknown keycode '${spec.keycode}'")
                return@forEach
            }
            val sizePx = spec.size * u
            val (cx, cy) = mapPoint(spec.anchor, spec.cx, spec.cy, surfaceW, surfaceH, u, asset.reference)
            val button = ControlData.Button().apply {
                name = spec.label.ifEmpty { spec.id }
                this.x = (cx - sizePx / 2f) / surfaceW
                this.y = (cy - sizePx / 2f) / surfaceH
                this.width = sizePx / surfaceH
                this.height = sizePx / surfaceH
                this.shape = ControlData.Button.Shape.CIRCLE
                this.mode = ControlData.Button.Mode.KEYBOARD
                this.keycode = keycode
                this.opacity = 0.25f
                this.borderOpacity = 0.45f
                this.textOpacity = 0.9f
                this.terradockAction = spec.action
                this.terradockBridgeOnly = spec.bridgeOnly
            }
            controls.add(button)
        }

        // 快捷栏：tap → 数字键 1..9,0（Lead 约定：快捷栏格子不占桥接字段，只合成数字键）
        asset.hotbar?.let { hotbar ->
            val cellW = hotbar.cell * u
            val gap = hotbar.gap * u
            val count = (hotbar.cols * hotbar.rows)
            for (i in 0 until count) {
                val keyName = hotbar.keys.getOrNull(i) ?: ""
                if (keyName.isEmpty()) continue
                val keycode = runCatching { ControlData.KeyCode.valueOf(keyName) }
                    .getOrElse { ControlData.KeyCode.UNKNOWN }
                if (keycode == ControlData.KeyCode.UNKNOWN) continue
                val col = i % hotbar.cols
                val row = i / hotbar.cols
                // 格子左上角在参考坐标下：x + col*(cell+gap), y + row*(cell+gap)，再走九宫格锚点换算
                val refLeft = hotbar.x + col * (hotbar.cell + hotbar.gap)
                val refTop = hotbar.y + row * (hotbar.cell + hotbar.gap)
                val (cx, cy) = mapPoint(hotbar.anchor, refLeft + hotbar.cell / 2f, refTop + hotbar.cell / 2f, surfaceW, surfaceH, u, asset.reference)
                val button = ControlData.Button().apply {
                    // 不可见热区：文字空 + 全透明（borderOpacity 留 0.02 防止旧格式迁移逻辑误判）
                    name = ""
                    this.x = (cx - cellW / 2f) / surfaceW
                    this.y = (cy - cellW / 2f) / surfaceH
                    this.width = cellW / surfaceH
                    this.height = cellW / surfaceH
                    this.shape = ControlData.Button.Shape.CIRCLE
                    this.keycode = keycode
                    this.opacity = 0f
                    this.borderOpacity = 0.02f
                    this.textOpacity = 0f
                    this.terradockAction = "HOTBAR"
                }
                controls.add(button)
            }
        }

        // 分组/轮盘控件：手机版 VirtualControlsGroupConfiguration_Layout（中心动作 + 最多 4 个外圈槽）
        asset.groups.forEach { spec ->
            if (spec.sectors.isEmpty()) return@forEach
            val diameterPx = spec.centre * 3f * u          // 展开后的轮盘直径（参考画布单位）
            val (cx, cy) = mapPoint(spec.anchor, spec.cx, spec.cy, surfaceW, surfaceH, u, asset.reference)
            val radial = ControlData.RadialMenu().apply {
                name = spec.id
                this.x = (cx - diameterPx / 2f) / surfaceW
                this.y = (cy - diameterPx / 2f) / surfaceH
                this.width = diameterPx / surfaceH
                this.height = diameterPx / surfaceH
                this.sectorCount = spec.sectors.size
                this.sectors = spec.sectors.map { s ->
                    ControlData.RadialMenu.Sector().apply {
                        keycode = runCatching { ControlData.KeyCode.valueOf(s.keycode) }
                            .getOrElse { ControlData.KeyCode.UNKNOWN }
                        label = s.label
                    }
                }.toMutableList()
            }
            controls.add(radial)
            AppLog.i(TAG, "Added group '${spec.id}': ${spec.sectors.size} sectors (centre=${spec.action})")
        }

        layout.controls = controls
        AppLog.i(TAG, "Built Terradock layout '${asset.name}': ${controls.size} controls @ ${surfaceW.toInt()}x${surfaceH.toInt()} (u=$u)")
        return layout
    }

    /**
     * 九宫格锚点映射：`pos_px = anchorPointPx + (ref - anchorRef) * u`。
     * 返回参考点换算后的**中心**屏幕坐标（px）。
     */
    fun mapPoint(
        anchor: String,
        refX: Float,
        refY: Float,
        surfaceW: Float,
        surfaceH: Float,
        u: Float,
        reference: Reference = Reference()
    ): Pair<Float, Float> {
        val refW = reference.w.takeIf { it > 0f } ?: REF_W
        val refH = reference.h.takeIf { it > 0f } ?: REF_H
        val a = anchor.uppercase()
        val anchorPxX = when {
            a.endsWith("L") -> 0f
            a.endsWith("R") -> surfaceW
            else -> surfaceW / 2f
        }
        val anchorPxY = when {
            a.startsWith("T") -> 0f
            a.startsWith("B") -> surfaceH
            else -> surfaceH / 2f
        }
        val anchorRefX = when {
            a.endsWith("L") -> 0f
            a.endsWith("R") -> refW
            else -> refW / 2f
        }
        val anchorRefY = when {
            a.startsWith("T") -> 0f
            a.startsWith("B") -> refH
            else -> refH / 2f
        }
        return Pair(
            anchorPxX + (refX - anchorRefX) * u,
            anchorPxY + (refY - anchorRefY) * u
        )
    }
}
