package org.vaadin.formshybrid.hello;

/** A row of HYBRID.CUSTOMERS, the table the host form lists. */
public record Customer(long id, String name, String city, String country) {
}
