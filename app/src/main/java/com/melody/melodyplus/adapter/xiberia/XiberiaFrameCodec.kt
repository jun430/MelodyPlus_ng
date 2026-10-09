// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaFrameCodec.kt
// 说明: cchip(XIBERIA) 帧编解码 —— **已切换为官方帧格式**。
//
// 真源（workspace 55490541，com.cchip.desheng）：
//   Protocol.packData(II[B)[B / packData(I[B)[B / BlockUtils.crc16Check([B)
//   详见 docs/REF_ChipDesheng_Frame_And_Codec.md
//
// 本类保留旧接口签名（encode / keyOf / payloadOf / StreamFramer / isResponse / isOk），
// 内部实现改为官方规则，call site（XiberiaRfcommClient / XiberiaSession / FeatureBackend）无需改动。
//
// 帧结构（官方控制/查询帧，逐字节从 packData smali 还原）：
//   FF | 03 | 00 | (size-8) | 01 | 08 | cmdHi | cmdLo | 00 | plen | payload...
//   size = max(payload.size + 10, maxLen)，maxLen 默认 0x0F
package com.melody.melodyplus.adapter.xiberia

object XiberiaFrameCodec {

    // ---------- 帧常量（保留旧名，语义已换为官方） ----------
    const val HEADER: Int = XiberiaOfficialCodec.HEADER          // 0xFF
    const val FIXED_B1: Int = XiberiaOfficialCodec.FIXED_B1      // 0x03
    const val FIXED_B2: Int = XiberiaOfficialCodec.FIXED_B2      // 0x00
    const val FIXED_B4: Int = XiberiaOfficialCodec.FIXED_B4      // 0x01（官方 aput-byte v2=0x1）
    const val FIXED_B5: Int = XiberiaOfficialCodec.FIXED_B5      // 0x08

    const val CMD_HI: Int = XiberiaOfficialCodec.CMD_HI          // 6
    const val CMD_LO: Int = XiberiaOfficialCodec.CMD_LO          // 7
    const val PAYLOAD: Int = XiberiaOfficialCodec.PAYLOAD        // 10

    /** 保留旧常量（新帧里 b[8] 恒为 0x00，不再是请求/应答标志）。 */
    const val REQ: Int = 0x00
    const val RESP: Int = 0x02

    /** 组一条官方控制/查询帧（cmd 16bit + payload），默认按官方 BASE_SEND_PACKET_SIZE 补零。 */
    fun encode(
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        maxLen: Int = XiberiaOfficialCodec.BASE_SEND_PACKET_SIZE,
    ): ByteArray = XiberiaOfficialCodec.encode(cmd, payload, maxLen)

    /** 命令码 = (b[6]<<8)|b[7]。 */
    fun keyOf(frame: ByteArray): Int = XiberiaOfficialCodec.keyOf(frame)

    /**
     * 官方帧没有请求/应答标志位；应答与否由「命令码匹配 + 来自耳机」判定。
     * 此处以「是否合法官方帧」冒充旧 isResponse 的语义，保证 RfcommClient 的 accept 判定可用。
     */
    fun isResponse(frame: ByteArray): Boolean = XiberiaOfficialCodec.isFrame(frame)

    /** 官方帧无统一成功码；默认视为成功（具体结果由 payload 语义判定）。 */
    fun isOk(frame: ByteArray): Boolean = XiberiaOfficialCodec.isFrame(frame)

    /** 取 payload（按官方 b[9] 长度域）。 */
    fun payloadOf(frame: ByteArray): ByteArray = XiberiaOfficialCodec.payloadOf(frame)

    /** 流式组帧器 —— 官方帧边界 b[3]+8，支持粘包。 */
    class StreamFramer {
        private val delegate = XiberiaOfficialCodec.StreamFramer()
        fun feed(bytes: ByteArray, count: Int): List<ByteArray> = delegate.feed(bytes, count)
        fun reset() = delegate.reset()
    }
}