/* Minimal host-side com.github.catvod.net.OkHttp. Subset of the method set Kototoro (Apache-2.0) exposes. */
package com.github.catvod.net;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OkHttp {

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(30);

    private static class Loader {
        static volatile OkHttp INSTANCE = new OkHttp();
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .build();

    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    public static OkHttpClient client() {
        return get().client;
    }

    public static OkHttpClient player() {
        return client();
    }

    public static OkHttpClient client(long timeout) {
        return client().newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    public static OkHttpClient noRedirect() {
        return client().newBuilder().followRedirects(false).followSslRedirects(false).build();
    }

    public static OkHttpClient client(boolean redirect, long timeout) {
        return redirect ? client(timeout) : client(timeout).newBuilder().followRedirects(false).followSslRedirects(false).build();
    }

    public static String string(String url) {
        return string(url, null);
    }

    public static String string(String url, Map<String, String> headers) {
        try (Response response = newCall(url, headers).execute()) {
            return response.body() == null ? "" : response.body().string();
        } catch (Exception e) {
            return "";
        }
    }

    public static byte[] bytes(String url) {
        try (Response response = newCall(url).execute()) {
            return response.body() == null ? new byte[0] : response.body().bytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static Call newCall(String url) {
        return client().newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) builder.headers(Headers.of(headers));
        return client().newCall(builder.build());
    }

    public static Call newCall(OkHttpClient client, String url) {
        return client.newCall(new Request.Builder().url(url).build());
    }
}
