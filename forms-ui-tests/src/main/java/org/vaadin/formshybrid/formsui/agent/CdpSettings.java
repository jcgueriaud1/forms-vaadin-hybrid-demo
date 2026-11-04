package org.vaadin.formshybrid.formsui.agent;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;

/**
 * Opens the embedded browser's CDP port, which swing-kit 3.0.1 doesn't let the
 * app configure (ADR-0004, decision 3). Every new jcefmaven {@code CefAppBuilder}
 * clones the static {@code DEFAULT_CEF_SETTINGS}, so setting the port and a
 * per-process cache directory there, before swing-kit builds its browser, is
 * enough. FSAL loads swing-kit and jcefmaven in its own class loader, so this
 * waits for swing-kit's panel builder to load and uses that loader. No class is
 * changed: the transformer only watches.
 */
final class CdpSettings implements ClassFileTransformer {

    private static final String SWING_KIT_BUILDER = "com/vaadin/swingkit/client/jcef/JCefVaadinPanelBuilder";

    private final int port;
    private volatile boolean done;

    private CdpSettings(int port) {
        this.port = port;
    }

    static void install(Instrumentation instrumentation, int port) {
        instrumentation.addTransformer(new CdpSettings(port));
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
            byte[] bytes) {
        if (!done && SWING_KIT_BUILDER.equals(className)) {
            done = true;
            try {
                Class<?> builder = Class.forName("me.friwi.jcefmaven.CefAppBuilder", true, loader);
                Field defaults = builder.getDeclaredField("DEFAULT_CEF_SETTINGS");
                defaults.setAccessible(true);
                Object settings = defaults.get(null);
                settings.getClass().getField("remote_debugging_port").setInt(settings, port);
                // The default cache directory makes a second JCEF process hand off to
                // one already running (an open demo), and its port never opens.
                File cache = new File(System.getProperty("java.io.tmpdir"),
                        "forms-ui-tests-jcef-" + ProcessHandle.current().pid());
                settings.getClass().getField("root_cache_path").set(settings, cache.getAbsolutePath());
                System.err.println("[cdp-agent] port " + port + ", cache " + cache);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("[cdp-agent] failed: " + e);
            }
        }
        return null;
    }
}
