package de.robv.android.xposed;

import java.lang.reflect.Member;

public final class XposedBridge {
    private XposedBridge() {}

    public static int hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub only, provided by framework at runtime");
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub only, provided by framework at runtime");
    }
}