package com.fongmi.android.tv.api.loader;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.*;

public class SpiderInitializerTest {
    @Test public void nullInternalLoaderStopsInitialization() {
        assertThrows(IllegalStateException.class,
                () -> SpiderInitializer.initialize(EmptyLoader.class, Object.class, new Object()));
    }

    @Test public void initializationFailureIsNotSwallowed() {
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> SpiderInitializer.initialize(BrokenInit.class, Object.class, new Object()));
        assertTrue(error.getCause() instanceof IllegalStateException);
    }

    @Test public void checksLoaderAfterInitialization() throws Exception {
        WorkingLoader.value = null;
        SpiderInitializer.initialize(WorkingLoader.class, Object.class, getClass().getClassLoader());
        assertSame(getClass().getClassLoader(), WorkingLoader.value);
    }

    @Test public void sourcesWithoutOptionalMethodsStillWork() throws Exception {
        SpiderInitializer.initialize(NoInit.class, Object.class, new Object());
        SpiderInitializer.initialize(UnrelatedLoader.class, Object.class, new Object());
    }

    public static class EmptyLoader {
        public static void init(Object context) {}
        public static ClassLoader loader() { return null; }
    }
    public static class BrokenInit {
        public static void init(Object context) { throw new IllegalStateException("Initialization failed"); }
    }
    public static class WorkingLoader {
        static ClassLoader value;
        public static void init(Object context) { value = (ClassLoader) context; }
        public static ClassLoader loader() { return value; }
    }
    public static class NoInit {}
    public static class UnrelatedLoader {
        public static void init(Object context) {}
        public static String loader() { return null; }
    }
}
