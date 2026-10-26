package org.vaadin.formshybrid.bean;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;

/**
 * Appends diagnostics to {@code ~/hybrid-bean.log}. The Forms client's stderr is
 * unreliable (uncaught exceptions print a header but no stack trace), so the bean
 * writes its own file.
 */
final class BeanLog {

    private static final File FILE = new File(System.getProperty("user.home"), "hybrid-bean.log");

    private BeanLog() {
    }

    static synchronized void log(String message) {
        log(message, null);
    }

    static synchronized void log(String message, Throwable error) {
        try (PrintWriter out = new PrintWriter(new FileWriter(FILE, true))) {
            out.println(LocalTime.now() + " [" + Thread.currentThread().getName() + "] " + message);
            if (error != null) {
                error.printStackTrace(out);
            }
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    /** Records uncaught exceptions with their stack traces, then lets the previous handler run. */
    static void captureUncaughtExceptions() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            log("uncaught in " + thread.getName(), error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }
}
