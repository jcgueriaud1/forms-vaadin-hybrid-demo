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
import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import com.vaadin.swingkit.client.JVaadinPanel;
import com.vaadin.swingkit.client.SwingVaadinClient;
import com.vaadin.swingkit.core.VaadinSwingEvent;

import org.cef.browser.CefBrowser;

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
 *
 * The view server may be down, slow or restarted (issue #13); Forms must keep
 * working regardless. The bean checks every {@value #PROBE_SECONDS} s that the
 * server accepts connections, shows a placeholder while it doesn't, and reloads
 * the view when it's back (the ready event then replays the current customer).
 * A view that doesn't load within {@value #LOAD_TIMEOUT_SECONDS} s (an error
 * page, a server still starting) counts as unavailable too, so it is retried.
 * Calls time out after {@value #CALL_TIMEOUT_SECONDS} s, and only the latest
 * customer is ever pushed, so a slow view never builds up a queue.
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
    static final int PROBE_SECONDS = 5;
    static final int CALL_TIMEOUT_SECONDS = 10;
    static final int LOAD_TIMEOUT_SECONDS = 15;
    // swing-kit's event for a page that failed to load
    private static final String EXCEPTION_EVENT = "exception";
    private static final int CONNECT_TIMEOUT_MS = 2000;

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

    // Checks the view server off the Forms thread.
    private final ScheduledExecutorService probe = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hybrid-bean-probe");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pushQueued = new AtomicBoolean();
    private final AtomicInteger loads = new AtomicInteger();

    private IHandler handler;
    private String url;
    private boolean started;
    private volatile JVaadinPanel panel;
    private volatile boolean viewReady;
    // false while the server is unreachable or the view stopped answering
    private volatile boolean available = true;
    // a Forms dialog is over the bean (AWT thread only)
    private boolean covered;
    private volatile String[] currentCustomer;
    private volatile String greeting = "";
    private volatile String orderCustomerId = "";
    private volatile String ordersTotal = "";
    private AWTEventListener dialogWatcher;
    private BrowserKeyboard keyboard;

    @Override
    public void init(IHandler handler) {
        super.init(handler);
        this.handler = handler;
        BeanLog.configure();
        BeanLog.captureUncaughtExceptions();
        BeanLog.debug("init");
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
        url = System.getProperty("hybrid.url", DEFAULT_URL);
        try {
            SwingVaadinClient.getConfiguration().setTimeout(CALL_TIMEOUT_SECONDS);
            BeanLog.debug("building panel for " + url);
            JVaadinPanel p = SwingVaadinClient.getBuilder().build(url);
            // swing-kit's own handler answers a load error with a modal message
            // box (which would block Forms) or its "Unreachable" page; the bean
            // shows its placeholder and retries instead.
            p.removeDefaultExceptionHandler();
            p.addEventListener(EXCEPTION_EVENT, this::onLoadError);
            p.addEventListener(HybridEvents.READY, e -> onViewReady());
            p.addEventListener(HybridEvents.GREET, this::onGreet);
            p.addEventListener(HybridEvents.ORDER_SAVED, this::onOrderSaved);
            panel = p;
            BeanLog.debug("panel built, isReady=" + p.isReady());
            // The page may have attached (and sent ready) before the listener
            // existed; swing-kit's ready flag covers that case.
            if (p.isReady()) {
                onViewReady();
            }
            EventQueue.invokeLater(() -> {
                add(p, VIEW_CARD);
                showCard();
            });
            pushCustomer();
            expectLoaded();
            probe.scheduleWithFixedDelay(this::checkServer, 0, PROBE_SECONDS, TimeUnit.SECONDS);
            EventQueue.invokeLater(this::watchFormsDialogs);
            EventQueue.invokeLater(() -> keyboard = BrowserKeyboard.install(p));
        } catch (Throwable t) {
            BeanLog.warn("panel build failed", t);
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
            BeanLog.warn("no Forms window found: dialogs won't be watched");
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
        boolean now = false;
        for (Component c : desktop.getComponents()) {
            if (c != own && c instanceof FWindow && c.isVisible() && c.getBounds().intersects(bean)) {
                now = true;
                break;
            }
        }
        if (now != covered) {
            covered = now;
            BeanLog.debug(covered ? "Forms dialog over the view: hiding the browser" : "Forms dialog closed: showing the browser");
            if (covered && keyboard != null) {
                keyboard.formsTookFocus();
            }
            showCard();
            desktop.repaint();
        }
    }

    /**
     * The browser is shown only while the view is available and no Forms dialog
     * is over it; otherwise the placeholder says why. AWT thread only.
     */
    private void showCard() {
        if (panel == null) {
            return;
        }
        if (covered) {
            status.setText("Answer the Forms dialog to continue.");
        } else if (!available) {
            status.setText("<html><center>The Vaadin view isn't available.<br>"
                    + "Forms keeps working; the view comes back by itself.</center></html>");
            if (keyboard != null) {
                keyboard.formsTookFocus();
            }
        }
        cards.show(this, covered || !available ? STATUS_CARD : VIEW_CARD);
        validate();
        repaint();
    }

    /** Runs every few seconds on the probe thread. */
    private void checkServer() {
        boolean reachable = serverAcceptsConnections();
        if (!reachable && available) {
            setAvailable(false, "server not reachable");
        } else if (reachable && !available) {
            // A new page and Vaadin session: its ready event replays the customer.
            BeanLog.debug("server reachable again: reloading the view");
            viewReady = false;
            setAvailable(true, "server reachable again");
            EventQueue.invokeLater(this::loadView);
            expectLoaded();
        }
    }

    /**
     * Navigates to the view URL rather than calling the panel's reload(), which
     * reloads whatever page is showing, possibly an error page. AWT thread.
     */
    private void loadView() {
        CefBrowser browser = BrowserKeyboard.browserOf(panel);
        if (browser != null) {
            browser.loadURL(url);
        } else {
            panel.reload();
        }
    }

    /** A page load failed (server down, connection reset…): retry it later. */
    private void onLoadError(VaadinSwingEvent event) {
        Object content = event.getParams().get("content");
        // ERR_ABORTED: a load replaced by another one, e.g. our own retry
        if (String.valueOf(content).contains("ERR_ABORTED")) {
            return;
        }
        setAvailable(false, String.valueOf(content));
    }

    /** If the page just requested hasn't sent its ready event in time, retry it. */
    private void expectLoaded() {
        int load = loads.incrementAndGet();
        probe.schedule(() -> {
            if (load == loads.get() && available && !viewReady) {
                setAvailable(false, "the view didn't load in " + LOAD_TIMEOUT_SECONDS + " s");
            }
        }, LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * A plain TCP connect: cheap, and unlike an HTTP request to the view it
     * doesn't create a Vaadin session every few seconds.
     */
    private boolean serverAcceptsConnections() {
        try (Socket socket = new Socket()) {
            URI uri = URI.create(url);
            int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
            socket.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private void setAvailable(boolean value, String why) {
        if (available != value) {
            available = value;
            BeanLog.warn("Vaadin view " + (value ? "available" : "unavailable") + ": " + why);
            EventQueue.invokeLater(this::showCard);
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
        if (id == CUSTOMER) {
            BeanLog.debug("setProperty CUSTOMER = " + value);
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
        BeanLog.debug("view ready event");
        viewReady = true;
        pushCustomer();
    }

    private void pushCustomer() {
        String[] customer = currentCustomer;
        JVaadinPanel p = panel;
        if (customer == null || p == null || !viewReady) {
            BeanLog.debug("pushCustomer deferred: customer=" + (customer != null) + " panel="
                    + (p != null) + " viewReady=" + viewReady);
            return;
        }
        if (!available) {
            BeanLog.debug("pushCustomer deferred: view unavailable");
            return;
        }
        // Only the latest customer matters: if a push is already queued, it will
        // pick this one up, so a slow view never builds up a queue of calls.
        if (!pushQueued.compareAndSet(false, true)) {
            return;
        }
        calls.submit(() -> {
            pushQueued.set(false);
            if (!available || !viewReady) {
                return; // the next ready event replays the customer
            }
            String[] latest = currentCustomer;
            try {
                BeanLog.debug("calling showCustomer(" + latest[0] + ", " + latest[1] + ")");
                p.as(HelloCallable.class).showCustomer(latest[0], latest[1]);
                BeanLog.debug("showCustomer returned");
            } catch (Throwable t) {
                // Typically a timeout: the server is up but the page isn't
                // answering (an expired session, a broken page). Reload it.
                BeanLog.warn("showCustomer failed", t);
                setAvailable(false, "the view didn't answer");
            }
        });
    }

    private void onGreet(VaadinSwingEvent event) {
        BeanLog.debug("greet event " + event.getParams());
        Serializable message = event.getParams().get(HybridEvents.MESSAGE);
        greeting = message == null ? "" : message.toString();
        EventQueue.invokeLater(() -> dispatchCustomEvent(new CustomEvent(handler, GREET)));
    }

    private void onOrderSaved(VaadinSwingEvent event) {
        BeanLog.debug("orderSaved event " + event.getParams());
        orderCustomerId = String.valueOf(event.getParams().get(HybridEvents.CUSTOMER_ID));
        ordersTotal = String.valueOf(event.getParams().get(HybridEvents.TOTAL));
        EventQueue.invokeLater(() -> dispatchCustomEvent(new CustomEvent(handler, ORDER_SAVED)));
    }

    @Override
    public void destroy() {
        if (dialogWatcher != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(dialogWatcher);
        }
        if (keyboard != null) {
            keyboard.uninstall();
        }
        calls.shutdownNow();
        probe.shutdownNow();
        super.destroy();
    }
}
