package org.vaadin.formshybrid.hello;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;

/**
 * Edits one order: date, product, quantity and status. The dialog renders inside
 * the embedded browser, so it can't be larger than the bean area in the form.
 */
public class OrderDialog extends Dialog {

    /** What the user entered; the caller saves it. */
    public record Edit(Order order, LocalDate orderDate, Product product, int quantity, String status) {
    }

    private final DatePicker orderDate = new DatePicker("Order date");
    private final ComboBox<Product> product = new ComboBox<>("Product");
    private final IntegerField quantity = new IntegerField("Quantity");
    private final Select<String> status = new Select<>();

    /** @param onSave saves the edit; returns whether the dialog should close */
    public OrderDialog(Order order, List<Product> products, Predicate<Edit> onSave) {
        setHeaderTitle("Order #" + order.id());
        setWidth("min(420px, 95vw)");
        setMaxHeight("95vh");

        orderDate.setValue(order.orderDate());
        orderDate.setRequired(true);

        product.setItems(products);
        product.setItemLabelGenerator(p -> String.format(Locale.US, "%s (%.2f)", p.name(), p.price()));
        products.stream().filter(p -> p.id() == order.productId()).findFirst()
                .ifPresent(product::setValue);
        product.setRequired(true);

        quantity.setValue(order.quantity());
        quantity.setMin(1);
        quantity.setStepButtonsVisible(true);
        quantity.setRequiredIndicatorVisible(true);

        status.setLabel("Status");
        status.setItems("NEW", "PAID", "SHIPPED", "CANCELLED");
        status.setValue(order.status());

        FormLayout form = new FormLayout(orderDate, product, quantity, status);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("340px", 2));
        add(form);

        Button save = new Button("Save", e -> {
            if (isValid() && onSave.test(new Edit(order, orderDate.getValue(), product.getValue(),
                    quantity.getValue(), status.getValue()))) {
                close();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        getFooter().add(new Button("Cancel", e -> close()), save);
    }

    private boolean isValid() {
        orderDate.setInvalid(orderDate.getValue() == null);
        product.setInvalid(product.getValue() == null);
        quantity.setInvalid(quantity.getValue() == null || quantity.getValue() < 1);
        return !orderDate.isInvalid() && !product.isInvalid() && !quantity.isInvalid();
    }
}
