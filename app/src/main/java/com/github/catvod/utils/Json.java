/* Minimal host-side com.github.catvod.utils.Json. Subset modelled on Kototoro (Apache-2.0). */
package com.github.catvod.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

public class Json {

    public static JsonElement parse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    public static boolean isObj(String text) {
        try {
            return parse(text).isJsonObject();
        } catch (Throwable e) {
            return false;
        }
    }

    public static String safeString(JsonObject obj, String key) {
        try {
            return obj.has(key) ? obj.get(key).getAsString() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    public static JsonObject safeObject(JsonElement element) {
        try {
            if (element.isJsonPrimitive()) element = parse(element.getAsString());
            return element.getAsJsonObject();
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    public static Map<String, String> toMap(String json) {
        return toMap(parse(json));
    }

    public static Map<String, String> toMap(JsonElement element) {
        Map<String, String> map = new HashMap<>();
        JsonObject obj = safeObject(element);
        for (String key : obj.keySet()) map.put(key, safeString(obj, key));
        return map;
    }
}
