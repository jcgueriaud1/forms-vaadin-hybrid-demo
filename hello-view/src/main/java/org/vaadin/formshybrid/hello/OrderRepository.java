package org.vaadin.formshybrid.hello;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders, which the Vaadin view owns: it is the only side of the hybrid screen
 * that writes them (ADR-0003). Other, legacy Forms screens may still lock or
 * change the same rows, so saving waits for a lock at most a few seconds and
 * checks that the row didn't change since it was read.
 */
@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Order> findByCustomer(long customerId) {
        return jdbc.sql("""
                SELECT o.id, o.customer_id, o.order_date, o.product_id, p.name AS product_name,
                       o.quantity, o.status, o.version, o.quantity * p.price AS amount
                FROM orders o JOIN products p ON p.id = o.product_id
                WHERE o.customer_id = :customerId
                ORDER BY o.order_date DESC""")
                .param("customerId", customerId)
                .query(Order.class)
                .list();
    }

    public List<Product> findProducts() {
        return jdbc.sql("SELECT id, name, price FROM products ORDER BY name")
                .query(Product.class)
                .list();
    }

    /** The same definition of the total as the form's: the ORDER_TOTALS view. */
    public BigDecimal totalFor(long customerId) {
        return jdbc.sql("SELECT NVL(MAX(total), 0) FROM order_totals WHERE customer_id = :customerId")
                .param("customerId", customerId)
                .query(BigDecimal.class)
                .single();
    }

    /**
     * Saves the edited fields of an order in one short transaction, which has
     * committed when this method returns.
     *
     * @throws OrderLockedException if another session (a Forms user) holds the
     *         row for more than 5 seconds
     * @throws OrderChangedException if the order changed since it was read
     */
    @Transactional
    public void save(long id, long expectedVersion, LocalDate orderDate, long productId,
            int quantity, String status) {
        long version;
        try {
            version = jdbc.sql("SELECT version FROM orders WHERE id = :id FOR UPDATE WAIT 5")
                    .param("id", id)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(OrderChangedException::new);
        } catch (DataAccessException e) {
            if (isLockTimeout(e)) {
                throw new OrderLockedException(e);
            }
            throw e;
        }
        if (version != expectedVersion) {
            throw new OrderChangedException();
        }
        jdbc.sql("""
                UPDATE orders
                SET order_date = :orderDate, product_id = :productId, quantity = :quantity,
                    status = :status, version = version + 1
                WHERE id = :id""")
                .param("orderDate", orderDate)
                .param("productId", productId)
                .param("quantity", quantity)
                .param("status", status)
                .param("id", id)
                .update();
    }

    // Spring doesn't translate these Oracle codes to a lock exception, so check
    // them directly: ORA-30006 (WAIT timed out) and ORA-00054 (NOWAIT, busy).
    // The driver chains its own non-SQLException cause below the SQLException,
    // so look for the first SQLException rather than the deepest cause.
    private static boolean isLockTimeout(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getErrorCode() == 30006 || sql.getErrorCode() == 54;
            }
        }
        return false;
    }

    /** Another session holds the order: typically a Forms user editing it. */
    public static class OrderLockedException extends RuntimeException {
        OrderLockedException(Throwable cause) {
            super(cause);
        }
    }

    /** The order was changed or deleted since it was read. */
    public static class OrderChangedException extends RuntimeException {
    }
}
