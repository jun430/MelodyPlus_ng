// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaProtocolCodec.kt
// 说明: cchip(XIBERIA) payload 解析子集（供 XiberiaFeatureBackend 编排使用）。
//
// 本文件已按**官方 App 真值**重写（旧版是从华为兼容模块移植的语义，与 cchip 不符）：
//   - 电量    : Protocol.parseBattery(dev,bytes,cmd) → payload 起于 b[10]，6 字节三态
//               [L.on][L.level][R.on][R.level][C.on][C.level]
//   - 能力位图: 官方 0x0807 走 parseGetOrSetSwitchMode，具体位偏移待实测校准（见 DeviceCaps 注释）
//   - EQ      : parseUserCustomEq / parseEqGet（10 段），见 parseEqualizer
//
// 详见 docs/REF_ChipDesheng_Frame_And_Codec.md
package com.melody.melodyplus.adapter.xiberia

object XiberiaProtocolCodec {

    /** 设备能力快照（来源：0x0807 的开关位图；位偏移按官方 parseGetOrSetSwitchMode 待校准）。 */
    data class DeviceCaps(
        val switchA: Boolean = false,
        val switchB: Boolean = false,
        val gameMode: Boolean = false,
        val ldac: Boolean = false,
        val toneLevel: Int = 0,
        val raw: ByteArray = ByteArray(0),
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = raw.contentHashCode()
    }

    /**
     * 解析电量应答 payload（官方 6 字节三态）。
     *
     * 返回 `[leftLevel, rightLevel, caseLevel]`（对应官方 BatteryData 的 L/R/C 电量字段）；
     * 长度不足 6 时按可读部分回退，仍不足返回空列表。
     *
     * ⚠️ 旧版返回 `[02 01 <val>]` 单值语义，已废弃（见 REF 文档 §3）。
     */
    fun parseBattery(payload: ByteArray): List<Int> {
        if (payload.isEmpty()) return emptyList()
        val full = XiberiaOfficialCodec.parseBattery(payload)
        if (full != null) {
            return listOf(full.leftLevel, full.rightLevel, full.caseLevel)
        }
        // 退化路径：老固件可能只回单耳电量
        return listOf(payload[0].u8())
    }

    /** 官方完整电量（含充电态）；长度不足返回 null。 */
    fun parseBatteryFull(payload: ByteArray): XiberiaOfficialCodec.OfficialBattery? =
        XiberiaOfficialCodec.parseBattery(payload)

    /**
     * 解析能力/开关位图（0x0807）。
     * 官方走 `parseGetOrSetSwitchMode(dev,bytes,cmd)`，具体位偏移需装机实测校准，
     * 此处保留旧结构以便 UI 有值可显（置位语义待验证）。
     */
    fun parseDeviceCaps(payload: ByteArray): DeviceCaps {
        if (payload.size < 3) return DeviceCaps(raw = payload)
        val byte0 = payload[0].u8()
        return DeviceCaps(
            switchA = (byte0 and 0x01) != 0,
            switchB = (byte0 and 0x02) != 0,
            gameMode = payload.getOrNull(1)?.u8() == 0x01,
            ldac = payload.getOrNull(2)?.u8() == 0x01,
            toneLevel = payload.getOrNull(3)?.u8() ?: 0,
            raw = payload,
        )
    }

    /** 解析 EQ 预设组（10 段），对应 parseUserCustomEq / parseEqGet。 */
    fun parseEqualizer(payload: ByteArray): IntArray =
        IntArray(payload.size) { payload[it].u8() }

    /** 官方帧头判定（0xFF 03 00）。 */
    fun command(frame: ByteArray, offset: Int, length: Int): Boolean {
        if (frame.size < offset + length || offset < 0) return false
        return XiberiaOfficialCodec.isFrame(frame.copyOfRange(offset, offset + length))
    }

    private fun Byte.u8(): Int = this.toInt() and 0xFF
}