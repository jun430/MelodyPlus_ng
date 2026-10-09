// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaOfficialCodec.kt
// 说明: cchip(XIBERIA) 官方 App 帧编解码（真机对齐版）。
//
// 真源（workspace 55490541，com.cchip.desheng）：
//   - 组帧：Lcom/cchip/desheng/main/utils/Protocol;->packData(II[B)[B   （控制/查询帧）
//           Lcom/cchip/desheng/main/utils/Protocol;->packData(I[B)[B     （数据帧，带 CRC16）
//           Lcom/cchip/desheng/main/utils/Protocol;->sendData(Ljava/lang/String;II[B)[B
//   - CRC ：Lcom/cchip/desheng/main/utils/BlockUtils;->crc16Check([B)I  （Modbus 表 0x'A001）
//   - 电量：Lcom/cchip/desheng/main/utils/Protocol;->readBattery(...) / parseBattery(...)
//
// 与旧实现 XiberiaFrameCodec 的差异（旧实现 b[9] 长度位错，故耳机按长度 0 处理）：
//   旧: FF 03 00 len 01 08 cmdHi cmdLo 00 00 [payload]
//   新: FF 03 00 sz-8 01 08 cmdHi cmdLo 00 plen [payload ...补零到 maxLen]
//   ⚠️ b[4]=0x01、b[8]=0x00 —— 来自官方 packData smali 的 aput-byte 逐条还原，禁止改动。
//
// 详见 docs/REF_ChipDesheng_Frame_And_Codec.md
package com.melody.melodyplus.adapter.xiberia

object XiberiaOfficialCodec {

    // ---------- 帧常量 ----------
    const val HEADER: Int = 0xFF
    const val FIXED_B1: Int = 0x03
    const val FIXED_B2: Int = 0x00
    /** b[4]：官方 smali `aput-byte v2(0x1), out, 4` —— 是 0x01。 */
    const val FIXED_B4: Int = 0x01
    const val FIXED_B5: Int = 0x08
    /** b[8]：官方 smali `aput-byte v3(0x0), out, 8` —— 是 0x00。 */
    const val FIXED_B8: Int = 0x00

    const val CMD_HI: Int = 6
    const val CMD_LO: Int = 7
    const val LEN_FIELD: Int = 3          // = size - 8
    const val PAYLOAD_LEN_IDX: Int = 9
    const val PAYLOAD: Int = 10

    /** 官方基础发送包尺寸（CommandConstant.BASE_SEND_PACKET_SIZE），readBattery 传 0x0F。 */
    const val BASE_SEND_PACKET_SIZE: Int = 0x0F

    // ---------- 电量查询 ----------
    /**
     * 官方电量查询命令码。真源：`Protocol.readBattery()` → `sendData(addr, 0xA01, 0x0F, empty)`。
     * 注意：这是**查询**码；`0x0A11/0x0A12` 是电量**应答/上报**码。
     */
    const val CMD_BATTERY_QUERY: Int = 0x0A01
    /** 电量应答/上报码（parseReceiveData 里 0xA11/0xA12 均落到 parseBattery）。 */
    const val CMD_BATTERY_REPORT: Int = 0x0A11
    const val CMD_BATTERY_REPORT_ALT: Int = 0x0A12

    // ---------- 组帧 ----------

    /**
     * 官方控制/查询帧（对应 `packData(II[B)`）。
     *
     * 等价 Kotlin（真源 smali 逐条还原）：
     * ```
     * size = max(payload.size + 10, maxLen)
     * buf[0]=0xFF; buf[1]=0x03; buf[2]=0x00; buf[3]=(size-8)&0xFF
     * buf[4]=0xFF; buf[5]=0x08; buf[6]=(cmd>>8)&0xFF; buf[7]=cmd&0xFF
     * buf[8]=0x08; buf[9]=payload.size; buf[10..]=payload
     * ```
     * @param maxLen 官方 `BASE_SEND_PACKET_SIZE`=0x0F；短 payload 会被补零到该长度。
     */
    fun encode(
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        maxLen: Int = BASE_SEND_PACKET_SIZE,
    ): ByteArray {
        var size = payload.size + 10
        if (size < maxLen) size = maxLen
        val out = ByteArray(size)
        out[0] = HEADER.toByte()
        out[1] = FIXED_B1.toByte()
        out[2] = FIXED_B2.toByte()
        out[3] = ((size - 8) and 0xFF).toByte()
        out[4] = FIXED_B4.toByte()
        out[5] = FIXED_B5.toByte()
        out[CMD_HI] = ((cmd shr 8) and 0xFF).toByte()
        out[CMD_LO] = (cmd and 0xFF).toByte()
        out[8] = FIXED_B8.toByte()
        out[PAYLOAD_LEN_IDX] = payload.size.toByte()
        if (payload.isNotEmpty()) payload.copyInto(out, PAYLOAD)
        return out
    }

    /**
     * 官方数据帧（对应 `packData(I[B)`，带 CRC16/Modbus 小端尾）。
     * `FF <cmd8> <len-2> [payload] <crcLo> <crcHi>`
     */
    fun encodeWithCrc(cmd8: Int, payload: ByteArray): ByteArray {
        val size = payload.size + 5
        val out = ByteArray(size)
        out[0] = HEADER.toByte()
        out[1] = (cmd8 and 0xFF).toByte()
        out[2] = ((size - 2) and 0xFF).toByte()
        if (payload.isNotEmpty()) payload.copyInto(out, 3)
        val crc = crc16(out)
        out[size - 2] = (crc and 0xFF).toByte()
        out[size - 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    // ---------- 解析 ----------

    /** 命令码 = (b[6]<<8)|b[7]（与旧 codec 同位置）。 */
    fun keyOf(frame: ByteArray): Int =
        ((frame[CMD_HI].u8() shl 8) or frame[CMD_LO].u8())

    /** 官方帧头判定：0xFF 03 00。 */
    fun isFrame(frame: ByteArray): Boolean =
        frame.size > PAYLOAD && frame[0].u8() == HEADER &&
            frame[1].u8() == FIXED_B1 && frame[2].u8() == FIXED_B2

    /**
     * 取 payload。官方在 b[9] 放 payload 长度；若长度域无效则退回「到缓冲区末尾」。
     * （真机应答可能带补零，故以长度域为准。）
     */
    fun payloadOf(frame: ByteArray): ByteArray {
        if (frame.size <= PAYLOAD) return ByteArray(0)
        val declared = frame[PAYLOAD_LEN_IDX].u8()
        val avail = frame.size - PAYLOAD
        val n = if (declared in 1..avail) declared else avail
        return frame.copyOfRange(PAYLOAD, PAYLOAD + n)
    }

    /** 整帧长度：官方 = b[3] + 8（粘包拆分时用）。 */
    fun frameLengthOf(frame: ByteArray): Int =
        if (frame.size > LEN_FIELD) frame[LEN_FIELD].u8() + 8 else -1

    // ---------- CRC16 / Modbus ----------

    private val CRC_TABLE: IntArray = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c shr 1) xor 0xA001 else c shr 1 }
        c and 0xFFFF
    }

    /** 对应 `BlockUtils.crc16Check([B)`：CRC16/Modbus，跳过末 2 字节。 */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        val end = data.size - 2
        var i = 0
        while (i < end) {
            crc = (crc shr 8) xor CRC_TABLE[(crc xor data[i].u8()) and 0xFF]
            i++
        }
        return crc and 0xFFFF
    }

    // ---------- 电量语义（官方 parseBattery） ----------

    /**
     * 官方电量负载（payload 起于 b[10]，共 6 字节）。
     * `[L.on][L.level][R.on][R.level][C.on][C.level]`
     */
    data class OfficialBattery(
        val leftCharging: Boolean,
        val leftLevel: Int,
        val rightCharging: Boolean,
        val rightLevel: Int,
        val caseCharging: Boolean,
        val caseLevel: Int,
    )

    /** 解析官方电量 payload；长度不足 6 返回 null。
     *
     * ⚠️ 充电位语义必须与官方 parseBatteryNew 一致：`flag == 0x01` 才算充电中。
     *    实测 MC05 回帧 `00 64 00 64 ff 39` —— 仓位 `0xff` 表示「不在充/未知」，
     *    若写成 `!= 0` 会误判为充电中。 */
    fun parseBattery(payload: ByteArray): OfficialBattery? {
        if (payload.size < 6) return null
        return OfficialBattery(
            leftCharging = payload[0].u8() == 0x01,
            leftLevel = payload[1].u8(),
            rightCharging = payload[2].u8() == 0x01,
            rightLevel = payload[3].u8(),
            caseCharging = payload[4].u8() == 0x01,
            caseLevel = payload[5].u8(),
        )
    }

    // ---------- 流式组帧器（官方帧边界：b[3]+8） ----------

    /**
     * 与旧 `XiberiaFrameCodec.StreamFramer` 同接口，边界改为官方规则：
     * `b[0]==0xFF && b[1]==0x03 && b[2]==0x00` 且收满 `b[3]+8` 字节。
     * 支持粘包（一次 feed 解出多条）。
     */
    class StreamFramer {
        private val buffer = mutableListOf<Byte>()

        fun feed(bytes: ByteArray, count: Int): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            for (i in 0 until count) {
                buffer += bytes[i]
                tryExtract()?.let { out += it }
            }
            return out
        }

        private fun tryExtract(): ByteArray? {
            // 丢帧头前的噪声（0xFF 03 00 序列）
            while (buffer.size >= 3 &&
                !(buffer[0].u8() == HEADER && buffer[1].u8() == FIXED_B1 && buffer[2].u8() == FIXED_B2)
            ) {
                buffer.removeAt(0)
            }
            if (buffer.size < PAYLOAD) return null
            val total = buffer[LEN_FIELD].u8() + 8
            if (total <= PAYLOAD) { buffer.removeAt(0); return null }
            if (buffer.size < total) return null
            val frame = ByteArray(total) { buffer[it] }
            repeat(total) { buffer.removeAt(0) }
            return frame
        }

        fun reset() = buffer.clear()

        private fun Byte.u8(): Int = this.toInt() and 0xFF
    }

    private fun Byte.u8(): Int = this.toInt() and 0xFF
}
