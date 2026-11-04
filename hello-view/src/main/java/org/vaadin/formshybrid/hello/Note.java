package org.vaadin.formshybrid.hello;

import java.time.LocalDateTime;

/** A row of HYBRID.CUSTOMER_NOTES. The database sets the author (#17). */
public record Note(long id, long customerId, String note, String author, LocalDateTime createdAt) {
}
