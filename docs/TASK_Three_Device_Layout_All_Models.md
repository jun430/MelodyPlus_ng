# ⛔ [已废弃 2026-10-07] AI 执行指令：全型号「三图设备布局 + 三路电量 + 目录化图片资源」

> **⚠️ 本文所述「模块自绘三图容器（`ThreeDeviceLayout` + `BatteryBadgeView`）」方案已废弃。**
> 废弃原因：宿主 `MelodyDetailModelView` 用 Glide **异步**加载默认大图，晚到回调会覆盖模块自绘图。
> **替代方案**：直接给宿主原生 `normal_image` 槽位贴「单张主图（三合一图）」并停宿主渲染 —— 见
> [`CHANGELOG_PanelWrite_Battery_Image_Fix.md`](CHANGELOG_PanelWrite_Battery_Image_Fix.md) ④⑤⑥ 与
> [`MODULE_TECHNICAL_GUIDE.md`](MODULE_TECHNICAL_GUIDE.md) §2.9。
> 本文仅作历史归档，**请勿按此执行**。

---

# （历史）AI 执行指令：全型号「三图设备布局 + 三路电量 + 目录化图片资源」

> 用途：交给负责修改 `/root/MelodyPlus_ng` 的 AI 执行。
> 目标：把设备图片区域从「单张主图」升级为**全型号统一**的「左侧上下两只耳机 + 右侧耳机仓」，
> 并显示左耳 / 右耳 / 耳机仓三路独立电量；同时把图片资源与用户自定义图片统一为**目录化命名**。
> 本文是工程指令，逐项执行。

---

## 0. 设计原则（与旧版最大差异）

1. **全型号统一**：不再有 `isMc05Profile` / `MC05_*` 专用分支。所有 profile 一律走三图布局。
2. **资源目录化**：内置图片按 `<型号目录>/{main,left,right,case}.png` 存放，不再有 `xiberia_mc05_left.png` 这种平铺文件名。
3. **用户自定义图片目录化**：`Download/MelodyPlus/images/<profileId>/{main,left,right,case}.png`。
4. **回落链固定**：某槽位无图 → 回退同型号 `main.png`；主机型无分体图时三格显示同一张主图，属预期行为。
5. **协议层不动**：型号 → adapter → 协议的自动匹配逻辑保持原样（`DeviceProfiles.findByName` → `HeadsetSessionManager.createAdapter` → `AdapterRegistry.resolve`）。

---

## 1. 目标画面

```text
+---------------------------------------------+
|  左半区                         右半区       |
|  [左耳图片]                    [耳机仓图片]  |
|  左耳  xx%                    耳机仓  xx%   |
|  [右耳图片]                                  |
|  右耳  xx%                                  |
+---------------------------------------------+
```

硬性规则：

1. 左耳在上、右耳在下，同处左列；耳机仓在右列垂直居中。**不是三列横排。**
2. 三个独立 `ImageView`，分别加载 `left/right/case` 三张图（禁止 Canvas 拼图）。
3. 电量文字紧跟各自图片：`left → left`、`right → right`、`case → caseBattery`。
4. 缺失某路显示 `--`，禁止复制其它路伪造。
5. `single` 仅当 `left/right/caseBattery` **全为 null** 时作为左耳位回退。
6. 图片 `scaleType = FIT_CENTER`，禁止拉伸变形。

---

## 2. 资源命名规范（唯一真源：`DeviceImageAssets`）

### 2.1 内置 assets

```text
app/src/main/assets/device_images/<型号目录>/main.png    主图
app/src/main/assets/device_images/<型号目录>/left.png    左耳
app/src/main/assets/device_images/<型号目录>/right.png   右耳
app/src/main/assets/device_images/<型号目录>/case.png    耳机仓
```

`<型号目录>` 由 `DeviceProfile.id` 派生：非 `[a-z0-9]` 字符折叠为 `_`。

| profileId | 型号目录 |
|---|---|
| `xiberia.mc05` | `xiberia_mc05` |
| `sony.wf1000xm5` | `sony_wf1000xm5` |
| `oppo.encox3` | `oppo_enco_x3`（显式覆盖，历史目录名） |

### 2.2 用户自定义图片

```text
Download/MelodyPlus/images/<profileId>/{main,left,right,case}.png
```

### 2.3 加载优先级（`slotDrawable`）

```text
① Download/MelodyPlus/images/<profileId>/<slot>.png   （经 Provider 中转读取）
② assets/device_images/<型号目录>/<slot>.png
③ assets/device_images/<型号目录>/main.png            （兜底）
```

---

## 3. 关键代码落点（已完成，供核对）

| 文件 | 作用 |
|---|---|
| `bridge/DeviceImageAssets.kt` | **新增**：目录/文件名派生的唯一真源（`dirFor` / `assetPath` / `assetForId` / `SLOT_*`） |
| `bridge/DeviceProfile.kt` | `productImageAsset` 改为经 `DeviceImageAssets.assetPathForId(id, SLOT_MAIN)` 生成 |
| `bridge/DeviceImageStore.kt` | 用户自定义图片改为 `<profileId>/<slot>.png` 目录布局（`save/read/delete/listSlots`） |
| `bridge/DeviceRegistry.kt` | `get_device_image` 增加 `EXTRA_DEVICE_IMAGE_SLOT`（缺省 `main`） |
| `hook/ThreeDeviceLayout.kt` | 通用三图容器（原 `Mc05ThreeDeviceLayout` 重命名，去型号化） |
| `hook/MelodyPanelHook.kt` | `installOrUpdateThreeDeviceLayout(imageView, profile)`：全型号统一接入；`slotDrawable` 统一解析 |
| `core/HeadsetModels.kt` | `BatteryState` 新增 `leftCharging/rightCharging/caseCharging`（可空） |
| `adapter/xiberia/XiberiaFeatureBackend.kt` | `BatterySnapshot` 增加三路充电位 |
| `adapter/xiberia/XiberiaHeadsetAdapter.kt` | 协议 → `BatteryState` 贯通三路充电位 |
| `ui/pages/OverviewPage.kt` | 每型号四个槽位（主图/左耳/右耳/耳机仓）分别可选图/清除 |

---

## 4. 协议数据（保持不变，仅校验）

cchip 电量 payload（`0x0A11`/`0x0A12` 上报）语义：

```text
payload[0] 左耳充电标志    payload[1] 左耳电量
payload[2] 右耳充电标志    payload[3] 右耳电量
payload[4] 耳机仓充电标志  payload[5] 耳机仓电量
```

充电位语义：`== 0x01` 才算充电中（`0xff` = 不在充/未知）。
详见 `docs/REF_ChipDesheng_Frame_And_Codec.md`。

---

## 5. 禁止事项

1. 不得恢复 `isMc05Profile` 或任何型号专用分支。
2. 不得使用平铺文件名（`xiberia_mc05_left.png` 等）。
3. 不得三列横排 / Canvas 拼图 / 左右耳颠倒 / `single` 复制三路。
4. 不得每次刷新都新增容器（按 tag `melodyplus_three_device_layout` 复用）。
5. 不得用 zygote / system_server / 全局框架重启生效。

---

## 6. 验证

```bash
cd /root/MelodyPlus_ng
./gradlew :app:assembleDebug --offline
rg -n "installOrUpdateThreeDeviceLayout|THREE_DEVICE_LAYOUT_TAG|slotDrawable" app/src/main
rg -n "isMc05Profile|MC05_ASSET" app/src/main   # 期望：无输出
find app/src/main/assets/device_images -maxdepth 2 -type f
```

UI 检查：左耳在左列上方、右耳在左列下方、耳机仓右列居中、三图不重叠、三路电量独立且缺失显示 `--`。

生效方式：模块作用域热重载，或 `am force-stop <目标包>` 后重开。**禁止重启框架。**
