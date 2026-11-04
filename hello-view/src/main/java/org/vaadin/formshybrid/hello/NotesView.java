package org.vaadin.formshybrid.hello;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.swingkit.core.VaadinSwingEventBuilder;
import com.vaadin.swingkit.server.Bridge;
import com.vaadin.swingkit.server.EventEmitterFactory;
import com.vaadin.swingkit.server.SwingVaadinServer;

import org.vaadin.formshybrid.contract.HelloCallable;
import org.vaadin.formshybrid.contract.HybridEvents;
import org.vaadin.formshybrid.contract.SignInCallable;

/**
 * Customer notes, embedded in the hybrid_notes form (#17). It shows who the
 * Forms user is and writes notes as that user, without trusting anything the
 * client sends: on attach it asks the form for a one-time token, and
 * {@link FormsIdentity} redeems it on the server.
 *
 * In a plain browser ({@code /notes?customer=103}) there is no token, so notes
 * can be read but not added.
 */
@Bridge
@Route("notes")
public class NotesView extends VerticalLayout implements HelloCallable, SignInCallable, BeforeEnterObserver {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CustomerRepository customers;
    private final NoteRepository notes;
    private final FormsIdentity identity;
    private final H3 title = new H3("Customer notes");
    private final Span user = new Span();
    private final Grid<Note> grid = new Grid<>(Note.class, false);
    private final TextField text = new TextField();
    private final Button add = new Button("Add note");
    private Customer customer;

    public NotesView(CustomerRepository customers, NoteRepository notes, FormsIdentity identity) {
        this.customers = customers;
        this.notes = notes;
        this.identity = identity;
        setSizeFull();
        setSpacing(false);

        title.getStyle().set("margin", "0");
        user.getStyle().set("font-weight", "bold");
        HorizontalLayout header = new HorizontalLayout(title, user);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.BASELINE);
        header.setFlexGrow(1, title);

        grid.addColumn(n -> WHEN.format(n.createdAt())).setHeader("When").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Note::author).setHeader("Author").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Note::note).setHeader("Note").setFlexGrow(1);

        text.setPlaceholder("New note");
        text.setMaxLength(400);
        text.setWidthFull();
        add.addThemeVariants(ButtonVariant.PRIMARY);
        add.addClickListener(e -> addNote());
        add.addClickShortcut(Key.ENTER);
        HorizontalLayout editor = new HorizontalLayout(text, add);
        editor.setWidthFull();
        editor.setFlexGrow(1, text);

        add(header, grid, editor);
        setFlexGrow(1, grid);
        updateUser();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (SwingVaadinServer.isSwingRendered()) {
            emit(new VaadinSwingEventBuilder(HybridEvents.READY));
            if (FormsIdentity.current().isEmpty()) {
                user.setText("Signing in…");
                emit(new VaadinSwingEventBuilder(HybridEvents.TOKEN_NEEDED));
            }
        }
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        event.getLocation().getQueryParameters().getSingleParameter("customer")
                .ifPresent(id -> showCustomer(id, ""));
    }

    @Override
    public void signIn(String token) {
        Optional<String> formsUser = identity.redeem(token);
        formsUser.ifPresent(FormsIdentity::signIn);
        updateUser();
        if (formsUser.isEmpty()) {
            user.setText("Sign-in refused: unknown, used or expired token");
        }
    }

    @Override
    public void showCustomer(String id, String name) {
        if (id == null || id.isBlank()) {
            return; // an empty Forms record: keep showing the last customer
        }
        customer = customers.findById(Long.parseLong(id)).orElse(null);
        title.setText(customer == null ? "Customer notes" : "Notes on " + customer.name());
        refresh();
    }

    private void refresh() {
        grid.setItems(customer == null ? List.of() : notes.findByCustomer(customer.id()));
        updateUser();
    }

    private void updateUser() {
        Optional<String> formsUser = FormsIdentity.current();
        user.setText(formsUser.map(u -> "Signed in as " + u)
                .orElse("Not signed in: open this view from Forms"));
        boolean canAdd = formsUser.isPresent() && customer != null;
        text.setEnabled(canAdd);
        add.setEnabled(canAdd);
    }

    private void addNote() {
        Optional<String> formsUser = FormsIdentity.current();
        if (formsUser.isEmpty() || customer == null || text.getValue().isBlank()) {
            return;
        }
        notes.add(formsUser.get(), customer.id(), text.getValue().strip());
        text.clear();
        refresh();
        Notification.show("Note saved as " + formsUser.get() + ".");
    }

    private void emit(VaadinSwingEventBuilder event) {
        try {
            EventEmitterFactory.newEventEmitter().emit(event.build());
        } catch (Exception e) {
            Notification.show("Could not reach the host form: " + e.getMessage());
        }
    }
}
