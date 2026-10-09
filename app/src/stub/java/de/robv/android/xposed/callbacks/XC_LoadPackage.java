package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.XC_MethodHook;

public abstract class XC_LoadPackage extends XC_MethodHook {
    public XC_LoadPackage() {}
    public XC_LoadPackage(int priority) {}

    @SuppressWarnings("unused")
    public static final class LoadPackageParam extends XC_MethodHook.MethodHookParam<XC_LoadPackage> {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstPackage;
    }
}