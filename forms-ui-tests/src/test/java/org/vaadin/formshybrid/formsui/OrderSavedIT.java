package org.vaadin.formshybrid.formsui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.vaadin.formshybrid.formsui.Eventually.assertEventually;

import java.math.BigDecimal;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.vaadin.addons.dramafinder.element.ButtonElement;
import org.vaadin.addons.dramafinder.element.GridElement;
import org.vaadin.addons.dramafinder.element.IntegerFieldElement;

import com.microsoft.playwright.Page;

/**
 * Vaadin → Forms (ADR-0003): saving an order in the view commits it, and Forms
 * shows the customer's new orders total without treating its record as
 * changed. Customer 108 is in the second row, so this also finds a record that
 * isn't at the top.
 */
@ExtendWith(HybridScreenExtension.class)
class OrderSavedIT {

    private static final long CUSTOMER = 108;
    private static final long ORDER = 1011;

    private Object[] original;

    @BeforeEach
    void rememberOrder(HybridScreen screen) {
        original = screen.queryRow("SELECT order_date, product_id, quantity, status FROM orders WHERE id = ?", ORDER);
    }

    @AfterEach
    void restoreOrder(HybridScreen screen) {
        Page view = screen.view();
        if (view.locator("vaadin-dialog[opened]").count() > 0) {
            ButtonElement.getByText(view, "Cancel").click();
        }
        screen.update("UPDATE orders SET order_date = ?, product_id = ?, quantity = ?, status = ?,"
                + " version = version + 1 WHERE id = ?", original[0], original[1], original[2], original[3], ORDER);
    }

    @Test
    void savingAnOrderInVaadinUpdatesTheOrdersTotalInForms(HybridScreen screen) {
        FormsClient forms = screen.forms();
        Page view = screen.view();
        String firstCustomer = forms.item("CUSTOMERS_ID", 0);
        int row = forms.rowOf("CUSTOMERS_ID", String.valueOf(CUSTOMER));
        forms.click("CUSTOMERS_NAME", row);
        assertThat(view.locator("h3")).containsText("(#" + CUSTOMER + ")");

        GridElement.get(view).findRow(0).orElseThrow().getCell(0).getCellContentLocator().dblclick();
        IntegerFieldElement.getByLabel(view, "Quantity").setValue("5");
        ButtonElement.getByText(view, "Save").click();

        // Committed first: nothing is pushed to Forms before the commit.
        assertEventually(5L, () -> screen.queryLong("SELECT quantity FROM orders WHERE id = ?", ORDER),
                "quantity of order " + ORDER + " in the database");
        BigDecimal total = (BigDecimal) screen.queryRow(
                "SELECT total FROM order_totals WHERE customer_id = ?", CUSTOMER)[0];
        assertEventually(String.format(Locale.US, "%,.2f", total),
                () -> forms.item("CUSTOMERS_ORDERS_TOTAL", row).trim(),
                "Orders total of customer " + CUSTOMER + " in Forms");

        // Execute Query re-queries the block. Had the total left the record
        // changed, Forms would first ask whether to save it; without changes it
        // re-queries and goes back to the first record (an alert would keep the
        // focus, and the view on this customer).
        forms.press("F11", "CTRL"); // Execute Query
        assertEventually(firstCustomer, () -> forms.item("CUSTOMERS_ID", 0), "first record after Execute Query");
        assertEventually("0", () -> forms.focusedItem().replaceAll(".*_", ""),
                "row of the item focused after Execute Query (" + forms.focusedItem() + ")");
        assertThat(view.locator("h3")).containsText("(#" + firstCustomer + ")");
    }
}
