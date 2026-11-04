package org.vaadin.formshybrid.hello;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.swingkit.core.VaadinSwingEventBuilder;
import com.vaadin.swingkit.server.EventEmitterFactory;
import com.vaadin.swingkit.server.SwingVaadinServer;

import org.vaadin.formshybrid.contract.HybridEvents;
import org.vaadin.formshybrid.hello.CustomerRepository.CustomerTotal;
import org.vaadin.formshybrid.hello.OrderRepository.OrderChangedException;
import org.vaadin.formshybrid.hello.OrderRepository.OrderLockedException;

/**
 * A whole screen migrated to Vaadin: the customer list and the selected
 * customer's orders, edited in the same dialog as the hybrid demo's. The
 * hybrid_orders form only hosts it (its bean area fills the window), so unlike
 * {@link HelloView} it takes no calls from Forms and sends it nothing but the
 * ready event.
 *
 * Outside Forms, open it as {@code /orders}.
 */
@Route("orders")
public class OrdersView extends VerticalLayout {

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final Grid<CustomerTotal> customerGrid = new Grid<>(CustomerTotal.class, false);
    private final H3 ordersTitle = new H3("Orders");
    private final Grid<Order> orderGrid = new Grid<>(Order.class, false);
    private final Span hint = new Span("Select a customer to see their orders.");

    public OrdersView(CustomerRepository customers, OrderRepository orders) {
        this.customers = customers;
        this.orders = orders;
        setSizeFull();
        setSpacing(false);

        H3 customersTitle = new H3("Customers");
        customersTitle.getStyle().set("margin", "0");
        ordersTitle.getStyle().set("margin", "var(--vaadin-gap-m, 0.5em) 0 0");

        customerGrid.addColumn(CustomerTotal::id).setHeader("Id").setAutoWidth(true).setFlexGrow(0);
        customerGrid.addColumn(CustomerTotal::name).setHeader("Customer").setFlexGrow(1);
        customerGrid.addColumn(CustomerTotal::city).setHeader("City").setAutoWidth(true);
        customerGrid.addColumn(CustomerTotal::country).setHeader("Country").setAutoWidth(true);
        customerGrid.addColumn(c -> money(c.ordersTotal())).setHeader("Orders total").setAutoWidth(true);
        customerGrid.asSingleSelect().addValueChangeListener(e -> showOrders());

        orderGrid.addColumn(Order::id).setHeader("Order").setAutoWidth(true).setFlexGrow(0);
        orderGrid.addColumn(Order::orderDate).setHeader("Date").setAutoWidth(true);
        orderGrid.addColumn(Order::productName).setHeader("Product").setFlexGrow(1);
        orderGrid.addColumn(Order::quantity).setHeader("Qty").setAutoWidth(true);
        orderGrid.addColumn(o -> money(o.amount())).setHeader("Amount").setAutoWidth(true);
        orderGrid.addColumn(Order::status).setHeader("Status").setAutoWidth(true);
        orderGrid.addItemDoubleClickListener(e -> edit(e.getItem()));

        add(customersTitle, customerGrid, ordersTitle, orderGrid, hint);
        setFlexGrow(1, customerGrid, orderGrid);

        List<CustomerTotal> all = customers.findAllWithTotals();
        customerGrid.setItems(all);
        all.stream().findFirst().ifPresent(customerGrid::select);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        // The bean shows the page once it's ready, and reloads it if it never is.
        if (SwingVaadinServer.isSwingRendered()) {
            try {
                EventEmitterFactory.newEventEmitter().emit(new VaadinSwingEventBuilder(HybridEvents.READY).build());
            } catch (Exception e) {
                Notification.show("Could not reach the host form: " + e.getMessage());
            }
        }
    }

    private void showOrders() {
        CustomerTotal customer = customerGrid.asSingleSelect().getValue();
        if (customer == null) {
            ordersTitle.setText("Orders");
            orderGrid.setItems();
            hint.setText("Select a customer to see their orders.");
            return;
        }
        var customerOrders = orders.findByCustomer(customer.id());
        ordersTitle.setText("Orders of " + customer.name());
        orderGrid.setItems(customerOrders);
        hint.setText(customerOrders.isEmpty() ? "No orders." : "Double-click an order to edit it.");
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
            Notification.show("Order #" + order.id() + " saved.");
            return refreshAfterSave();
        } catch (OrderLockedException e) {
            Notification.show("Order #" + order.id() + " is being edited in Forms. Try again in a moment.");
            return false;
        } catch (OrderChangedException e) {
            Notification.show("Order #" + order.id() + " changed since you opened it. It has been reloaded.");
            return refreshAfterSave();
        }
    }

    /** Reloads the totals and the orders, keeping the selected customer. */
    private boolean refreshAfterSave() {
        long selectedId = customerGrid.asSingleSelect().getValue().id();
        // setItems clears the selection, so selecting again always reloads the orders
        List<CustomerTotal> reloaded = customers.findAllWithTotals();
        customerGrid.setItems(reloaded);
        reloaded.stream().filter(c -> c.id() == selectedId).findFirst().ifPresent(customerGrid::select);
        return true;
    }

    private static String money(BigDecimal amount) {
        return String.format(Locale.US, "%,.2f", amount);
    }
}
