package org.vaadin.formshybrid.formsui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.vaadin.addons.dramafinder.element.GridElement;

import com.microsoft.playwright.Page;

/** Forms → Vaadin: the embedded view follows the customer the Forms user moves to. */
@ExtendWith(HybridScreenExtension.class)
class CustomerNavigationIT {

    @Test
    void viewShowsTheCustomerFormsMovesTo(HybridScreen screen) {
        FormsClient forms = screen.forms();
        Page view = screen.view();

        forms.click("CUSTOMERS_NAME", forms.rowOf("CUSTOMERS_ID", "108"));

        assertThat(view.locator("h3")).hasText("Hello, Copper Kettle Foods (#108)");
        long orders = screen.queryLong("SELECT COUNT(*) FROM orders WHERE customer_id = ?", 108);
        view.waitForCondition(() -> GridElement.get(view).getTotalRowCount() == orders);
        assertEquals(orders, GridElement.get(view).getTotalRowCount());
    }
}
