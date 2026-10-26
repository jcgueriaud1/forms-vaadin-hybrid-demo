package org.vaadin.formshybrid.hello;

import java.math.BigDecimal;

/** A row of HYBRID.PRODUCTS. */
public record Product(long id, String name, BigDecimal price) {
}
