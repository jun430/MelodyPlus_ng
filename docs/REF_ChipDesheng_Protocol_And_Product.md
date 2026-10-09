# REF · cchip(XIBERIA) 官方 App 协议命令表 & 型号图鉴

> 文档类型：逆向参考（Reference）
> 目标 APK：`com.cchip.desheng`（西伯利亚/cchip 官方耳机管理 App，`base.apk`）
> 反编译通道：`npmcp`（workspace `55490541`，未落盘反编译，全程结构化读取）
> 取证时间：2026-10-06
> 关联：`REF_MelodyPlus_ng_Module.md`、`adapter/xiberia/XiberiaCommands.kt`、`adapter/xiberia/XiberiaProtocolCodec.kt`

---

## 0. 结论先行（3 条关键情报）

1. **官方命令表真源 = `Lcom/cchip/desheng/constant/CommandId;`，共 44 条命令码**（见 §1）。
   模块当前 `XiberiaCommands.kt` 的码表**大部分沿用华为兼容模块的码，与 cchip 官方对不上**（§4 给出逐条差异）。
2. **官方型号表真源 = `Lcom/cchip/desheng/constant/Product;`，共 18 个型号**，每个型号固定
   `(ordinal, productId, nameStr, nameId, imgId)`（见 §2）。MC05 = `productId 0x118`。
3. **MC05 图片 = 3 张真图**：主图 `dev_mc05_icon` + 左右耳 `dev_mc05_left_icon` / `dev_mc05_right_icon`（§3）。

---

## 1. 官方命令表 · `CommandId`（44 条，全量）

> 类：`Lcom/cchip/desheng/constant/CommandId;`（`.field public static final <NAME>:I = 0x...`）
> 辅助：`Lcom/cchip/desheng/constant/CommandConstant;` → `BASE_SEND_PACKET_SIZE = 0x0F`（基础发送包 15 字节）

### 1.1 电量 / 电量上报
| 常量 | 值 | 说明 |
|---|---|---|
| `BATTERY_INFO_GET` | `0x0A11` | 查询电量（主查询） |
| `BATTERY_INFO_REPORT` | `0x0A12` | 电量主动上报 |

### 1.2 音效 / EQ
| 常量 | 值 | 说明 |
|---|---|---|
| `EQ_ENABLE_SET` | `0x0801` | EQ 开关-写 |
| `EQ_ENABLE_GET` | `0x0802` | EQ 开关-读 |
| `EQ_MODE_SET` | `0x0803` | EQ 模式-写 |
| `EQ_MODE_GET` | `0x0804` | EQ 模式-读 |
| `EQ_CUSTOM_GAIN_SET` | `0x0806` | 自定义 EQ 增益-写 |
| `USER_ALL_EQ_GET` | `0x0807` | 用户全量 EQ-读 |
| `ALL_KEY_GET` | `0x0314` | 全按键/全配置查询 |
| `SOUND_EFFECT_SET` | `0x0E0D` | 音效模式-写 |
| `SOUND_EFFECT_GET` | `0x0E0E` | 音效模式-读 |
| `BASS_BOOST_SET` | `0x0E11` | 低音增强-写 |
| `BASS_BOOST_GET` | `0x0E12` | 低音增强-读 |
| `SPATIAL_SOUND_SWITCH_SET` | `0x0E0F` | 空间音频-写 |
| `SPATIAL_SOUND_SWITCH_GET` | `0x0E10` | 空间音频-读 |

### 1.3 降噪 / 通透
| 常量 | 值 | 说明 |
|---|---|---|
| `NOISE_SET` | `0x0B01` | 降噪模式-写 |
| `NOISE_GET` | `0x0B02` | 降噪模式-读 |
| `NOISE_REPORT` | `0x0B03` | 降噪模式-上报 |
| `NOISE_STYLE_SET` | `0x0E1F` | 降噪风格-写 |
| `NOISE_STYLE_GET` | `0x0E20` | 降噪风格-读 |
| `ANTI_WIND_NOISE_SET` | `0x0E21` | 抗风噪-写 |
| `ANTI_WIND_NOISE_GET` | `0x0E22` | 抗风噪-读 |

### 1.4 延迟 / 编码 / 双设备
| 常量 | 值 | 说明 |
|---|---|---|
| `LOW_DELAY_SET` | `0x0C01` | 低延迟/游戏模式-写 |
| `LOW_DELAY_GET` | `0x0C02` | 低延迟-读 |
| `LOW_DELAY_REPORT` | `0x0C03` | 低延迟-上报 |
| `LDAC_SET` | `0x0E04` | LDAC 开关-写 |
| `LDAC_GET` | `0x0E05` | LDAC 开关-读 |
| `LHDC_SET` | `0x0E13` | LHDC 开关-写 |
| `LHDC_GET` | `0x0E14` | LHDC 开关-读 |
| `DUAL_DEVICE_SET` | `0x0E0B` | 双设备连接-写 |
| `DUAL_DEVICE_GET` | `0x0E0C` | 双设备连接-读 |

### 1.5 操控 / 触控 / 语音
| 常量 | 值 | 说明 |
|---|---|---|
| `TOUCH_SET` | `0x0E17` | 触控功能-写 |
| `TOUCH_GET` | `0x0E18` | 触控功能-读 |
| `CHILD_MODE_SET` | `0x0E19` | 儿童模式-写 |
| `CHILD_MODE_GET` | `0x0E1A` | 儿童模式-读 |
| `OFFLINE_VOICE_SET` | `0x0E1B` | 离线语音-写 |
| `OFFLINE_VOICE_GET` | `0x0E1C` | 离线语音-读 |
| `VOLUME_GEAR_SET` | `0x0E26` | 音量档位-写 |
| `VOLUME_GEAR_GET` | `0x0E27` | 音量档位-读 |

### 1.6 系统 / 状态
| 常量 | 值 | 说明 |
|---|---|---|
| `VERSION_GET` | `0x0D01` | 固件版本查询（payload = ASCII） |
| `DONGLE_STATE_GET_OR_REPORT` | `0x0E25` | Dongle（接收器）状态 |

> **未在 CommandId 中出现的语义**：入耳/佩戴检测、智能暂停、序列号读取。
> 这几项在官方 App 中可能不走 `CommandId` 表（走独立上报/状态位），模块当前对应码为推断值，需实测校准。

---

## 2. 官方型号表 · `Product`（18 个，全量）

> 类：`Lcom/cchip/desheng/constant/Product;`（`abstract enum`），子类 `Product$<NAME>`
> 构造器签名：`Product(String name, int ordinal, int productId, String nameStr, int nameId, int imgId)`
> 映射查询 API：`Product$Companion.findProductById(Integer pid) → Product?`（按 `productId` 线性查找）

| # | 枚举名 | productId | nameStr（显示名） | nameId（str 资源） | imgId（mipmap 资源） |
|---|---|---|---|---|---|
| 0 | `DM03` | `0x113` | `DM03` | `R.string.DM03` | `dev_dm03_icon` |
| 1 | `MC20` | `0x112` | `MC20` | `R.string.dev_type_mc20` | `dev_mc20_icon` |
| 2 | `MC01_MAX` | `0x114` | `MC01 MAX` | `R.string.MC01_MAX` | `dev_mc01_max_icon_1` |
| 3 | `AS10_ANC` | `0x115` | `AS10` | `R.string.AS10_ANC` | `dev_as10_icon` |
| 4 | `MC03` | `0x117` | `MC03` | `R.string.MC03` | `dev_mc03_icon` |
| 5 | `W30` | `0x116` | `W30` | `R.string.W30` | `dev_w30_icon` |
| **6** | **`MC05`** | **`0x118`** | **`MC05`** | **`R.string.MC05`** | **`dev_mc05_icon`** |
| 7 | `DM02BA` | `0x101` | `DM02BA 标准版` | `R.string.DM02BA_B` | `ic_dm01_pro` |
| 8 | `DM02BA_TWO` | `0x102` | `DM02BA 双金标版` | `R.string.DM02BA_TWO` | `dev_dm0202_icon` |
| 9 | `DM25` | `0x103` | `DM25` | `R.string.DM25` | `dev_dm25_icon` |
| 10 | `AIR_FIT` | `0x104` | `Air Fit` | `R.string.AIR_FIT` | `dev_air_fit_icon` |
| 11 | `DM01_TWO` | `0x105` | `DM01双金标版` | `R.string.DM01_TWO` | `dev_dm0202_icon` |
| 12 | `DM01_MAX` | `0x106` | `DM01 MAX` | `R.string.DM01_MAX` | `ic_dm01_max` |
| 13 | `DM02` | `0x107` | `DM02 进阶版` | `R.string.DM02` | `dev_dm02_icon` |
| 14 | `AIR_CLIP` | `0x108` | `Air Clip` | `R.string.AIR_CLIP` | `dev_air_fit_icon` |
| 15 | `DM02_BASE` | `0x109` | `DM02 基础版` | `R.string.DM02_BASE` | `dm02` |
| 16 | `MC01` | `0x110` | `MC01` | `R.string.MC01` | `dev_mc01_icon` |
| 17 | `MC02` | `0x111` | `XIBERIA MC02` | `R.string.MC02` | `dev_mc02_icon` |

**productId 区间：`0x101`–`0x118`（连续无空洞）。**

### 2.1 需要特殊处理「左右耳图标」的型号
部分型号除主图标外还有左右耳分体图标（`getLeftAndRightImg()` / 独立 mipmap）：

| 型号 | 主图 | 左耳 | 右耳 |
|---|---|---|---|
| **MC05** | `dev_mc05_icon` | `dev_mc05_left_icon` | `dev_mc05_right_icon` |
| MC03 | `dev_mc03_icon` | `dev_mc03_left_icon` | `dev_mc03_right_icon` |
| MC20 | `dev_mc20_icon` | `dev_mc20_left_icon` | `dev_mc20_right_icon` |
| MC01_MAX | `dev_mc01_max_icon_1` | `mc01_max_left` | `mc01_max_right` |
| W30 | `dev_w30_icon` | `dev_w30_left_icon` | `dev_w30_right_icon` |
| AS10 | `dev_as10_icon` | `dev_as10_left_icon` | `dev_as10_right_icon` |

### 2.2 各型号能力接口（`Product` 抽象方法，用于功能门控）
`getChipType()` / `getEarType()` / `getCustomEqMode()` / `getServerVersionType()` /
`getLeftAndRightImg()` / `getPdfAssetFileNames()` / `getSoundEffectItems()` /
`isSupport()` / `isSupportAI()` / `isSupportBassBoost()` / `isSupportBatteryCase()` /
`isSupportDrainWater()` / `isSupportDualDevice()` / `isSupportHiRes()` / `isSupportLDAC()` /
`isSupportLowLatency()` / `isSupportQQMusicAudio()` / `isSupportShowDeviceDialog()` /
`isSupportTouchLockMode()` / `isSupportVolumeGear()` / `showBatteryType()` / `showCleanRemindPop()`

型号示例：
- `MC05`：`getChipType()=BT_MATE`、`getEarType()=OWS`、`getCustomEqMode()=GainMode.VALUE_12`
- `MC01`：`getChipType()=BT`、`isSupport()=false`
- `MC02`：`getEarType()=OWS`、`isSupport()=false`

---

## 3. MC05 图片资源（三张真图）

> 资源 ID（xxhdpi mipmap）：
> - `dev_mc05_icon` → `resource:0x7f0f002b`
> - `dev_mc05_left_icon` → `resource:0x7f0f002c`
> - `dev_mc05_right_icon` → `resource:0x7f0f002d`

**已导出实体文件**：`/sdcard/Download/Operit/earbud_research/mc05_images/`

| 文件 | 来源资源 | 尺寸 | 大小 |
|---|---|---|---|
| `mc05_main.png` | `dev_mc05_icon` | 2368 × 2160 RGBA | 1,833,090 B（≈1.75 MB） |
| `mc05_left.png` | `dev_mc05_left_icon` | 1913 × 1913 RGBA | 451,481 B |
| `mc05_right.png` | `dev_mc05_right_icon` | 1913 × 1913 RGBA | 462,026 B |

> 主图为「耳机整机 + 充电仓」构图，左右耳为单体真图（可用于分体电量展示位）。

---

## 4. 模块现有码表 vs 官方命令表（⚠️ 关键修正清单）

> 现状：`app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaCommands.kt`
> 该表注释自述「依据真机 Frida 抓包 + smali 逆向，置信度 ⚠️=推断」——同官方 `CommandId` 对照后，
> **多个语义的码值错误**，这是 MC05 电量/功能写入不生效的高概率根因之一。

| 模块语义 | 模块当前码 | 官方 CommandId | 官方码 | 判定 |
|---|---|---|---|---|
| 电量查询 | `BATTERY = 0x0804` | `BATTERY_INFO_GET` | `0x0A11` | ❌ **错**（0x0804 官方是 EQ_MODE_GET） |
| 固件版本 | `FW_VERSION = 0x0D01` | `VERSION_GET` | `0x0D01` | ✅ 对 |
| 提示音档位 | `TONE_LEVEL = 0x0E26` | `VOLUME_GEAR_SET` | `0x0E26` | ✅ 码对，语义应为「音量档位」 |
| 游戏/低延迟 | `GAME_MODE = 0x0E11` | `LOW_DELAY_SET` | `0x0C01` | ❌ **错**（0x0E11 官方是 BASS_BOOST_SET） |
| LDAC | `LDAC = 0x0E0B` | `LDAC_SET` | `0x0E04` | ❌ **错**（0x0E0B 官方是 DUAL_DEVICE_SET） |
| 双设备 | `TRI_STATE = 0x0E27` | `DUAL_DEVICE_SET` | `0x0E0B` | ❌ **错**（0x0E27 官方是 VOLUME_GEAR_GET） |
| EQ 预设查询 | `EQ_PRESET_QUERY = 0x0A11` | `BATTERY_INFO_GET` | `0x0A11` | ❌ **错**（撞电量查询码） |
| EQ 自定义 | `EQ_CUSTOM = 0x0314` | `ALL_KEY_GET` | `0x0314` | ❌ **错**（0x0314 官方是全配置查询） |
| 智能暂停 | `SMART_PAUSE = 0x0E0C` | `DUAL_DEVICE_GET` | `0x0E0C` | ❌ **错** |
| 佩戴检测 | `WEAR_DETECT = 0x0E04` | `LDAC_SET` | `0x0E04` | ❌ **冲突** |
| 周期状态查询 | `QUERY_STATUS = 0x0E05` | `LDAC_GET` | `0x0E05` | ❌ **冲突** |
| 漏音抑制 | `LEAK_SUPPRESS = 0x0E0E` | `SOUND_EFFECT_GET` | `0x0E0E` | ⚠️ 码撞「音效读」，语义需实测确认 |
| 开关 A | `SWITCH_A = 0x0C01` | `LOW_DELAY_SET` | `0x0C01` | ❌ 撞低延迟 |
| 开关 B | `SWITCH_B = 0x0C02` | `LOW_DELAY_GET` | `0x0C02` | ❌ 撞低延迟读 |
| 序列号 | `SERIAL = 0x0E01` | —（不在表内） | — | ⚠️ 需独立校准 |

### 4.1 修正建议（方向）
1. **电量必须以 `0x0A11` 为主查询**（`BATTERY_INFO_GET`），并订阅 `0x0A12` 主动上报；
   现有 `XiberiaProtocolCodec.parseBattery` 的「[02 01 <val>] / 0x0D 打包」样本需按 `0x0A11` 应答重新校准。
2. **布尔开关统一切到官方三元组 `SET(0x??) / GET(0x??) / REPORT`**：
   低延迟 `0x0C01/0x0C02/0x0C03`、LDAC `0x0E04/0x0E05`、双设备 `0x0E0B/0x0E0C`。
3. **避免「一码多义」**：0x0E0B 官方=双设备、0x0E11=低音增强、0x0E0E=音效读 —— 模块原表把它们
   当成 LDAC / 游戏模式 / 漏音抑制，属于语义错配。
4. MC05 官方定型：`productId=0x118`、主图 `dev_mc05_icon`、左右耳 `dev_mc05_*_icon`；
   若要做 melody 侧伪装，注意 melody 的 productId 与 cchip 的 `productId` **不是同一编码空间**
   （模块当前把两者混用，见 `AdapterRegistry.spoofIdHexFor`）。

---

## 5. 复现指令（npmcp）

```text
# 命令表
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/CommandId; workspaceId=55490541
# 型号枚举
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/Product; workspaceId=55490541
# 单型号真值（示例 MC05）
np_apk_read_text locator=dex_class:Lcom/cchip/desheng/constant/Product$MC05; workspaceId=55490541
# 型号→图标映射
np_apk_search target=smali query="R$mipmap" workspaceId=55490541
# 图标资源
np_apk_search target=resource_table_names query="dev_mc05" workspaceId=55490541
```
