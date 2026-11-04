package org.vaadin.formshybrid.formsui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import com.microsoft.playwright.assertions.LocatorAssertions;

/**
 * The view's server can be down, slow or restarted (#13): Forms keeps working
 * meanwhile, and the view comes back on the customer Forms is on, without
 * restarting the Forms client.
 */
@ExtendWith(HybridScreenExtension.class)
class ViewServerDownIT {

    @AfterEach
    void viewServerRunsAgain(HybridScreen screen) throws Exception {
        screen.startView();
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void formsKeepsWorkingWhileTheViewServerIsDownAndTheViewRecovers(HybridScreen screen) throws Exception {
        FormsClient forms = screen.forms();
        screen.stopView();

        // Forms still navigates: each click must land, so no freeze and no
        // alert taking the focus. The bean probes the server every 5 s, so stay
        // down long enough for it to notice.
        for (String customer : new String[] {"108", "106", "102"}) {
            forms.click("CUSTOMERS_NAME", forms.rowOf("CUSTOMERS_ID", customer));
            Thread.sleep(3000);
        }

        screen.startView();

        // The bean reloads the view when the server is back (within its 5 s
        // probe), and replays the customer Forms is on.
        assertThat(screen.view().locator("h3")).containsText("(#102)",
                new LocatorAssertions.ContainsTextOptions().setTimeout(60_000));
    }
}
