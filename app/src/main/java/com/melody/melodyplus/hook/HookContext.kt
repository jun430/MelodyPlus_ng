package com.melody.melodyplus.hook
import com.melody.melodyplus.hook.modLogT
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.melody.melodyplus.bridge.RegistryContract
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
abstract class HookContext {
    lateinit var appClassLoader: ClassLoader
    abstract fun onHook()

    companion object {
        /**
         * 取当前进程的 Application context（Xposed loadPackage 阶段无现成 ctx 时用）。
         * 反射调用 android.app.ActivityThread.currentApplication()。
         */
        fun currentApplication(): Context? = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val method = activityThread.getMethod("currentApplication")
            method.invoke(null) as? Context
        }.getOrNull()
    }
    fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)
    fun findClassOrNull(name: String): Class<*>? =
        runCatching { findClass(name) }.getOrNull()
            ?: com.melody.melodyplus.scope.AutoResolver.resolveByAlias(name)
    fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method =
        findClass(className).getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
    fun findMethodOrNull(className: String, methodName: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { findMethod(className, methodName, *parameterTypes) }.getOrNull()
    fun findConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*> =
        findClass(className).getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }
    fun findConstructorOrNull(className: String, vararg parameterTypes: Class<*>): Constructor<*>? =
        runCatching { findConstructor(className, *parameterTypes) }.getOrNull()
    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method =
        findClass(className).declaredMethods.first { it.name == methodName && it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }
    fun findMethodByParamCountOrNull(className: String, methodName: String, paramCount: Int): Method? =
        runCatching { findMethodByParamCount(className, methodName, paramCount) }.getOrNull()
    fun hookAfter(method: Method, block: HookParam.() -> Unit) = hookMember(method, afterBlock = block)
    fun hookBefore(method: Method, block: HookParam.() -> Unit) = hookMember(method, beforeBlock = block)
    fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit) =
        hookMember(constructor, afterBlock = block)
    private fun hookMember(
        member: Member,
        beforeBlock: (HookParam.() -> Unit)? = null,
        afterBlock: (HookParam.() -> Unit)? = null,
    ) {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                beforeBlock?.invoke(HookParam(param))
            }
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                afterBlock?.invoke(HookParam(param))
            }
        }
        // 关键：不要直接调用 XposedBridge.hookMethod —— 不同 LSPosed 版本签名不同
        // （旧版返回 int，新版返回 XC_MethodHook.Unhook），编译期描述符不匹配会 NoSuchMethodError。
        // 改为反射按名字+参数个数解析，兼容所有实现。
        val m = XposedBridge::class.java.declaredMethods.firstOrNull {
            it.name == "hookMethod" && it.parameterTypes.size == 2 &&
                Member::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: throw NoSuchMethodError("runtime XposedBridge.hookMethod(Member,*) not found")
        m.isAccessible = true
        m.invoke(null, member, callback)
    }
    fun safeHook(tag: String, name: String, action: () -> Unit) {
        runCatching(action).onFailure {
            modLogT("W", "skip hook $name", it)
        }
    }
    fun reportHookStatus(context: Context?) {
        if (context == null) { modLog("I", "reportStatus: context null"); return }
        if (!RegistryContract.isProviderAvailable(context)) {
            modLog("I", "reportStatus: provider unavailable pkg=${context.packageName}")
            return
        }
        runCatching {
            context.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_REPORT_STATUS,
                null,
                Bundle().apply {
                    putString(RegistryContract.EXTRA_HOOK_NAME, this@HookContext.javaClass.simpleName)
                },
            )
        }.onFailure {
            modLog("I", "report status call failed: $it")
        }
    }

    /**
     * 上报一条 Hook 运行日志到模块 UI（Provider 路由）。
     * Hook 各自在关键路径/异常处调用；空消息会被忽略。
     */
    fun reportLog(context: Context?, level: String, message: String) {
        if (context == null || message.isBlank()) return
        if (!RegistryContract.isProviderAvailable(context)) return
        runCatching {
            context.contentResolver.call(
                RegistryContract.URI,
                RegistryContract.METHOD_APPEND_LOG,
                null,
                Bundle().apply {
                    putString(RegistryContract.EXTRA_LOG_LEVEL, level)
                    putString(RegistryContract.EXTRA_LOG_SOURCE, this@HookContext.javaClass.simpleName)
                    putString(RegistryContract.EXTRA_LOG_MESSAGE, message)
                },
            )
        }.onFailure {
            modLog("I", "append log failed: $it")
        }
    }
}

/**
 * 早期日志 Context 解析与补偿队列（文件顶层 object，可供 [modLog] 与 [HookContext] 共用）。
 *
 * 背景：hook 装载期（initZygote / handleLoadPackage / HOOK_BOOT）发生在 Application attach 之前，
 * `ActivityThread.currentApplication()` 为 null → 原 `modLog` 的 Provider 通道被 `?: return` 跳过，
 * 导致「模块日志页看不到 hook 装载日志」（LSP 通道有、模块页没有）。
 *
 * 策略：
 *  ① [anyContext] 在 Application 未就绪时回退 `ActivityThread.getSystemContext()`（进程早期即可用）；
 *  ② 仍拿不到（极罕见）时 [enqueuePendingLog] 暂存，后续任一 modLog 能调到 Provider 时补发。
 *
 * 注意：`getSystemContext()` 只是**进程内**的 Context，从 App 进程经它的 ContentResolver 调外部
 * Provider 与用 Application Context 等效，均走 system_server 解析 authority，不受包可见性限制。
 * 严禁在 zygote 进程调用（见 [HookEntry.initZygote]）。
 */
internal object HookContextLog {
    /** 任意可用的 Context（Application 优先，其次系统 Context）。 */
    fun anyContext(): Context? = HookContext.currentApplication() ?: systemContext()

    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as? Context
    }.getOrNull()

    private const val MAX_PENDING_LOGS = 200
    private val pendingLogs = ArrayDeque<PendingHookLog>()
    private val pendingLock = Any()

    /** 无 Context 时暂存日志，待后续拿到 Context 时补发，避免装载期日志丢失。 */
    fun enqueue(level: String, source: String, message: String) {
        synchronized(pendingLock) {
            if (pendingLogs.size >= MAX_PENDING_LOGS) pendingLogs.removeFirst()
            pendingLogs.addLast(PendingHookLog(level, source, message))
        }
    }

    /** 取出并清空补偿队列（由 [modLog] 在能调到 Provider 时调用）。 */
    fun drain(): List<PendingHookLog> = synchronized(pendingLock) {
        if (pendingLogs.isEmpty()) return emptyList()
        val out = pendingLogs.toList()
        pendingLogs.clear()
        out
    }
}

/** 早期暂存的单条 Hook 日志（见 [HookContextLog.enqueue]）。 */
internal data class PendingHookLog(val level: String, val source: String, val message: String)
class HookParam(private val param: XC_MethodHook.MethodHookParam<out XC_MethodHook>) {
    val args: List<Any?> = param.args?.toList() ?: emptyList()
    val instance: Any? = param.thisObject
    val hasResult: Boolean get() = param.returnEarly
    var result: Any?
        get() = param.result
        set(value) {
            param.result = value
        }

    /**
     * 修改 before 阶段的入参（写回原始 args 数组）。
     * 注意：[args] 是副本，改它无效；需改写被调方法入参时必须走此方法。
     */
    fun setArg(index: Int, value: Any?) {
        val array = param.args ?: return
        if (index in array.indices) array[index] = value
    }
}
fun getObjectField(instance: Any?, fieldName: String): Any? =
    findField(instance, fieldName)?.get(instance)
fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
    findField(instance, fieldName)?.set(instance, value)
}
fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
    if (instance == null) return null
    val method = findMethod(instance.javaClass, methodName, args.size) ?: throw NoSuchMethodException(methodName)
    return method.invoke(instance, *args)
}
fun callMethodOrNull(instance: Any?, methodName: String, vararg args: Any?): Any? =
    runCatching { callMethod(instance, methodName, *args) }.getOrNull()
private object MissingMember
private val fieldCache = ConcurrentHashMap<String, Any>()
private val methodCache = ConcurrentHashMap<String, Any>()
private fun findField(instance: Any?, fieldName: String): Field? {
    if (instance == null) return null
    val key = "${instance.javaClass.name}#$fieldName"
    fieldCache[key]?.let { return it as? Field }
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            val field = cls.getDeclaredField(fieldName).apply { isAccessible = true }
            fieldCache[key] = field
            return field
        }
        cls = cls.superclass
    }
    fieldCache[key] = MissingMember
    return null
}
private fun findMethod(clazz: Class<*>, methodName: String, argCount: Int): Method? {
    val key = "${clazz.name}#$methodName/$argCount"
    methodCache[key]?.let { return it as? Method }
    var cls: Class<*>? = clazz
    while (cls != null) {
        cls.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == argCount
        }?.let { method ->
            method.isAccessible = true
            methodCache[key] = method
            return method
        }
        cls = cls.superclass
    }
    methodCache[key] = MissingMember
    return null
}

// ---------------------------------------------------------------------------
// 统一「双通道」日志入口（文件顶层，同包 com.melody.melodyplus.hook 内可直接调用）
// ---------------------------------------------------------------------------

/**
 * 统一双通道日志：一次调用同时写两路，
 *  ① **LSP 通道**：`XposedBridge.log` → logcat 与 `/data/adb/lspd/log/modules*.log`
 *  ② **模块内部通道**：经 Provider `append_log` 落到
 *     [com.melody.melodyplus.bridge.HookLogStore] → 模块「日志」页与「一键导出」均可见。
 *
 * 签名与 `modLog(String)` 兼容，全项目裸 `modLog(...)` 统一替换为
 * `modLog(...)` 后，**模块自身全部日志都能在模块日志页看到**。
 * 任意进程（melody 主进程 / `:fg` 弹窗进程 / 蓝牙进程）均可调用；
 * ① ② 各自独立 `runCatching`，任一失败都不影响另一路、且不反抛。
 *
 * @param message 日志正文。保留 `[com.melody.melodyplus] ` 前缀（与 LSP 通道保持一致），
 *                落模块日志时会自动去掉该前缀，展示更干净。
 */
fun modLog(message: String) = modLog("I", message)

/** 带 context 的重载：优先用传入的 context，取不到时回退 [HookContext.anyContext]。 */
fun modLog(context: Context?, level: String, message: String) {
    val lspMsg = if (message.startsWith(LSP_PREFIX)) message else "$LSP_PREFIX$message"
    runCatching { de.robv.android.xposed.XposedBridge.log(lspMsg) }
    reportToProvider(
        ctx = context ?: HookContextLog.anyContext(),
        level = level,
        source = inferLogSource(),
        message = message.removePrefix(LSP_PREFIX),
    )
}

/** 带级别的双通道日志（level：I / W / E），语义同 [modLog]。 */
fun modLog(level: String, message: String) {
    // ① LSP 通道：统一补 `[com.melody.melodyplus] ` 前缀（调用方已带则不重复加）。
    val lspMsg = if (message.startsWith(LSP_PREFIX)) message else "$LSP_PREFIX$message"
    runCatching { de.robv.android.xposed.XposedBridge.log(lspMsg) }
    // ② 模块内部通道：Provider 落盘（跨进程，UI 日志页读取）。
    reportToProvider(
        ctx = HookContextLog.anyContext(),
        level = level,
        source = inferLogSource(),
        message = message.removePrefix(LSP_PREFIX),
    )
}

/**
 * 统一的「模块内部通道」写入（双通道的第 ② 路）。
 *
 * 关键修复：hook 装载期（initZygote / handleLoadPackage / HOOK_BOOT）发生在 Application
 * attach 之前，`currentApplication()` 为 null。原实现直接 `?: return` 跳过落盘 →
 * 日志只进 LSP 通道、模块日志页一条都没有。现改为：
 *  - ctx 可解析（含 [HookContextLog.anyContext] 的 systemContext 兜底）→ 先补发暂存日志，再发本条；
 *  - ctx 仍不可得（极罕见）→ 暂存进 [HookContextLog.enqueue]，待后续日志调用时补发。
 *
 * 不做 isProviderAvailable 预检：宿主进程查模块包受 Android 11+ 包可见性限制会误判，
 * 而 contentResolver.call 走 system_server 解析 authority，不受该限制；失败被 runCatching 吞掉。
 */
private fun reportToProvider(ctx: Context?, level: String, source: String, message: String) {
    if (ctx == null) {
        HookContextLog.enqueue(level, source, message)
        return
    }
    if (com.melody.melodyplus.bridge.RegistryCircuit.shouldSkip()) return
    val callOk = runCatching {
        // 补发早期暂存（顺序保持）。
        HookContextLog.drain().forEach { pending ->
            ctx.contentResolver.call(
                com.melody.melodyplus.bridge.RegistryContract.URI,
                com.melody.melodyplus.bridge.RegistryContract.METHOD_APPEND_LOG,
                null,
                logBundle(pending.level, pending.source, pending.message),
            )
        }
        ctx.contentResolver.call(
            com.melody.melodyplus.bridge.RegistryContract.URI,
            com.melody.melodyplus.bridge.RegistryContract.METHOD_APPEND_LOG,
            null,
            logBundle(level, source, message),
        )
    }.isSuccess
    com.melody.melodyplus.bridge.RegistryCircuit.record(callOk)
}

private fun logBundle(level: String, source: String, message: String): android.os.Bundle =
    android.os.Bundle().apply {
        putString(com.melody.melodyplus.bridge.RegistryContract.EXTRA_LOG_LEVEL, level)
        putString(com.melody.melodyplus.bridge.RegistryContract.EXTRA_LOG_SOURCE, source)
        putString(com.melody.melodyplus.bridge.RegistryContract.EXTRA_LOG_MESSAGE, message)
    }

/**
 * 带异常的日志：正文后拼 `: <Throwable>`，与两参版同走双通道。
 * 形如 `modLogT("W", "xxx", e)` 的调用机械转换到此。
 */
fun modLogT(level: String, message: String, throwable: Throwable?) {
    modLog(level, if (throwable == null) message else "$message: $throwable")
}

/** 模块日志统一前缀（LSP 通道保留，模块日志页展示时去掉）。 */
private const val LSP_PREFIX = "[com.melody.melodyplus] "

/** 从调用栈推断模块内日志来源类名（去包名前缀），拿不到时回退固定值。 */
private fun inferLogSource(): String {
    runCatching {
        for (e in Thread.currentThread().stackTrace) {
            val cn = e.className
            if (cn.startsWith("com.melody.melodyplus.") &&
                !cn.endsWith("HookContext") &&
                !cn.endsWith("HookContextKt") &&
                !cn.contains("\$Companion")
            ) {
                return cn.substringAfterLast('.')
            }
        }
    }
    return "MelodyPlus"
}