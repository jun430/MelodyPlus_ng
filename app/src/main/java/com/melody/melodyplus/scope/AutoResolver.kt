package com.melody.melodyplus.scope

import android.util.Log
import android.view.View
import java.util.concurrent.ConcurrentHashMap

/**
 * AutoResolver —— 混淆名自动适配 Kit
 *
 * 系统更新后混淆类名变化时，通过「类结构指纹」在宿主 classloader 中自动重新定位：
 *  - 指纹 = 字段类型集合 + 无参方法返回类型集合 + 名称语义（连接/电量/降噪等关键词）
 *  - 评分制：候选类按命中特征累加分数，达到阈值认定为原类
 *  - 结果缓存，进程生命周期内只扫描一次
 *
 * 用法（HookContext 提供 resolveClass(key, def)）：
 *   val clazz = resolveClass("batteryInfoVo", "W7.a")
 */
object AutoResolver {
    private const val TAG = "MelodyPlus"
    private val cache = ConcurrentHashMap<String, Class<*>?>()
    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var pool: List<Class<*>> = emptyList()
    @Volatile private var scanned = false
    private val lock = Any()

    fun interface Fingerprint {
        /** 返回该候选类的匹配得分，<=0 表示不匹配。 */
        fun score(candidate: Class<*>): Int
    }

    // ============ 语义关键词工具 ============
    private fun nameOf(c: Class<*>): String = c.name.lowercase()

    // ============ 各语义 key 的指纹表 ============
    private val fingerprints: Map<String, Fingerprint> = buildMap {
        // 电量信息 VO（原 W7.a / BATTERY_INFO_VO_CLASS）：
        // 含多个 int 型 getter（左耳/右耳/仓电量），字段与 getter 命名常含 battery
        put("batteryInfoVo") Fingerprint@{ c ->
            var s = 0
            if (nameOf(c).contains("battery")) s += 3
            val getters = c.declaredMethods.filter { it.parameterTypes.isEmpty() }
            val intGetters = getters.count { it.returnType == Int::class.javaPrimitiveType }
            if (intGetters >= 2) s += 2
            if (c.declaredFields.any { it.type == Int::class.javaPrimitiveType }) s += 1
            if (s >= 4) s else 0
        }
        // 连接状态 VO（原 O7.b / CONNECTION_INFO_VO_CLASS）：布尔连接标志 + 枚举/字符串设备名
        put("connectionVo") Fingerprint@{ c ->
            var s = 0
            val getters = c.declaredMethods.filter { it.parameterTypes.isEmpty() }
            if (getters.any { it.returnType == Boolean::class.javaPrimitiveType }) s += 1
            if (nameOf(c).contains("connect")) s += 3
            if (c.declaredFields.size in 1..12) s += 1
            if (s >= 4) s else 0
        }
        // 降噪状态 VO（原 I8.x / s7.c / NOISE_VO_CLASS）：包含降噪模式枚举字段或 int 模式值
        put("noiseVo") Fingerprint@{ c ->
            var s = 0
            if (nameOf(c).contains("noise") || nameOf(c).contains("anc")) s += 4
            if (c.declaredFields.any { it.type.isEnum }) s += 1
            if (s >= 4) s else 0
        }
        // 设备信息 DTO（保持语义名，防混淆）：含 name/address(MAC) 类型字段
        put("deviceInfoDto") Fingerprint@{ c ->
            var s = 0
            if (nameOf(c).contains("deviceinfo") || nameOf(c).contains("earphone")) s += 4
            if (c.declaredFields.any { it.type == String::class.java }) s += 1
            if (s >= 4) s else 0
        }
        // 详情页主 Fragment（原 O7.G）：androidx Fragment 子类，含 onViewCreated 生命周期 + View 字段
        put("detailMainFragment") Fingerprint@{ c ->
            var s = 0
            if (isSubclassOf(c, "androidx.fragment.app.Fragment")) s += 3
            if (c.declaredMethods.any { it.name == "onViewCreated" }) s += 1
            if (c.declaredFields.any { View::class.java.isAssignableFrom(it.type) }) s += 1
            if (s >= 4) s else 0
        }
        // 详情模型超时回调（原 B8.g）：java.util.function.BiConsumer 型（accept 2参）
        put("detailModelTimeout") Fingerprint@{ c ->
            var s = 0
            if (isSubclassOf(c, "java.util.function.BiConsumer")) s += 4
            if (c.declaredMethods.any { it.name == "accept" && it.parameterTypes.size == 2 }) s += 1
            if (s >= 4) s else 0
        }
        // 详情模型生命周期（原 F8.b）：有 start()/stop() 或 onDestroy 成对的协程/生命周期持有者
        put("detailModelLifecycle") Fingerprint@{ c ->
            var s = 0
            val ms = c.declaredMethods.map { it.name }
            if (ms.contains("start")) s += 2
            if (ms.contains("stop") || ms.contains("destroy")) s += 2
            if (s >= 4) s else 0
        }
        // 面板模式条目（原 L4.a）：无参构造 + 多个 int/枚举字段（模式索引 UI 条目）
        put("modeItem") Fingerprint@{ c ->
            var s = 0
            runCatching { c.getDeclaredConstructor() }.onSuccess { s += 2 }
            val fields = c.declaredFields
            if (fields.count { it.type == Int::class.javaPrimitiveType } >= 2) s += 1
            if (fields.any { it.type.isEnum }) s += 1
            if (s >= 4) s else 0
        }
        // MyDeviceInterfaceAgent（原 z8.j）：持有 com.heytap.deviceinfo.MyDevicesInterface Binder 字段
        // + k(int, Bundle) 发令方法 + 含 NOTIFY_APP_ALIVE 相关逻辑（构造函数）
        put("myDeviceAgent") Fingerprint@{ c ->
            var s = 0
            // MyDevicesInterface 是跨 APK AIDL 类型名，R8 不可改名 → 最强锚点
            if (c.declaredFields.any { it.type.name == "com.heytap.deviceinfo.MyDevicesInterface" }) s += 5
            if (c.declaredFields.any { it.type.name == "com.oplus.mydevices.sdk.device.DeviceInfo" }) s += 2
            val ms = c.declaredMethods
            if (ms.any { it.name == "k" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == android.os.Bundle::class.java }) s += 3
            if (ms.any { it.parameterTypes.isEmpty() && it.returnType == Void.TYPE && it.name == "i" }) s += 1
            if (s >= 5) s else 0
        }
    }

    private fun isSubclassOf(c: Class<*>, ancestor: String): Boolean {
        var cur: Class<*>? = c
        while (cur != null) {
            if (cur.name == ancestor) return true
            cur = cur.superclass
        }
        return false
    }

    /** 注入宿主 classloader 并执行一次全量扫描（懒加载，hook 入口调用）。 */
    fun init(cl: ClassLoader) {
        classLoader = cl
    }

    /** 解析语义 key → 实际 Class；未命中返回 null。
     * 三级定位：1) DexKit 字符串常量（最快最准） 2) 结构指纹扫描 3) null */
    fun resolve(key: String): Class<*>? {
        cache[key]?.let { return it }
        ensureScanned()
        val cl = classLoader ?: return null
        val fp = fingerprints[key] ?: return null
        // 扫描/评分放后台线程，避免卡宿主主线程
        return runCatching {
            java.util.concurrent.CompletableFuture.supplyAsync {
                // ---- 第 1 级：DexKit 字符串常量定位（R8 不会改资源名/字符串）----
                val clz = dexKitProbe(key, cl)
                if (clz != null) {
                    Log.i(TAG, "AutoResolver: '$key' -> ${clz.name} (dexkit)")
                    cache[key] = clz
                    return@supplyAsync clz
                }
                // ---- 第 2 级：结构指纹 ----
                val best = pool.map { it to fp.score(it) }
                    .filter { it.second > 0 }
                    .maxByOrNull { it.second }
                val hit = best?.takeIf { it.second >= 4 }?.first
                if (hit != null) {
                    Log.i(TAG, "AutoResolver: '$key' -> ${hit.name} (scan)")
                } else {
                    Log.w(TAG, "AutoResolver: '$key' not resolved")
                }
                cache[key] = hit
                hit
            }.get(8, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull()
    }

    // ============ DexKit 前置层（移植自 MelodyCodecTweaker 思路，纯反射接桥，无编译依赖）============
    // 每个语义 key 对应宿主里的稳定字符串字面量（资源 key / Intent action / SP key 等，R8 不可改名）
    private val stringAnchors: Map<String, Array<String>> = mapOf(
        "detailMainFragment" to arrayOf("pref_device_battery", "pref_anc_switch"),
        "noiseVo" to arrayOf("pref_noise_switch"),
        "batteryInfoVo" to arrayOf("pref_battery_level", "battery_left"),
        "connectionVo" to arrayOf("pref_device_connect"),
        "modeItem" to arrayOf("pref_anc_mode"),
    )

    @Volatile private var dexKitBridge: Any? = null
    @Volatile private var dexKitTried = false

    private fun dexKitProbe(key: String, cl: ClassLoader): Class<*>? {
        val anchors = stringAnchors[key] ?: return null
        val bridge = ensureDexKit(cl) ?: return null
        val moduleCl = AutoResolver::class.java.classLoader ?: return null
        return runCatching {
            // 注意：dexkit 的类全部从模块自身 classloader 加载（宿主 classloader 看不到）
            val apk = apkPathOf(cl) ?: return null
            val findClassCls = moduleCl.loadClass("org.luckypray.dexkit.query.FindClass")
            val matcherCls = moduleCl.loadClass("org.luckypray.dexkit.query.matchers.ClassMatcher")
            val findClass = bridge.javaClass.getMethod("findClass", findClassCls)
            val query = findClassCls.getConstructor().newInstance()
            val matcher = matcherCls.getConstructor().newInstance()
            matcherCls.getMethod("usingStrings", Array<String>::class.java).invoke(matcher, anchors)
            query.javaClass.getMethod("matcher", matcherCls).invoke(query, matcher)
            val result = findClass.invoke(bridge, query)
            // result: ClassDataList（Iterable<ClassData>），ClassData.getClassName() → 类名
            val iter = (result as? Iterable<*>)?.iterator() ?: return null
            while (iter.hasNext()) {
                val item = iter.next() ?: continue
                val name = item.javaClass.getMethod("getClassName").invoke(item) as? String ?: continue
                val c = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
                return c
            }
            null
        }.onFailure { Log.w(TAG, "dexkit probe '$key' failed: $it") }.getOrNull()
    }

    private fun ensureDexKit(cl: ClassLoader): Any? {
        dexKitBridge?.let { return it }
        if (dexKitTried) return null
        synchronized(this) {
            if (dexKitTried) return dexKitBridge
            dexKitTried = true
            runCatching {
                val apk = apkPathOf(cl) ?: return null
                // DexKit桥：模块 APK 内打包了 libdexkit.so + dexkit classes，
                // 但宿主 classloader 看不到它们 → 用模块自身 classloader 加载 DexKit，
                // DexKitBridge.create 需要 native lib 在 path 中 —— Xposed 模块 APK 的
                // nativeLibraryDir 会被 LSPosed 加进宿主 path（libxposed API 下通过
                // moduleApplicationInfo.nativeLibraryDir 注入）。这里通过反射拿模块 apk path。
                val moduleCl = AutoResolver::class.java.classLoader ?: return null
                val bridgeCls = moduleCl.loadClass("org.luckypray.dexkit.DexKitBridge")
                val create = bridgeCls.getMethod("create", String::class.java)
                val bridge = create.invoke(null, apk)
                dexKitBridge = bridge
                Log.i(TAG, "AutoResolver: DexKit bridge ready")
            }.onFailure { Log.w(TAG, "DexKit init failed: $it") }
            return dexKitBridge
        }
    }

    private fun apkPathOf(cl: ClassLoader): String? = runCatching {
        val pathList = dalvik.system.BaseDexClassLoader::class.java
            .getDeclaredField("pathList").apply { isAccessible = true }.get(cl)
        val elements = pathList.javaClass.getDeclaredField("dexElements")
            .apply { isAccessible = true }.get(pathList) as Array<Any>
        for (e in elements) {
            for (f in e.javaClass.declaredFields) {
                if (f.type == String::class.java) {
                    runCatching { f.isAccessible = true; (f.get(e) as? String) }
                        .getOrNull()?.let { s -> if (s.endsWith(".apk")) return s }
                }
            }
        }
        null
    }.getOrNull()

    /**
     * 旧混淆名 → 语义 key 别名表。
     * 当硬编码名在宿主中加载失败（版本更新混淆重排）时，按此表映射到指纹重新定位。
     * 新版本适配：别名表的 key 是旧快照名，无需更新；新增语义 key 时补充 value。
     */
    private val aliasToKey: Map<String, String> = mapOf(
        "I8.x" to "noiseVo",
        "s7.c" to "noiseVo",
        "O7.b" to "connectionVo",
        "W7.a" to "batteryInfoVo",
        "O7.G" to "detailMainFragment",
        "B8.g" to "detailModelTimeout",
        "F8.b" to "detailModelLifecycle",
        "L4.a" to "modeItem",
    )

    /** 硬编码名加载失败时的兜底：alias → 语义 key → 指纹扫描。 */
    fun resolveByAlias(obfuscatedName: String): Class<*>? {
        val key = aliasToKey[obfuscatedName] ?: return null
        val hit = resolve(key)
        if (hit != null) {
            Log.i(TAG, "AutoResolver: alias '$obfuscatedName' -> ${hit.name} via '$key'")
        }
        return hit
    }

    private fun ensureScanned() {
        if (scanned) return
        synchronized(lock) {
            if (scanned) return
            val cl = classLoader ?: return
            val found = LinkedHashSet<Class<*>>()
            val shortPattern = Regex("^[a-z][0-9]?\\.[a-z](\\$[0-9]+)?$")
            // 1) 从 classloader 的 dex 枚举全部类名（通过 apk 路径开 DexFile）
            runCatching {
                val pathList = dalvik.system.BaseDexClassLoader::class.java
                    .getDeclaredField("pathList").apply { isAccessible = true }.get(cl)
                val elements = pathList.javaClass.getDeclaredField("dexElements")
                    .apply { isAccessible = true }.get(pathList) as Array<Any>
                for (e in elements) {
                    // Element.path 或 Element 里的 dexFile 字段，逐个探测
                    val apkPath = e.javaClass.declaredFields.firstOrNull { f ->
                        f.type == String::class.java && runCatching {
                            f.isAccessible = true; (f.get(e) as? String)?.endsWith(".apk") == true
                        }.getOrDefault(false)
                    }?.let { it.isAccessible = true; it.get(e) as? String }
                    val dex = apkPath?.let { p -> runCatching { dalvik.system.DexFile(p) }.getOrNull() } ?: continue
                    try {
                        val entries = dex.entries()
                        while (entries.hasMoreElements()) {
                            val n = entries.nextElement()
                            val isTarget = n.startsWith("com.oplus.melody") ||
                                n.startsWith("com.coloros.melody") ||
                                n.startsWith("com.oneplus.melody") ||
                                shortPattern.matches(n)
                            if (!isTarget) continue
                            runCatching { found.add(cl.loadClass(n)) }
                        }
                    } finally {
                        runCatching { dex.close() }
                    }
                }
            }.onFailure { Log.w(TAG, "AutoResolver dex scan failed: $it") }
            // 2) 兜底：全量混淆短名试探（x.y ~ x9.y9.z 等）
            if (found.isEmpty()) {
                val letters = 'a'..'z'
                for (a in letters) for (d in 0..9) for (b in letters) {
                    val n = "$a$d.$b"
                    runCatching { found.add(cl.loadClass(n)) }
                }
            }
            pool = found.toList()
            scanned = true
            Log.i(TAG, "AutoResolver scan: ${pool.size} classes in pool")
        }
    }
}