/*
 * Stub of com.whl.quickjs.android.QuickJSLoader, adapted from Kototoro (Apache-2.0).
 * init() only tries System.loadLibrary; this APK ships no QuickJS .so, so it is a no-op
 * unless the jar itself provided one. The obfuscated static helper is kept verbatim
 * because jars resolve it by exact name on the host class.
 */
package com.whl.quickjs.android;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

public final class QuickJSLoader {
    private static volatile boolean initialized;

    private QuickJSLoader() {
    }

    public interface Console {
    }

    public static void init() {
        init(Boolean.FALSE);
    }

    public static void init(Boolean redirectStdout) {
        if (!initialized) {
            synchronized (QuickJSLoader.class) {
                if (!initialized) {
                    initialized = tryLoad("quickjs-android-wrapper") || tryLoad("quickjs");
                }
            }
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void startRedirectingStdoutStderr(String prefix) {
    }

    private static boolean tryLoad(String libraryName) {
        try {
            System.loadLibrary(libraryName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object oOoOoOoOoOoOoO0o(Console console, Object[] args) {
        if (console == null) {
            return null;
        }
        for (Method method : console.getClass().getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            try {
                method.setAccessible(true);
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 0) return method.invoke(console);
                if (types.length == 1 && types[0].isArray()) {
                    Object arrayArg = args == null ? Array.newInstance(types[0].getComponentType(), 0) : args;
                    return method.invoke(console, arrayArg);
                }
                if (types.length == (args == null ? 0 : args.length)) return method.invoke(console, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
