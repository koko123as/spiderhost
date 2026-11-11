/* Host-side CatVod logger. Shape follows Kototoro (Apache-2.0). Also mirrors into the SpiderHost log file. */
package com.github.catvod.crawler;

import android.util.Log;

public class SpiderDebug {

    public interface Sink {
        void write(String line);
    }

    private static volatile Sink sink;

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void log(Throwable th) {
        try {
            Log.d("SpiderLog", String.valueOf(th == null ? null : th.getMessage()), th);
            Sink s = sink;
            if (s != null && th != null) s.write("[SpiderDebug] " + Log.getStackTraceString(th));
        } catch (Throwable ignored) {
        }
    }

    public static void log(String msg) {
        try {
            Log.d("SpiderLog", String.valueOf(msg));
            Sink s = sink;
            if (s != null) s.write("[SpiderDebug] " + msg);
        } catch (Throwable ignored) {
        }
    }

    public static String ec(int i) {
        return "";
    }
}
