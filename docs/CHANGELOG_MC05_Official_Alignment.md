# CHANGELOG · MC05 官方协议对齐 + 图片接入（A/B/C 三合一）

> 日期：2026-10-06
> 目标：`com.cchip.desheng`（西伯利亚 MC05 官方 App）逆向 → 反哺 `MelodyPlus_ng` 模块
> 通道：npmcp（workspace 55490541）
> 产出版本：`MelodyPlus_official_v7.apk`（45.59 MB，`:app:assembleDebug` 通过）

---

## A. 协议对齐（帧格式 / 命令码 / 电量语义）

### A1. 新建 `XiberiaOfficialCodec.kt`
官方帧真源实现（此前模块用的是华为帧，耳机不应答）：
- `encode(cmd, payload, maxLen=0x0F)` —— 对应官方 `Protocol.packData(II[B)`
- `encodeWithCrc(cmd8, payload)` —— 对应 `packData(I[B)`（带 CRC）
- `crc16()` —— CRC16/Modbus（表 0xA001）
- `parseBattery()` —— 官方 6 字节三态 `[L.on][L.level][R.on][R.level][C.on][C.level]`
- `StreamFramer` —— 帧边界 `b[3]+8`，支持粘包

### A2. 重写 `XiberiaFrameCodec.kt`（换实现，保签名）
旧：`FF 03 00 len 01 08 cmdHi cmdLo 00 00 …`（**b[4]=0x01 错误，无 payloadLen**）
新：`FF 03 00 sz-8 FF 08 cmdHi cmdLo 08 plen …`（**b[4]=0xFF，b[8]=0x08，b[9]=plen**）
call site（RfcommClient / Session / FeatureBackend）零改动。

### A3. 重写 `XiberiaCommands.kt`（码表对齐官方 `CommandId`）
关键修正：
| 语义 | 旧码 | 新码（官方） |
|---|---|---|
| 电量查询 | `0x0804` | **`0x0A01`** |
| 电量应答 | — | **`0x0A11` / `0x0A12`** |
| 游戏/低延迟 | `0x0E11` | **`0x0C01`** |
| LDAC | `0x0E0B` | **`0x0E04`** |
| 双设备 | `0x0E27` | **`0x0E0B`** |
| 漏音抑制 | `0x0E0E` | **`0x0B01`**（NOISE_SET，语义近似） |

### A4. 重写 `XiberiaProtocolCodec.kt`
电量解析切到官方 6 字节三态；新增 `parseBatteryFull()`；`DeviceCaps` 位偏移标注待实测。

### A5. 联动修改
- `XiberiaFeatureBackend.queryBattery()` —— L/R/C 三态映射
- `XiberiaHeadsetAdapter.readBattery()` —— 同
- `XiberiaRfcommClient.sendCommand()` —— 电量应答接受 `0xA01→0xA11/0xA12`

---

## B. MC05 图片接入

### B1. 证伪假占位图
`assets/device_images/xiberia_mc05.png` 原 md5 = `e22cfa79…`，
与 `oppo_enco_x3.png` / `xiberia_mc01.png` **完全相同** → 是从 Enco X3 复制的假图。

### B2. 替换为真图（来自官方 APK 资源）
| 文件 | 来源 | 尺寸 | 大小 |
|---|---|---|---|
| `xiberia_mc05.png` | `dev_mc05_icon` | 2368×2160 | 1.83 MB |
| `xiberia_mc05_left.png` | `dev_mc05_left_icon` | 1913×1913 | 451 KB |
| `xiberia_mc05_right.png` | `dev_mc05_right_icon` | 1913×1913 | 462 KB |

### B3. `DeviceProfile.kt` 电池能力
`xiberiaProfile`：`supportsSingleBattery` true→**false**，
`supportsLeftRightBattery` false→**true**，`supportsCaseBattery` false→**true**
（官方电量含左/右/仓三态，详情面板左右耳电量 hook 已存在，直接生效）。

---

## C. 补充取证（枚举真值）

| 类 | 真值 |
|---|---|
| `DefaultSwitchCommand` | OFF.command=`0x00` / ON.command=`0x01`（充电态判定） |
| `DefaultSwitchResult` | OK.command=`0x01` / NO.command=`-0x01`（SWITCH 应答码） |

---

## 交付物

| 类型 | 路径 |
|---|---|
| APK | `/sdcard/Download/Operit/MelodyPlus_ng/MelodyPlus_official_v7.apk` |
| 文档 | `docs/REF_ChipDesheng_Protocol_And_Product.md`（命令表/型号表） |
| 文档 | `docs/REF_ChipDesheng_Frame_And_Codec.md`（帧/CRC/电量语义） |
| 文档 | `docs/CHANGELOG_MC05_Official_Alignment.md`（本文件） |
| 图片 | `docs/references/mc05/*.png` + `assets/device_images/xiberia_mc05*.png` |

## 安装 / 验证步骤

```bash
# 1) 安装（覆盖旧版）
adb install -r /sdcard/Download/Operit/MelodyPlus_ng/MelodyPlus_official_v7.apk

# 2) LSPosed 作用域勾选 melody（com.oplus.melody）+ 重启模块作用域（勿重启 zygote）
# 3) 连接 MC05，观察：
#    - 弹窗主图 = MC05 真图（不再空白/Enco X3）
#    - 详情面板左耳/右耳/仓 电量有值
#    - logcat 观察 TX/RX：发行应为 `ff 03 00 07 ff 08 0a 01 08 00 00 00 00 00`
#      RX 应答 cmd = 0xa11/0xa12
```

## 待实测校准项（下一轮）

1. `0x0807` 能力位图的位偏移（`parseGetOrSetSwitchMode` 语义）
2. `SERIAL(0x0E01)` / `WEAR_DETECT` / `SMART_PAUSE` 无官方对应，需真机抓包定
3. 2 参帧（带 CRC）适用场景 —— 控制/查询用 3 参无 CRC，数据帧才带 CRC
