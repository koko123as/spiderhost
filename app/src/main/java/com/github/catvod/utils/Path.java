/* Minimal host-side com.github.catvod.utils.Path. Directory layout follows Kototoro / FongMi (Apache-2.0). */
package com.github.catvod.utils;

import com.github.catvod.Init;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Path {

    private static File mkdir(File file) {
        if (file != null && !file.exists()) file.mkdirs();
        return file;
    }

    public static File cache() {
        return Init.context().getCacheDir();
    }

    public static File files() {
        return Init.context().getFilesDir();
    }

    public static File so() {
        return mkdir(new File(files(), "so"));
    }

    public static File jar() {
        return mkdir(new File(cache(), "jar"));
    }

    public static File js() {
        return mkdir(new File(cache(), "js"));
    }

    public static File jar(String name) {
        return new File(jar(), Util.md5(name).concat(".jar"));
    }

    public static File cache(String name) {
        return new File(cache(), name);
    }

    public static File files(String name) {
        return new File(files(), name);
    }

    public static boolean exists(String path) {
        return new File(path.replace("file://", "")).exists();
    }

    public static String read(File file) {
        try {
            return read(new FileInputStream(file));
        } catch (Exception e) {
            return "";
        }
    }

    public static String read(InputStream is) {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    public static File write(File file, byte[] data) {
        try {
            mkdir(file.getParentFile());
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
            }
        } catch (Exception ignored) {
        }
        return file;
    }
}
