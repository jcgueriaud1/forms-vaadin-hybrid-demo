package org.vaadin.formshybrid.hello;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.swingkit.core.VaadinSwingEventBuilder;
import com.vaadin.swingkit.server.Bridge;
import com.vaadin.swingkit.server.EventEmitterFactory;
import com.vaadin.swingkit.server.SwingVaadinServer;

import org.vaadin.formshybrid.contract.HelloCallable;
import org.vaadin.formshybrid.contract.HybridEvents;

/**
 * The embedded view: greets the customer the host form is on, reading its
 * details from the same database, and can ask the host form to show a message.
 */
// @Bridge makes swing-kit add the <swing-bridge> element that receives Swing → Vaadin calls.
@Bridge
@Route("")
public class HelloView extends VerticalLayout implements HelloCallable {

    private final CustomerRepository customers;
    private final H2 greeting = new H2("Waiting for the host form…");
    private final Paragraph details = new Paragraph();
    private final Button greet = new Button("Ask Forms to say hi");
    private String customerName;

    public HelloView(CustomerRepository customers) {
        this.customers = customers;
        greet.setEnabled(false);
        greet.addClickListener(e -> emit(HybridEvents.GREET,
                "Hi from Vaadin, " + customerName + "!"));
        add(greeting, details, greet);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (SwingVaadinServer.isSwingRendered()) {
            emit(HybridEvents.READY, null);
        } else {
            add(new Paragraph("Open this view inside the Forms host form to talk to it."));
        }
    }

    @Override
    public void showCustomer(String id, String name) {
        // Forms sends the id and name; the rest comes from the shared database.
        Customer customer = customers.findById(Long.parseLong(id))
                .orElse(new Customer(Long.parseLong(id), name, null, null));
        customerName = customer.name();
        greeting.setText("Hello, " + customer.name() + " (#" + customer.id() + ")");
        details.setText(customer.city() == null
                ? "Not found in the database."
                : customer.city() + ", " + customer.country() + " (read by Vaadin from Oracle)");
        greet.setEnabled(true);
    }

    private void emit(String type, String message) {
        VaadinSwingEventBuilder event = new VaadinSwingEventBuilder(type);
        if (message != null) {
            event.addParam(HybridEvents.MESSAGE, message);
        }
        try {
            EventEmitterFactory.newEventEmitter().emit(event.build());
        } catch (Exception e) {
            greeting.setText("Could not reach the host form: " + e.getMessage());
        }
    }
}
