package org.vaadin.formshybrid.formsui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.vaadin.formshybrid.formsui.Eventually.assertEventually;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.vaadin.addons.dramafinder.element.ButtonElement;
import org.vaadin.addons.dramafinder.element.GridElement;
import org.vaadin.addons.dramafinder.element.IntegerFieldElement;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;

/**
 * Keyboard and focus between the two halves (#8): keys reach the side that has
 * the focus, and only that side. The bean decides who has the keyboard from
 * real mouse presses, and the original bug was a key the page didn't consume
 * also reaching Forms (Down in the Vaadin grid moved the Forms record). So this
 * test uses real input (java.awt.Robot through the agent) for every click and
 * key: Playwright's input goes straight into the browser and would skip the
 * handover. It uses arrow keys, which don't depend on the keyboard layout.
 */
@ExtendWith(HybridScreenExtension.class)
class KeyboardFocusIT {

    private static final String CUSTOMER = "108";

    @AfterEach
    void closeDialog(HybridScreen screen) {
        Page view = screen.view();
        if (view.locator("vaadin-dialog[opened]").count() > 0) {
            ButtonElement.getByText(view, "Cancel").click();
        }
    }

    @Test
    void keysGoToTheSideThatHasTheFocus(HybridScreen screen) {
        FormsClient forms = screen.forms();
        Page view = screen.view();
        int row = forms.rowOf("CUSTOMERS_ID", CUSTOMER);
        forms.nativeClick("CUSTOMERS_NAME", row);
        assertThat(view.locator("h3")).containsText("(#" + CUSTOMER + ")");

        // Into the view: a real click hands the keyboard to the browser. Robot
        // can't double-click on macOS (it doesn't set the click count), so the
        // order dialog is opened through Playwright; the handover already happened.
        Locator order = GridElement.get(view).findRow(0).orElseThrow().getCell(0).getCellContentLocator();
        nativeClick(forms, order);
        order.dblclick();
        IntegerFieldElement quantity = IntegerFieldElement.getByLabel(view, "Quantity");
        String before = quantity.getValue();
        nativeClick(forms, quantity.getLocator().locator("input"));
        forms.nativeKeys("UP");

        // The field got the key; Forms didn't (Up there is Previous Record).
        assertEventually(String.valueOf(Integer.parseInt(before) + 1), quantity::getValue, "Quantity after Up");
        assertEquals("CUSTOMERS_NAME_" + row, forms.focusedItem(), "Forms item after Up in the view");
        assertThat(view.locator("h3")).containsText("(#" + CUSTOMER + ")");

        // Back to Forms: after a click on a Forms item, keys go to Forms again.
        nativeClick(forms, ButtonElement.getByText(view, "Cancel").getLocator());
        assertThat(view.locator("vaadin-dialog[opened]")).hasCount(0);
        forms.nativeClick("CUSTOMERS_NAME", row);
        forms.nativeKeys("DOWN");
        assertEventually("CUSTOMERS_NAME_" + (row + 1), forms::focusedItem, "Forms item after Down in Forms");
        assertThat(view.locator("h3")).containsText("(#" + forms.item("CUSTOMERS_ID", row + 1) + ")");
    }

    /** A real click in the middle of an element of the view. */
    private static void nativeClick(FormsClient forms, Locator element) {
        element.waitFor();
        BoundingBox box = element.boundingBox();
        forms.nativeClickInView(box.x + box.width / 2, box.y + box.height / 2, 1);
    }
}
