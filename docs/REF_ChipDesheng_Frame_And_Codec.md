# REF · cchip(XIBERIA) 帧格式与电量协议（真机对齐版）

> 真源：`com.cchip.desheng`（workspace 55490541）· 真机：XIBERIA MC05（MAC `41:42:01:00:44:F9`）
> 本文结论全部来自 **官方 smali 逐字节还原 + 真机抓帧实证**，非推测。

---

## 1. 控制/查询帧格式（packData(II[B)）

官方 `Protocol.packData(int cmd, int maxLen, byte[] payload)` 逐条还原：

```
size = payload.size + 10
if (size <= maxLen) size = maxLen      // 短 payload 补零到 maxLen
buf[0] = 0xFF                          // 帧头
buf[1] = 0x03
buf[2] = 0x00
buf[3] = (size - 8) & 0xFF             // 长度域
buf[4] = 0x01                          // ⚠️ aput-byte v2(0x1) —— 不是 0xFF
buf[5] = 0x08
buf[6] = (cmd >> 8) & 0xFF             // 命令高位
buf[7] = cmd & 0xFF                    // 命令低位
buf[8] = 0x00                          // ⚠️ aput-byte v3(0x0) —— 不是 0x08
buf[9] = payload.size                  // 实际 payload 长度
buf[10..] = payload
```

**易错点（曾两次踩坑）**：
- `buf[4]` 是 **0x01**，不是 0xFF；
- `buf[8]` 是 **0x00**，不是 0x08；
- `buf[9]` 才是 payload 长度（旧实现错把它写成 0，导致耳机按长度 0 处理 → 无应答）。

### 电量查询帧（真机实证）
`readBattery(dev)` → `sendData(addr, 0xA11, 0x0F, empty)`，std maxLen=0x0F：

```
FF 03 00 07 01 08 0A 11 00 00 00 00 00 00 00     (15 字节)
```

---

## 2. 命令分发表（parseReceiveData）

`(b[6]<<8)|b[7]` 作为 key 走 sparse-switch。电量相关：

| 命令 | 处理函数 | 门槛 |
|---|---|---|
| `0x0A01` / `0x0A02` | `parseBattery(dev, bytes)`（2 参，旧路径） | 整帧 **≥ 0x0E(14)** |
| **`0x0A11` / `0x0A12`** | **`parseBatteryNew(dev, bytes)` + `parseBattery(dev,bytes,cmd)`（3 参）** | 整帧 **≥ 0x0C(12)** |

---

## 3. 电量应答解析（parseBatteryNew，整帧索引）

```
if (bytes.length < 0x0C) return;                 // 不足 12 字节直接丢
L充电 = (bytes[0x0A] == 0x01)                    // ⚠️ 只在 ==0x01 时算充电
L电量 =  bytes[0x0B] & 0xFF
if (bytes.length >= 0x0E) { R充电=bytes[0x0C]==0x01; R电量=bytes[0x0D]&0xFF }
if (bytes.length >= 0x10) { C充电=bytes[0x0E]==0x01; C电量=bytes[0x0F]&0xFF }
```

即 **payload[0..5] = L.on / L.level / R.on / R.level / C.on / C.level**（payload 起于整帧 b[10]）。

---

## 4. 真机抓帧实证

### 4.1 正确链路（0xA11）✅
```
TX  ff 03 00 07 01 08 0a 11 00 00 00 00 00 00 00     (15B)
RX  ff 03 00 08 01 08 0a 11 02 06 00 64 00 64 ff 39  (16B)
                                  └── payload(6B) ──┘
```
解析：

| 字段 | 字节 | 值 | 含义 |
|---|---|---|---|
| L.on | frame[10] | 0x00 | 左耳未充电 |
| L.level | frame[11] | 0x64 | **左耳 100%** |
| R.on | frame[12] | 0x00 | 右耳未充电 |
| R.level | frame[13] | 0x64 | **右耳 100%** |
| C.on | frame[14] | 0xff | 仓未充电（0xff=未知/不在充） |
| C.level | frame[15] | 0x39 | **仓 57%** |

### 4.2 错误链路（0xA01）❌
```
TX  ff 03 00 07 01 08 0a 01 00 00 00 00 00 00 00
RX  ff 03 00 03 01 08 0a 01 02 01 01              (11B, payload 仅 1 字节)
```
- 只回 1 字节 payload（11 字节整帧），**不是电量数据**；
- 官方 `parseBattery` 要求 ≥14 字节 → 会被静默丢弃。

**结论：电量查询必须用 `0xA11`（`0xA01` 是无效/保留码）。**

---

## 5. 流量帧解析定义（parseReceiveData 粘包）

```
if (bytes.length < 10) return;                                   // 太短
if (bytes[0]==0xFF && bytes[1]==0x03 && bytes[2]==0x00) {
    if (bytes[3] < bytes.length - 8) {                           // 粘包
        copy(bytes, 0, tmp, 0, bytes[3]+8); parseReceiveData(dev, tmp);
        tail = bytes[bytes[3]+8..]; parseReceiveData(dev, tail); return;
    }
    cmd = (bytes[6]<<8)|bytes[7];  dispatch(cmd);
}
```
即帧边界 = `b[3] + 8`，与本文 §1 的长度域自洽。

---

## 6. 模块内已固化要点

- `XiberiaOfficialCodec.encode()`：b[4]=0x01、b[8]=0x00、b[9]=payload.size；
- `XiberiaOfficialCodec.parseBattery()`：充电位判定 `== 0x01`（不是 `!= 0`，避免 0xFF 误判）；
- `XiberiaCommands.BATTERY_QUERY_CHAIN`：**0xA11 置首**，0xA01 作退化兜底；
- `XiberiaRfcommClient.queryBatteryPayload()`：按链探测，取首个 payload ≥ 6 字节的应答。

---

## 7. 待办

- [ ] 耳机重连后做「RFCOMM → 弹窗电量」端到端验证（当前耳机 `NotConnected`，未触发查询）；
- [ ] 确认 `:fg` 进程弹窗能否拿到 `XiberiaFeatureBackend` 的 batteryCache（跨进程边界问题）。