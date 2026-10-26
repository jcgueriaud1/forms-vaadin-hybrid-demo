package org.vaadin.formshybrid.contract;

/** Events the embedded view sends to the host form, and their parameter keys. */
public final class HybridEvents {

    /** The view is attached and can take calls; the bean replays the current customer. */
    public static final String READY = "ready";

    /** The user asked Forms to show a greeting; carries {@link #MESSAGE}. */
    public static final String GREET = "greet";

    public static final String MESSAGE = "message";

    /**
     * The view committed a change to one of a customer's orders; carries
     * {@link #CUSTOMER_ID} and that customer's new {@link #TOTAL}. Sent only after
     * the commit (ADR-0003).
     */
    public static final String ORDER_SAVED = "orderSaved";

    public static final String CUSTOMER_ID = "customerId";

    /** A plain decimal string with a '.' separator, for example "1053.00". */
    public static final String TOTAL = "total";

    private HybridEvents() {
    }
}
