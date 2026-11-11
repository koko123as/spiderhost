/* Host-side com.github.catvod.utils.Asset. From Kototoro (Apache-2.0). */
package com.github.catvod.utils;

import com.github.catvod.Init;

import java.io.InputStream;

public class Asset {

    public static InputStream open(String fileName) {
        try {
            return Init.context().getAssets().open(fileName.replace("assets://", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String read(String fileName) {
        return Path.read(open(fileName));
    }
}
