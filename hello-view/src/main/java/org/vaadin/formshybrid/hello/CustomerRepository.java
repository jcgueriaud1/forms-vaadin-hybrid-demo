package org.vaadin.formshybrid.hello;

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

    public Optional<Customer> findById(long id) {
        return jdbc.sql("SELECT id, name, city, country FROM customers WHERE id = :id")
                .param("id", id)
                .query(Customer.class)
                .optional();
    }
}
