package com.fongmi.android.tv.api.loader;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/** Validate optional source initialization before entering a Spider's native constructor. */
final class SpiderInitializer {
    private SpiderInitializer() {}

    static void initialize(Class<?> type, Class<?> contextType, Object context) throws ReflectiveOperationException {
        initialize(type, contextType, context, message -> {});
    }

    static void initialize(Class<?> type, Class<?> contextType, Object context, Consumer<String> log) throws ReflectiveOperationException {
        try {
            log.accept("init_call class=" + type.getName());
            type.getMethod("init", contextType).invoke(null, context);
            log.accept("init_returned");
        } catch (NoSuchMethodException ignored) {
            log.accept("init_hook_absent");
            // Some sources do not expose an initialization hook.
        }
        Method loader;
        try {
            loader = type.getMethod("loader");
        } catch (NoSuchMethodException ignored) {
            log.accept("loader_hook_absent");
            return;
        }
        if (!Modifier.isStatic(loader.getModifiers()) || !ClassLoader.class.isAssignableFrom(loader.getReturnType())) return;
        log.accept("loader_check_start");
        Object internal = loader.invoke(null);
        log.accept("loader_check_returned class=" + (internal == null ? "null" : internal.getClass().getName()));
        if (internal == null) throw new IllegalStateException("Source initialization returned no internal class loader");
    }
}
