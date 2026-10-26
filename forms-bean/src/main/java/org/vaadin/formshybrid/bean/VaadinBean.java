package org.vaadin.formshybrid.bean;

import java.awt.AWTEvent;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.EventQueue;
import java.awt.event.HierarchyEvent;
import java.io.Serializable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import com.vaadin.swingkit.client.JVaadinPanel;
import com.vaadin.swingkit.client.SwingVaadinClient;
import com.vaadin.swingkit.core.VaadinSwingEvent;

import oracle.forms.handler.IHandler;
import oracle.forms.properties.ID;
import oracle.forms.ui.CustomEvent;
import oracle.forms.ui.FWindow;
import oracle.forms.ui.VBean;

import org.vaadin.formshybrid.contract.HelloCallable;
import org.vaadin.formshybrid.contract.HybridEvents;

/**
 * Forms bean area implementation that shows the embedded Vaadin view through
 * swing-kit's {@link JVaadinPanel}.
 *
 * <ul>
 * <li>Forms → Vaadin: {@code SET_CUSTOM_PROPERTY(bean, 1, 'CUSTOMER', 'id|name')}
 * calls {@link HelloCallable#showCustomer}.</li>
 * <li>Vaadin → Forms: the view's {@code greet} event raises the custom item event
 * {@code GREET}; the trigger reads the text with
 * {@code GET_CUSTOM_PROPERTY(bean, 1, 'GREET_MSG')}.</li>
 * <li>Vaadin → Forms: the view's {@code orderSaved} event raises
 * {@code ORDER_SAVED}; the trigger reads {@code ORDER_CUSTOMER_ID} and
 * {@code ORDERS_TOTAL}.</li>
 * </ul>
 *
 * The view URL defaults to {@value #DEFAULT_URL} and can be overridden with the
 * {@code -Dhybrid.url=...} JVM option.
 */
public class VaadinBean extends VBean {

    private static final ID CUSTOMER = ID.registerProperty("CUSTOMER");
    private static final ID GREET = ID.registerProperty("GREET");
    private static final ID GREET_MSG = ID.registerProperty("GREET_MSG");
    private static final ID ORDER_SAVED = ID.registerProperty("ORDER_SAVED");
    private static final ID ORDER_CUSTOMER_ID = ID.registerProperty("ORDER_CUSTOMER_ID");
    private static final ID ORDERS_TOTAL = ID.registerProperty("ORDERS_TOTAL");

    static final String DEFAULT_URL = "http://localhost:8080/";
    private static final int START_DELAY_MS = 1500;

    private static final String STATUS_CARD = "status";
    private static final String VIEW_CARD = "view";

    private final CardLayout cards = new CardLayout();
    private final JLabel status = new JLabel("Starting the Vaadin view…", SwingConstants.CENTER);
    // Calls into the view block until the browser answers, so keep them off the
    // Forms (AWT) thread, in order.
    private final ExecutorService calls = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hybrid-bean-calls");
        t.setDaemon(true);
        return t;
    });

    private IHandler handler;
    private boolean started;
    private volatile JVaadinPanel panel;
    private volatile boolean viewReady;
    private volatile String[] currentCustomer;
    private volatile String greeting = "";
    private volatile String orderCustomerId = "";
    private volatile String ordersTotal = "";
    private AWTEventListener dialogWatcher;

    @Override
    public void init(IHandler handler) {
        super.init(handler);
        this.handler = handler;
        BeanLog.captureUncaughtExceptions();
        BeanLog.log("init");
        // Cards switch by visibility only: the browser is never removed, which
        // would destroy its native window.
        setLayout(cards);
        status.setOpaque(true);
        status.setBackground(Color.WHITE);
        status.setForeground(Color.DARK_GRAY);
        add(status, STATUS_CARD);
        // On macOS, CefApp initialisation runs on the AWT thread and synchronously
        // needs the AppKit thread. If the Forms window is still being shown or
        // resized, AppKit is waiting on the AWT thread at the same time and the
        // client deadlocks on its splash screen. So start JCEF only once the bean
        // is on screen and the window has settled.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && !started) {
                started = true;
                Timer settle = new Timer(START_DELAY_MS, ev -> {
                    Thread starter = new Thread(this::startPanel, "hybrid-bean-start");
                    starter.setDaemon(true);
                    starter.start();
                });
                settle.setRepeats(false);
                settle.start();
            }
        });
    }

    /** JCEF start-up is slow (and downloads Chromium on first run): never on the Forms thread. */
    private void startPanel() {
        String url = System.getProperty("hybrid.url", DEFAULT_URL);
        try {
            BeanLog.log("building panel for " + url);
            JVaadinPanel p = SwingVaadinClient.getBuilder().build(url);
            p.addEventListener(HybridEvents.READY, e -> onViewReady());
            p.addEventListener(HybridEvents.GREET, this::onGreet);
            p.addEventListener(HybridEvents.ORDER_SAVED, this::onOrderSaved);
            panel = p;
            BeanLog.log("panel built, isReady=" + p.isReady());
            EventQueue.invokeLater(() -> {
                add(p, VIEW_CARD);
                cards.show(this, VIEW_CARD);
                validate();
                repaint();
            });
            pushCustomer();
            EventQueue.invokeLater(this::watchFormsDialogs);
        } catch (Throwable t) {
            BeanLog.log("panel build failed", t);
            EventQueue.invokeLater(() -> status.setText("Vaadin view unavailable: " + t));
        }
    }

    /**
     * Forms draws its alerts, LOVs and message boxes as lightweight windows
     * ({@link FWindow}) in the same desktop container as the form's own window.
     * The browser is a native component that always paints on top of them, so a
     * dialog over the bean area would be hidden while Forms waits for an answer.
     * While another visible Forms window overlaps the bean, the browser is
     * replaced by a placeholder (issue #6).
     */
    private void watchFormsDialogs() {
        FWindow own = ancestor(this, FWindow.class);
        Container desktop = own == null ? null : own.getParent();
        if (desktop == null) {
            BeanLog.log("no Forms window found: dialogs won't be watched");
            return;
        }
        dialogWatcher = e -> {
            Object source = e.getSource();
            if (source == desktop || source instanceof FWindow) {
                updateForDialogs(own, desktop);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(dialogWatcher,
                AWTEvent.CONTAINER_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK);
    }

    private void updateForDialogs(FWindow own, Container desktop) {
        JVaadinPanel p = panel;
        if (p == null || !isShowing()) {
            return;
        }
        Rectangle bean = javax.swing.SwingUtilities.convertRectangle(getParent(), getBounds(), desktop);
        boolean covered = false;
        for (Component c : desktop.getComponents()) {
            if (c != own && c instanceof FWindow && c.isVisible() && c.getBounds().intersects(bean)) {
                covered = true;
                break;
            }
        }
        if (covered == p.isVisible()) {
            BeanLog.log(covered ? "Forms dialog over the view: hiding the browser" : "Forms dialog closed: showing the browser");
            status.setText("Answer the Forms dialog to continue.");
            cards.show(this, covered ? STATUS_CARD : VIEW_CARD);
            validate();
            desktop.repaint();
        }
    }

    private static <T> T ancestor(Component c, Class<T> type) {
        for (Component p = c; p != null; p = p.getParent()) {
            if (type.isInstance(p)) {
                return type.cast(p);
            }
        }
        return null;
    }

    @Override
    public boolean setProperty(ID id, Object value) {
        BeanLog.log("setProperty " + id + " = " + value);
        if (id == CUSTOMER) {
            String[] parts = String.valueOf(value).split("\\|", 2);
            currentCustomer = new String[] { parts[0], parts.length > 1 ? parts[1] : "" };
            pushCustomer();
            return true;
        }
        return super.setProperty(id, value);
    }

    @Override
    public Object getProperty(ID id) {
        if (id == GREET_MSG) {
            return greeting;
        }
        if (id == ORDER_CUSTOMER_ID) {
            return orderCustomerId;
        }
        if (id == ORDERS_TOTAL) {
            return ordersTotal;
        }
        return super.getProperty(id);
    }

    /** The form usually sends the first customer before the view has loaded: replay it on ready. */
    private void onViewReady() {
        BeanLog.log("view ready event");
        viewReady = true;
        pushCustomer();
    }

    private void pushCustomer() {
        String[] customer = currentCustomer;
        JVaadinPanel p = panel;
        // The view's ready event can fire while build() is still loading the page,
        // before our listener exists, so swing-kit's own load state counts too.
        if (customer == null || p == null || !(viewReady || p.isReady())) {
            BeanLog.log("pushCustomer deferred: customer=" + (customer != null) + " panel="
                    + (p != null) + " viewReady=" + viewReady + " isReady=" + (p != null && p.isReady()));
            return;
        }
        calls.submit(() -> {
            try {
                BeanLog.log("calling showCustomer(" + customer[0] + ", " + customer[1] + ")");
                p.as(HelloCallable.class).showCustomer(customer[0], customer[1]);
                BeanLog.log("showCustomer returned");
            } catch (Throwable t) {
                BeanLog.log("showCustomer failed", t);
            }
        });
    }

    private void onGreet(VaadinSwingEvent event) {
        BeanLog.log("greet event " + event.getParams());
        Serializable message = event.getParams().get(HybridEvents.MESSAGE);
        greeting = message == null ? "" : message.toString();
        EventQueue.invokeLater(() -> dispatchCustomEvent(new CustomEvent(handler, GREET)));
    }

    private void onOrderSaved(VaadinSwingEvent event) {
        BeanLog.log("orderSaved event " + event.getParams());
        orderCustomerId = String.valueOf(event.getParams().get(HybridEvents.CUSTOMER_ID));
        ordersTotal = String.valueOf(event.getParams().get(HybridEvents.TOTAL));
        EventQueue.invokeLater(() -> dispatchCustomEvent(new CustomEvent(handler, ORDER_SAVED)));
    }

    @Override
    public void destroy() {
        if (dialogWatcher != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(dialogWatcher);
        }
        calls.shutdownNow();
        super.destroy();
    }
}
