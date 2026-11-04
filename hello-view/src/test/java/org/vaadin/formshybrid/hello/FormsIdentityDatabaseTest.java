package org.vaadin.formshybrid.hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one-time token flow of #17 against the demo database (setup-db.sh): a
 * Forms user issues a token through its own session, the view's side redeems
 * it once. Skipped when the database isn't reachable.
 */
class FormsIdentityDatabaseTest {

    private static final String URL = env("HYBRID_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/XEPDB1");

    private SingleConnectionDataSource viewSide;
    private JdbcClient jdbc;
    private FormsIdentity identity;

    @BeforeEach
    void connect() {
        try {
            viewSide = new SingleConnectionDataSource(URL, "hybrid", "hybrid", true);
            assumeTrue(viewSide.getConnection().isValid(5));
        } catch (SQLException e) {
            assumeTrue(false, "demo database not reachable: " + e.getMessage());
        }
        jdbc = JdbcClient.create(viewSide);
        identity = new FormsIdentity(jdbc);
    }

    @Test
    void aTokenSignsInItsFormsUserOnce() throws SQLException {
        String token = issueTokenAs("alice");
        assertEquals(Optional.of("ALICE"), inTransaction(() -> identity.redeem(token)));
        assertEquals(Optional.empty(), inTransaction(() -> identity.redeem(token)));
    }

    @Test
    void aForgedTokenSignsNobodyIn() {
        assertEquals(Optional.empty(), inTransaction(() -> identity.redeem("ALICE")));
        assertEquals(Optional.empty(), inTransaction(() -> identity.redeem("0".repeat(64))));
    }

    @Test
    void anExpiredTokenSignsNobodyIn() throws SQLException {
        String token = issueTokenAs("bob");
        jdbc.sql("UPDATE hybrid_tokens SET expires_at = SYSTIMESTAMP - INTERVAL '1' SECOND WHERE token = :t")
                .param("t", token).update();
        assertEquals(Optional.empty(), inTransaction(() -> identity.redeem(token)));
    }

    @Test
    void aNoteIsWrittenAsTheSignedInUserAndTheConnectionIsCleanedUp() {
        NoteRepository notes = new NoteRepository(jdbc);
        String text = "test note " + System.nanoTime();
        inTransaction(() -> {
            notes.add("BOB", 103, text);
            return null;
        });
        try {
            assertEquals("BOB", jdbc.sql("SELECT author FROM customer_notes WHERE note = :n")
                    .param("n", text).query(String.class).single());
            assertNull(jdbc.sql("SELECT SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') FROM dual")
                    .query(String.class).optional().orElse(null));
        } finally {
            jdbc.sql("DELETE FROM customer_notes WHERE note = :n").param("n", text).update();
        }
    }

    /** What the form does: call hybrid.issue_token in the Forms user's own session. */
    private static String issueTokenAs(String formsUser) throws SQLException {
        try (Connection c = new SingleConnectionDataSource(URL, formsUser, formsUser, false).getConnection();
                ResultSet rs = c.createStatement().executeQuery("SELECT hybrid.issue_token FROM dual")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    // @Transactional isn't applied outside Spring: run the repository calls in
    // a transaction the same way
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(viewSide)).execute(s -> work.get());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
