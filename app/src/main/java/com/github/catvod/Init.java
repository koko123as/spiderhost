/* Host-side com.github.catvod.Init (context holder). Shape follows Kototoro (Apache-2.0). */
package com.github.catvod;

import android.content.Context;

import java.lang.ref.WeakReference;

public class Init {

    private WeakReference<Context> context = new WeakReference<>(null);

    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }

    private static Init get() {
        return Loader.INSTANCE;
    }

    public static void set(Context context) {
        get().context = new WeakReference<>(context);
    }

    public static Context context() {
        return get().context.get();
    }
}
