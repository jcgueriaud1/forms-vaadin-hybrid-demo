package org.vaadin.formshybrid.formsui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.vaadin.formshybrid.formsui.Eventually.assertEventually;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.vaadin.addons.dramafinder.element.ButtonElement;

import com.microsoft.playwright.Page;

/** Vaadin → Forms, the simplest event: the view asks the host form to show a greeting. */
@ExtendWith(HybridScreenExtension.class)
class GreetingIT {

    private static final String CUSTOMER = "106";

    @Test
    void greetingFromTheViewShowsInTheFormsStatusBar(HybridScreen screen) {
        FormsClient forms = screen.forms();
        Page view = screen.view();
        int row = forms.rowOf("CUSTOMERS_ID", CUSTOMER);
        forms.click("CUSTOMERS_NAME", row);
        assertThat(view.locator("h3")).containsText("(#" + CUSTOMER + ")");

        ButtonElement.getByText(view, "Ask Forms to say hi").click();

        assertEventually("Hi from Vaadin, " + forms.item("CUSTOMERS_NAME", row) + "!", forms::message,
                "Forms message line");
    }
}
