package org.vaadin.formshybrid.contract;

/** Events the embedded view sends to the host form, and their parameter keys. */
public final class HybridEvents {

    /** The view is attached and can take calls; the bean replays the current customer. */
    public static final String READY = "ready";

    /** The user asked Forms to show a greeting; carries {@link #MESSAGE}. */
    public static final String GREET = "greet";

    public static final String MESSAGE = "message";

    private HybridEvents() {
    }
}
