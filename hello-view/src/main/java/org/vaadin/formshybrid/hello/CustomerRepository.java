package org.vaadin.formshybrid.hello;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads customers from the same Oracle schema as the host form. */
@Repository
public class CustomerRepository {

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A customer with their orders total, from the same ORDER_TOTALS view as the form's. */
    public record CustomerTotal(long id, String name, String city, String country, BigDecimal ordersTotal) {
    }

    public List<CustomerTotal> findAllWithTotals() {
        return jdbc.sql("""
                SELECT c.id, c.name, c.city, c.country, NVL(t.total, 0) AS orders_total
                FROM customers c LEFT JOIN order_totals t ON t.customer_id = c.id
                ORDER BY c.name""")
                .query(CustomerTotal.class)
                .list();
    }

    public Optional<Customer> findById(long id) {
        return jdbc.sql("SELECT id, name, city, country FROM customers WHERE id = :id")
                .param("id", id)
                .query(Customer.class)
                .optional();
    }
}
