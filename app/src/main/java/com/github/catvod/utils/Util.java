/* Minimal host-side com.github.catvod.utils.Util. Subset modelled on Kototoro (Apache-2.0). */
package com.github.catvod.utils;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;

public class Util {

    public static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    public static final int URL_SAFE = Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP;

    public static String base64(String s) {
        return base64(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String base64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.DEFAULT | Base64.NO_WRAP);
    }

    public static byte[] decode(String s) {
        return Base64.decode(s, Base64.DEFAULT);
    }

    public static String md5(String src) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(src.getBytes(StandardCharsets.UTF_8));
            return String.format("%032x", new BigInteger(1, digest));
        } catch (Exception e) {
            return "";
        }
    }

    public static String md5(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return String.format("%032x", new BigInteger(1, md.digest()));
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean equals(String name, String md5) {
        return md5(Path.jar(name)).equalsIgnoreCase(md5);
    }

    public static String getIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
