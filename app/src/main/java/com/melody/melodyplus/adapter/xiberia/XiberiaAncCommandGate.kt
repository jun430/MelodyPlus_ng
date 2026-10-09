// 文件: app/src/main/java/com/melody/melodyplus/adapter/xiberia/XiberiaAncCommandGate.kt
// 来源: 参考模块 com.oai.huaweimelodycompat.HuaweiAncCommandGate 的源码逻辑 1:1 逆向复刻，
//       协议侧由华为 ANC 改为 XIBERIA（cchip）「模式」语义。
//
// 参考模块原始结构（逆向自 APK，workspace 71313114）：
//   fields : nextToken:J, states:Map<String,State>
//   methods: <init>()
//            begin(State):Start
//            clear(String)
//            complete(String,J,I):Completion        // declared-synchronized
//            desiredMode(String):I                  // declared-synchronized
//            isInFlight(String):Z                   // declared-synchronized
//            presentedMode(String,I):I              // declared-synchronized
//            request(String,I,String):Start         // declared-synchronized
//            static finalIntentSucceeded(Completion,I,Throwable):Z
//            private static key(String):String       // toUpperCase
//   nested : Start / Completion / State
//
// 语义：把「用户点了一次模式切换」建模成一个带 token 的在途请求；
//   同一 MAC 的旧请求会被新请求顶替（token 递增），只有最新 token 的
//   完成回调才被认作有效。这样避免连点造成的 UI 回跳 / 乱序应答。

package com.melody.melodyplus.adapter.xiberia

internal class XiberiaAncCommandGate {

    /** 一次「期望值变更」的起点。 */
    class Start internal constructor(
        val key: String,
        val token: Long,
        val desiredMode: Int,
        val reason: String,
        val supersededToken: Long,
    )

    /** 一次「结果到达」的收尾。 */
    class Completion internal constructor(
        val key: String,
        val token: Long,
        val desiredMode: Int,
        val accepted: Boolean,
        val reason: String,
    )

    private class State(
        var token: Long,
        var desiredMode: Int,
        var presentedMode: Int,
        var inFlight: Boolean,
        var reason: String,
    )

    private var nextToken: Long = 0L
    private val states = HashMap<String, State>()

    @Synchronized
    fun request(mac: String, desiredMode: Int, reason: String): Start {
        val k = key(mac)
        val previous = states[k]
        val token = ++nextToken
        if (previous == null) {
            states[k] = State(
                token = token,
                desiredMode = desiredMode,
                presentedMode = desiredMode,
                inFlight = true,
                reason = reason,
            )
        } else {
            previous.token = token
            previous.desiredMode = desiredMode
            previous.inFlight = true
            previous.reason = reason
        }
        return Start(
            key = k,
            token = token,
            desiredMode = desiredMode,
            reason = reason,
            supersededToken = previous?.token ?: -1L,
        )
    }

    @Synchronized
    fun begin(state: Start): Start = state

    @Synchronized
    fun complete(mac: String, token: Long, success: Int): Completion? {
        val k = key(mac)
        val state = states[k] ?: return null
        val accepted = token == state.token
        if (accepted) {
            state.inFlight = false
            state.presentedMode = if (success == 0) state.desiredMode else state.presentedMode
            states.remove(k)
        }
        return Completion(
            key = k,
            token = token,
            desiredMode = state.desiredMode,
            accepted = accepted,
            reason = state.reason,
        )
    }

    @Synchronized
    fun desiredMode(mac: String): Int = states[key(mac)]?.desiredMode ?: -1

    @Synchronized
    fun presentedMode(mac: String, fallback: Int): Int = states[key(mac)]?.presentedMode ?: fallback

    @Synchronized
    fun isInFlight(mac: String): Boolean = states[key(mac)]?.inFlight == true

    @Synchronized
    fun clear(mac: String) {
        states.remove(key(mac))
    }

    companion object {
        private fun key(mac: String): String = mac.uppercase()

        /** 与参考模块 finalIntentSucceeded 等价：成功码=0 且无异常。 */
        fun finalIntentSucceeded(completion: Completion?, success: Int, failure: Throwable?): Boolean =
            completion != null && completion.accepted && success == 0 && failure == null
    }
}
