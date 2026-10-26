package org.vaadin.formshybrid.hello;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.vaadin.formshybrid.hello.OrderRepository.OrderChangedException;
import org.vaadin.formshybrid.hello.OrderRepository.OrderLockedException;

/**
 * Saving an order against the demo database (setup-db.sh), the way ADR-0003
 * requires: a bounded lock wait, and a changed-since check. Skipped when the
 * database isn't reachable. Neither case changes any data.
 */
class OrderRepositoryDatabaseTest {

    private static final long ORDER = 1003;

    private DriverManagerDataSource dataSource;
    private JdbcClient jdbc;
    private OrderRepository orders;

    @BeforeEach
    void connect() {
        dataSource = new DriverManagerDataSource(
                env("HYBRID_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/XEPDB1"),
                env("HYBRID_DB_USER", "hybrid"), env("HYBRID_DB_PASSWORD", "hybrid"));
        try (Connection c = dataSource.getConnection()) {
            assumeTrue(c.isValid(5));
        } catch (SQLException e) {
            assumeTrue(false, "demo database not reachable: " + e.getMessage());
        }
        jdbc = JdbcClient.create(dataSource);
        orders = new OrderRepository(jdbc);
    }

    @Test
    void orderLockedElsewhereIsReportedAfterAboutFiveSeconds() throws SQLException {
        long version = currentVersion();
        try (Connection formsUser = dataSource.getConnection()) {
            formsUser.setAutoCommit(false);
            formsUser.createStatement().execute("SELECT id FROM orders WHERE id = " + ORDER + " FOR UPDATE");
            Instant start = Instant.now();
            assertThrows(OrderLockedException.class,
                    () -> orders.save(ORDER, version, LocalDate.now(), 3, 1, "NEW"));
            Duration waited = Duration.between(start, Instant.now());
            assertTrue(waited.toSeconds() >= 4, "waited " + waited);
            formsUser.rollback();
        }
    }

    @Test
    void orderChangedSinceReadIsReported() {
        long version = currentVersion();
        assertThrows(OrderChangedException.class,
                () -> orders.save(ORDER, version - 1, LocalDate.now(), 3, 1, "NEW"));
    }

    private long currentVersion() {
        return jdbc.sql("SELECT version FROM orders WHERE id = :id").param("id", ORDER)
                .query(Long.class).single();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
