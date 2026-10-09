package com.melody.melodyplus.bridge
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hook 运行日志条目。
 * @param level 日志级别：I / W / E
 */
data class HookLogEntry(
    val level: String,
    val source: String,       // 来源（Hook 类名 / 进程）
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("level", level)
        put("source", source)
        put("message", message)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(obj: JSONObject): HookLogEntry? = runCatching {
            HookLogEntry(
                level = obj.optString("level", "I"),
                source = obj.optString("source", ""),
                message = obj.optString("message", ""),
                timestamp = obj.optLong("timestamp", 0L),
            )
        }.getOrNull()
    }
}

/**
 * Provider 侧日志存储：环形缓冲 + SharedPreferences 持久化。
 * Hook 子进程经 contentResolver.call(append_log) 写入；
 * 主进程 UI 经 call(get_logs) 读取、call(clear_logs) 清空。
 */
object HookLogStore {
    private const val PREFS = "module_hook_logs"
    private const val KEY_LOGS = "logs_json"
    const val MAX_ENTRIES = 500

    /** 单条 message 最大字符数（超出截断）。限制 Binder/JSON 体积，防任意长文本灌爆存储。 */
    const val MAX_MESSAGE_CHARS = 2_000
    /** 单条 source 最大字符数。 */
    private const val MAX_SOURCE_CHARS = 120
    /** 允许的日志级别白名单（其余归一为 I）。 */
    private val ALLOWED_LEVELS = setOf("I", "W", "E", "D")
    /** 相同 (level,source,message) 在该窗口内只保留一条，抑制高频重复日志洪峰。 */
    private const val DEDUP_WINDOW_MS = 1_000L

    /**
     * 读-改-写串行化锁。
     *
     * 必要性：Provider 运行在模块进程，多个 Hook 子进程经 `contentResolver.call(append_log)`
     * 会落到本进程 Binder 线程池的**多个线程**并发执行 → 各自 readAll 到同一旧列表再写回，
     * 后写覆盖先写导致日志丢失。此处把 append/clear 的「读-改-写」整体互斥，
     * 同时把最近一次去重键也纳入同一临界区。
     */
    private val lock = Any()
    private var lastDedupKey: String? = null
    private var lastDedupAt: Long = 0L

    // --- 内存行缓冲 + 延迟批量落盘（P1-D：Hook 热路径不再同步全量 JSON 读改写）---
    /** 内存中的日志行（JSON 字符串），null 表示尚未从 prefs 加载。 */
    private var buffer: ArrayDeque<String>? = null
    private var dirty = false
    private var flushScheduled = false
    /**
     * 延迟落盘句柄。**必须懒加载**：object 初始化发生在纯 JVM 单测里时，
     * `Looper.getMainLooper()` 是 android.jar stub（抛 RuntimeException），
     * 若放在字段初始化器会直接让 `HookLogStore` 类初始化失败，连 `sanitize`/`normalizeAppendInput`
     * 这类纯函数都不可用。懒加载后纯函数路径不触碰它。
     */
    private val flushHandler: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }
    /**
     * 批量落盘延迟。append 只在内存 O(1) 追加并标记 dirty；真正的全量序列化由
     * 主线程 Handler 合并到一次执行。800ms 内进程被杀会丢最后几条未落盘日志——
     * 这是诊断日志可接受的权衡（换来 Hook 热路径不阻塞）。
     */
    private const val FLUSH_DELAY_MS = 800L

    /** 懒加载：首次访问时把 prefs 中已持久化的行读入内存缓冲。 */
    private fun ensureLoadedLocked(context: Context): ArrayDeque<String> {
        buffer?.let { return it }
        val loaded = ArrayDeque(rawLinesLocked(context))
        buffer = loaded
        return loaded
    }

    /** prefs 中的原始 JSON 行（解析 + 重新序列化，保证磁盘格式与内存缓冲一致）。 */
    private fun rawLinesLocked(context: Context): List<String> =
        readAllLocked(context).map { it.toJson().toString() }

    /** 仅在持锁时调用：把内存缓冲一次性写回 prefs。 */
    private fun flushLocked(context: Context) {
        if (!dirty) return
        val buf = buffer ?: return
        prefs(context).edit().putString(KEY_LOGS, JSONArray(buf.toList()).toString()).apply()
        dirty = false
    }

    /** 安排一次延迟批量落盘（已排队则复用，避免洪峰下反复晒队列）。 */
    private fun scheduleFlush(context: Context) {
        val appCtx = context.applicationContext ?: context
        val handler = flushHandler
        // 无主线程 Looper（纯 JVM 单测 / 极早期）：同步落盘，保证读到的数据一致。
        if (handler == null) {
            synchronized(lock) { flushLocked(appCtx) }
            return
        }
        if (flushScheduled) return
        flushScheduled = true
        handler.postDelayed({
            synchronized(lock) {
                flushScheduled = false
                flushLocked(appCtx)
            }
        }, FLUSH_DELAY_MS)
    }

    /** 归一化并限制单条日志体积；同时把非法 level 归为 I。返回 null 表示应丢弃（空 message）。 */
    fun sanitize(entry: HookLogEntry): HookLogEntry? {
        val message = entry.message.trim()
        if (message.isEmpty()) return null
        val level = entry.level.trim().uppercase().let { if (it in ALLOWED_LEVELS) it else "I" }
        return HookLogEntry(
            level = level,
            source = entry.source.trim().take(MAX_SOURCE_CHARS),
            message = if (message.length > MAX_MESSAGE_CHARS) message.take(MAX_MESSAGE_CHARS) + "…(truncated)" else message,
            timestamp = entry.timestamp,
        )
    }

    /**
     * Provider `append_log` 入口的**纯输入归一化**（无 Context / 无 IO，可单测）。
     *
     * P3 拆分：从 [DeviceRegistryProvider.call] 的 APPEND_LOG 分支抽出——把来自 Binder extras 的
     * 原始 `level/source/message` 归为可入库的 [HookLogEntry]：
     *  - `level` 空 → `"I"`；`source` 空 → [defaultSource]；`message` 先按 [MAX_MESSAGE_CHARS] 钳制；
     *  - `message` 全空白 → 返回 null（Provider 侧应丢弃，不入库）。
     * 注意：这里只做**入口体积/默认值**处理，最终白名单/trim/去重仍由 [sanitize]/[append] 负责。
     */
    fun normalizeAppendInput(
        level: String?,
        source: String?,
        message: String?,
        defaultSource: String,
    ): HookLogEntry? {
        val msg = message.orEmpty().take(MAX_MESSAGE_CHARS)
        if (msg.isBlank()) return null
        return HookLogEntry(
            level = level.orEmpty().ifBlank { "I" },
            source = source.orEmpty().ifBlank { defaultSource },
            message = msg,
        )
    }

    /**
     * 追加一条日志。**O(1) 内存追加 + 延迟批量落盘**（P1-D）。
     *
     * 关键改动：原实现在 Hook 热路径上每次 append 都「读全文 JSON → 解析 → 追加 → 全量序列化 → 写回」，
     * 洪峰下 CPU/IO 成本随条数线性上升。现改为：
     *  - 内存缓冲追加（O(1)），标记 dirty；
     *  - 800ms 合并窗口内的多次 append 由主线程 Handler 一次性写盘（[scheduleFlush]）；
     *  - 去重/白名单/长度限制仍在临界区内完成，语义不变。
     * @return true=已接受（内存）；false=被丢弃（空消息或重复节流命中）。
     */
    fun append(context: Context, entry: HookLogEntry): Boolean {
        val safe = sanitize(entry) ?: run {
            // 空消息 / 非法输入：计入丢弃计数，便于区分「没调用」与「被丢弃」。
            ProviderStats.recordLogDropped()
            return false
        }
        synchronized(lock) {
            val now = safe.timestamp
            val dedupKey = "${safe.level}|${safe.source}|${safe.message}"
            if (dedupKey == lastDedupKey && now - lastDedupAt < DEDUP_WINDOW_MS) {
                // 节流：窗口内完全相同的重复日志不再入库，避免洪峰把真实日志挤出环形缓冲。
                ProviderStats.recordLogDropped()
                return false
            }
            lastDedupKey = dedupKey
            lastDedupAt = now
            val buf = ensureLoadedLocked(context)
            buf.addLast(safe.toJson().toString())
            while (buf.size > MAX_ENTRIES) buf.removeFirst()
            dirty = true
        }
        scheduleFlush(context)
        return true
    }

    /** 读取全部日志（含尚未落盘的内存缓冲）。读取前先合并一次落盘，保证与磁盘一致。 */
    fun readAll(context: Context): List<HookLogEntry> = synchronized(lock) {
        flushLocked(context)
        buffer?.let { return flushScheduledLogs(it) }
        readAllLocked(context)
    }

    /** 把内存行缓冲转回领域对象。 */
    private fun flushScheduledLogs(buf: ArrayDeque<String>): List<HookLogEntry> = buildList {
        buf.forEach { line ->
            runCatching { HookLogEntry.fromJson(JSONObject(line)) }.getOrNull()?.let { add(it) }
        }
    }

    private fun readAllLocked(context: Context): List<HookLogEntry> {
        val raw = prefs(context).getString(KEY_LOGS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { HookLogEntry.fromJson(it) }?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        synchronized(lock) {
            // 清空需同时抹掉内存缓冲，否则下一次 append 会带着旧缓冲重新落盘。
            prefs(context).edit().remove(KEY_LOGS).apply()
            buffer = ArrayDeque()
            dirty = false
            lastDedupKey = null
            lastDedupAt = 0L
        }
    }

    /** 读取最近 [limit] 条（用于 UI 分页/限量拉取，避免每次 Binder 返回完整 JSON）。 */
    fun readRecent(context: Context, limit: Int): List<HookLogEntry> {
        if (limit <= 0) return emptyList()
        return readAll(context).takeLast(limit)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}