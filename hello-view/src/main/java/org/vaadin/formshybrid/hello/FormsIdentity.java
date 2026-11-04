package org.vaadin.formshybrid.hello;

import java.util.Optional;

import com.vaadin.flow.server.VaadinSession;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who the Forms user is (#17, ADR-0003). The form gets a one-time token from
 * {@code hybrid.issue_token}, which ties it to the user its database session
 * logged in as; the bean carries only the token. Redeeming it here is the only
 * way a view learns a user, so nothing the client sends is trusted.
 */
@Service
public class FormsIdentity {

    private static final String ATTRIBUTE = FormsIdentity.class.getName() + ".user";

    private final JdbcClient jdbc;

    public FormsIdentity(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Redeems a token once: reads its user if it hasn't expired, and deletes
     * it in the same transaction, so it can't be used again.
     *
     * @return the Forms user, or empty for an unknown, used or expired token
     */
    @Transactional
    public Optional<String> redeem(String token) {
        if (token == null || !token.matches("[0-9A-F]{64}")) {
            return Optional.empty();
        }
        Optional<String> user = jdbc.sql("""
                SELECT forms_user FROM hybrid_tokens
                WHERE token = :token AND expires_at > SYSTIMESTAMP
                FOR UPDATE""")
                .param("token", token)
                .query(String.class)
                .optional();
        jdbc.sql("DELETE FROM hybrid_tokens WHERE token = :token").param("token", token).update();
        return user;
    }

    /** Remembers the redeemed user for this Vaadin session. */
    public static void signIn(String formsUser) {
        VaadinSession.getCurrent().setAttribute(ATTRIBUTE, formsUser);
    }

    public static Optional<String> current() {
        return Optional.ofNullable((String) VaadinSession.getCurrent().getAttribute(ATTRIBUTE));
    }
}
