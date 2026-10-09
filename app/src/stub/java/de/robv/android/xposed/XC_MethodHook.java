package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    @SuppressWarnings("unchecked")
    public static abstract class MethodHookParam<T extends XC_MethodHook> {
        public T method;
        public Object thisObject;
        public Object[] args;
        public boolean returnEarly;
        public Object result;
        public Throwable throwable;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.returnEarly = true; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { this.throwable = t; this.returnEarly = true; }
        public boolean hasThrowable() { return throwable != null; }
    }
}