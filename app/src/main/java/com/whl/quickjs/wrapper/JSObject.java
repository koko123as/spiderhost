/*
 * Stub of com.whl.quickjs.wrapper.JSObject. The obfuscated static helper is the
 * one Kototoro (Apache-2.0) found jars calling on the host class; kept verbatim.
 * This is NOT a working QuickJS binding; jars that need a real JS engine will fail here.
 */
package com.whl.quickjs.wrapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class JSObject {
    public static Object oOoOoOoOoOoOoO0o(Method method, Object target, Object[] args) {
        if (method == null) {
            return null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable targetError = error.getTargetException();
            if (targetError instanceof RuntimeException) {
                throw (RuntimeException) targetError;
            }
            throw new RuntimeException(targetError);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
