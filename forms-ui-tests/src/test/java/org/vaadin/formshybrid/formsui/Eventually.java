package org.vaadin.formshybrid.formsui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Waits for a value that changes on its own, such as a Forms item the form sets
 * when an event arrives from the view. Playwright's assertions wait for the
 * view; this waits for the Forms side and the database.
 */
public final class Eventually {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private Eventually() {
    }

    /** Asserts that {@code actual} returns {@code expected} within 10 seconds. */
    public static <T> void assertEventually(T expected, Supplier<T> actual, String what) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        T last = actual.get();
        while (!Objects.equals(expected, last) && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            last = actual.get();
        }
        assertEquals(expected, last, what + " (waited " + TIMEOUT.toSeconds() + " s)");
    }
}
