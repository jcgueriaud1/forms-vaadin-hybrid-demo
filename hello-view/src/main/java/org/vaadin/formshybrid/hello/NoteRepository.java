package org.vaadin.formshybrid.hello;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer notes, which the notes view owns (ADR-0003). The view never sends an
 * author: it tags the connection with the signed-in Forms user for the
 * transaction, and the table's trigger takes the author from that tag.
 */
@Repository
public class NoteRepository {

    private final JdbcClient jdbc;

    public NoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Note> findByCustomer(long customerId) {
        return jdbc.sql("""
                SELECT id, customer_id, note, author, created_at
                FROM customer_notes WHERE customer_id = :customerId
                ORDER BY created_at DESC""")
                .param("customerId", customerId)
                .query(Note.class)
                .list();
    }

    /**
     * Adds a note as {@code formsUser}, who must come from
     * {@link FormsIdentity#redeem}. The client identifier is cleared before the
     * pooled connection is returned.
     */
    @Transactional
    public void add(String formsUser, long customerId, String note) {
        jdbc.sql("BEGIN DBMS_SESSION.SET_IDENTIFIER(:who); END;").param("who", formsUser).update();
        try {
            jdbc.sql("INSERT INTO customer_notes (customer_id, note) VALUES (:customerId, :note)")
                    .param("customerId", customerId)
                    .param("note", note)
                    .update();
        } finally {
            jdbc.sql("BEGIN DBMS_SESSION.CLEAR_IDENTIFIER; END;").update();
        }
    }
}
