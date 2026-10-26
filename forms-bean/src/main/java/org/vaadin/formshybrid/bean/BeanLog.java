package org.vaadin.formshybrid.bean;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The bean's diagnostics. Problems ({@link #warn}) always go to stderr, the
 * Forms client's console. With {@code -Dhybrid.debug=true} everything,
 * including the {@link #debug} trace, is also appended to
 * {@code ~/hybrid-bean.log}, and swing-kit's own logging is turned up to debug.
 *
 * The client's stderr is unreliable for uncaught exceptions (they print a header
 * but no stack trace), which is why the file exists.
 */
final class BeanLog {

    static final boolean DEBUG = Boolean.getBoolean("hybrid.debug");

    private static final File FILE = new File(System.getProperty("user.home"), "hybrid-bean.log");

    // java.util.logging keeps loggers weakly: hold it, or its level is lost
    private static final Logger JCEF_MAVEN = Logger.getLogger("me.friwi.jcefmaven");

    private BeanLog() {
    }

    /**
     * Configures swing-kit's SLF4J logging (slf4j-simple, to stderr): warnings
     * only, or debug with {@code hybrid.debug}. Must run before swing-kit logs
     * for the first time. Explicit -Dorg.slf4j.simpleLogger.* settings win.
     * Also hides jcefmaven's start-up progress lines unless debugging.
     */
    static void configure() {
        if (!DEBUG) {
            JCEF_MAVEN.setLevel(Level.WARNING);
        }
        setDefault("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        if (DEBUG) {
            setDefault("org.slf4j.simpleLogger.log.com.vaadin.swingkit", "debug");
        }
        setDefault("org.slf4j.simpleLogger.showDateTime", "true");
        setDefault("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
    }

    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /** The trace of what the bean does; written only with {@code hybrid.debug}. */
    static void debug(String message) {
        if (DEBUG) {
            write(message, null);
        }
    }

    static void warn(String message) {
        warn(message, null);
    }

    /** Something went wrong: always reported. */
    static void warn(String message, Throwable error) {
        System.err.println("hybrid-bean: " + message + (error == null ? "" : ": " + error));
        if (DEBUG) {
            write("WARN " + message, error);
        }
    }

    private static synchronized void write(String message, Throwable error) {
        try (PrintWriter out = new PrintWriter(new FileWriter(FILE, true))) {
            out.println(LocalTime.now() + " [" + Thread.currentThread().getName() + "] " + message);
            if (error != null) {
                error.printStackTrace(out);
            }
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    /**
     * With {@code hybrid.debug}, records uncaught exceptions with their stack
     * traces, then lets the previous handler (or the JVM's default output) run.
     */
    static void captureUncaughtExceptions() {
        if (!DEBUG) {
            return;
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            write("uncaught in " + thread.getName(), error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                // what the JVM prints without a handler (ThreadGroup's would call us back)
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                error.printStackTrace();
            }
        });
    }
}
