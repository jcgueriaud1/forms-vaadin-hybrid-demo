package org.vaadin.formshybrid.hello;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A row of HYBRID.ORDERS with its product's name and the line amount.
 * {@code version} is the value read, checked again when the order is saved.
 */
public record Order(long id, long customerId, LocalDate orderDate, long productId,
        String productName, int quantity, String status, long version, BigDecimal amount) {
}
