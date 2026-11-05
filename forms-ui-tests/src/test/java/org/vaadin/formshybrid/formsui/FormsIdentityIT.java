package org.vaadin.formshybrid.formsui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.vaadin.formshybrid.formsui.Eventually.assertEventually;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.vaadin.addons.dramafinder.element.ButtonElement;
import org.vaadin.addons.dramafinder.element.TextFieldElement;

import com.microsoft.playwright.Page;

/**
 * The view learns who the Forms user is from a one-time token, never from a
 * user name sent by the client (#17, ADR-0003). Logged in to the notes form as
 * alice or bob, the view signs in as that user, a note added in the view is
 * stored as theirs, and the token is used up. Each user gets their own client.
 */
@ExtendWith(HybridScreenExtension.class)
class FormsIdentityIT {

    private final String note = "Oracle Forms UI test " + UUID.randomUUID();

    @AfterEach
    void removeNote(HybridScreen screen) {
        screen.update("DELETE FROM customer_notes WHERE note = ?", note);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "bob"})
    void viewSignsInAsTheFormsUser(String user, HybridScreen screen) throws Exception {
        String formsUser = user.toUpperCase();
        try (HybridScreen.Client client = screen.startClient("hybrid-notes", user, user)) {
            Page view = client.view();
            assertEquals(formsUser, client.forms().item("CONTROL_FORMS_USER", 0), "user shown in Forms");
            assertThat(view.getByText("Signed in as ")).hasText("Signed in as " + formsUser);

            TextFieldElement.getByLabel(view, "New note").setValue(note);
            ButtonElement.getByText(view, "Add note").click();

            assertEventually(1L, () -> screen.queryLong(
                    "SELECT COUNT(*) FROM customer_notes WHERE note = ? AND author = ?", note, formsUser),
                    "the note stored with " + formsUser + " as author");
            assertEquals(0L, screen.queryLong("SELECT COUNT(*) FROM hybrid_tokens WHERE forms_user = ?", formsUser),
                    "unused tokens of " + formsUser);
        }
    }
}
