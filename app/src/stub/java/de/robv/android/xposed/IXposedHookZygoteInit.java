package de.robv.android.xposed;

/**
 * 存根（compileOnly）：真实实现由框架在运行时提供。
 * zygote 初始化回调 —— 用于获取模块自身 APK 路径（StartupParam.modulePath）。
 */
public interface IXposedHookZygoteInit {

    void initZygote(StartupParam startupParam) throws Throwable;

    /** 与框架真实内部类同名（IXposedHookZygoteInit$StartupParam），字节码签名一致。 */
    class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}