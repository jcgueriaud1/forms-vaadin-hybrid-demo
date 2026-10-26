package org.vaadin.formshybrid.hello;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.swingkit.core.VaadinSwingEventBuilder;
import com.vaadin.swingkit.server.Bridge;
import com.vaadin.swingkit.server.EventEmitterFactory;
import com.vaadin.swingkit.server.SwingVaadinServer;

import org.vaadin.formshybrid.contract.HelloCallable;
import org.vaadin.formshybrid.contract.HybridEvents;
import org.vaadin.formshybrid.hello.OrderRepository.OrderChangedException;
import org.vaadin.formshybrid.hello.OrderRepository.OrderLockedException;

/**
 * The embedded view. It shows the orders of the customer the host form is on,
 * read from the same database, and lets the user edit them in a dialog. The view
 * owns ORDERS (ADR-0003): after each committed change it tells the host form the
 * customer's new orders total. It can also ask the host form to show a greeting.
 *
 * Outside Forms, open it as {@code /?customer=103} to try it in a normal browser.
 */
// @Bridge makes swing-kit add the <swing-bridge> element that receives Swing → Vaadin calls.
@Bridge
@Route("")
public class HelloView extends VerticalLayout implements HelloCallable, BeforeEnterObserver {

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final H3 greeting = new H3("Waiting for the host form…");
    private final Span details = new Span();
    private final Button greet = new Button("Ask Forms to say hi");
    private final Grid<Order> grid = new Grid<>(Order.class, false);
    private final Span hint = new Span("Double-click an order to edit it.");
    private Customer customer;

    public HelloView(CustomerRepository customers, OrderRepository orders) {
        this.customers = customers;
        this.orders = orders;
        setSizeFull();
        setPadding(true);
        setSpacing(false);

        greet.setEnabled(false);
        greet.addThemeVariants(ButtonVariant.SMALL);
        greet.addClickListener(e -> emit(new VaadinSwingEventBuilder(HybridEvents.GREET)
                .addParam(HybridEvents.MESSAGE, "Hi from Vaadin, " + customer.name() + "!")));
        greeting.getStyle().set("margin", "0");
        HorizontalLayout header = new HorizontalLayout(greeting, greet);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setFlexGrow(1, greeting);

        grid.addColumn(Order::orderDate).setHeader("Date").setAutoWidth(true);
        grid.addColumn(Order::productName).setHeader("Product").setFlexGrow(1);
        grid.addColumn(Order::quantity).setHeader("Qty").setAutoWidth(true);
        grid.addColumn(o -> money(o.amount())).setHeader("Amount").setAutoWidth(true);
        grid.addColumn(Order::status).setHeader("Status").setAutoWidth(true);
        grid.addItemDoubleClickListener(e -> edit(e.getItem()));
        grid.setVisible(false);
        hint.setVisible(false);

        add(header, details, grid, hint);
        setFlexGrow(1, grid);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (SwingVaadinServer.isSwingRendered()) {
            emit(new VaadinSwingEventBuilder(HybridEvents.READY));
        } else if (customer == null) {
            details.setText("Open this view inside the Forms host form, or as /?customer=103.");
        }
    }

    /** Outside Forms, a customer can be chosen with ?customer=<id>, to try the view in a browser. */
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        event.getLocation().getQueryParameters().getSingleParameter("customer")
                .ifPresent(id -> showCustomer(id, ""));
    }

    @Override
    public void showCustomer(String id, String name) {
        if (id == null || id.isBlank()) {
            return; // an empty Forms record: keep showing the last customer
        }
        // Forms sends the id and name; the rest comes from the shared database.
        customer = customers.findById(Long.parseLong(id))
                .orElse(new Customer(Long.parseLong(id), name, null, null));
        greeting.setText("Hello, " + customer.name() + " (#" + customer.id() + ")");
        greet.setEnabled(true);
        refresh();
    }

    private void refresh() {
        if (customer.city() == null) {
            details.setText("Not found in the database.");
            grid.setVisible(false);
            hint.setVisible(false);
            return;
        }
        List<Order> customerOrders = orders.findByCustomer(customer.id());
        details.setText(customer.city() + ", " + customer.country() + " · " + customerOrders.size()
                + " orders, total " + money(orders.totalFor(customer.id()))
                + " (read by Vaadin from Oracle)");
        grid.setItems(customerOrders);
        grid.setVisible(true);
        hint.setVisible(!customerOrders.isEmpty());
    }

    private void edit(Order order) {
        new OrderDialog(order, orders.findProducts(), this::save).open();
    }

    /** @return whether the dialog can close */
    private boolean save(OrderDialog.Edit edit) {
        Order order = edit.order();
        try {
            orders.save(order.id(), order.version(), edit.orderDate(), edit.product().id(),
                    edit.quantity(), edit.status());
        } catch (OrderLockedException e) {
            Notification.show("Order #" + order.id() + " is being edited in Forms. Try again in a moment.");
            return false;
        } catch (OrderChangedException e) {
            Notification.show("Order #" + order.id() + " changed since you opened it. It has been reloaded.");
            refresh();
            return true;
        }
        // Committed: now tell the host form, never before (ADR-0003).
        refresh();
        BigDecimal total = orders.totalFor(customer.id()).setScale(2, RoundingMode.HALF_UP);
        if (SwingVaadinServer.isSwingRendered()) {
            emit(new VaadinSwingEventBuilder(HybridEvents.ORDER_SAVED)
                    .addParam(HybridEvents.CUSTOMER_ID, String.valueOf(customer.id()))
                    .addParam(HybridEvents.TOTAL, total.toPlainString()));
        }
        Notification.show("Order #" + order.id() + " saved.");
        return true;
    }

    private static String money(BigDecimal amount) {
        return String.format(Locale.US, "%,.2f", amount);
    }

    private void emit(VaadinSwingEventBuilder event) {
        try {
            EventEmitterFactory.newEventEmitter().emit(event.build());
        } catch (Exception e) {
            Notification.show("Could not reach the host form: " + e.getMessage());
        }
    }
}
