package org.vaadin.formshybrid.formsui.agent;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The Oracle Forms UI tests' agent inside the Forms client JVM (ADR-0004). It
 * offers a local control port to read and click Forms items, found by the names
 * {@code record=names} gives them ({@code BLOCK_ITEM_<visible row>}), and opens
 * the embedded browser's CDP port.
 *
 * <pre>-javaagent:forms-ui-tests.jar=control=&lt;port&gt;,cdp=&lt;port&gt;</pre>
 *
 * Control port, plain text over HTTP on 127.0.0.1:
 * <ul>
 * <li>{@code GET /ping}: "ok"</li>
 * <li>{@code GET /items}: one "name&lt;tab&gt;value" line per named item on screen</li>
 * <li>{@code GET /item?name=CUSTOMERS_ID_0}: the item's value, 404 if not on screen</li>
 * <li>{@code POST /click?name=CUSTOMERS_NAME_2}: clicks the item, as a user would</li>
 * </ul>
 */
public final class FormsAgent {

    /** A Forms item as record=names names it: BLOCK_ITEM_row. */
    private static final Pattern ITEM_NAME = Pattern.compile("[A-Z][A-Z0-9_$#]*_\\d+");

    private FormsAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) throws IOException {
        Map<String, String> options = parse(args);
        if (options.containsKey("cdp")) {
            CdpSettings.install(instrumentation, Integer.parseInt(options.get("cdp")));
        }
        if (options.containsKey("control")) {
            startControlServer(Integer.parseInt(options.get("control")));
        }
    }

    private static Map<String, String> parse(String args) {
        Map<String, String> options = new HashMap<>();
        if (args != null) {
            for (String option : args.split(",")) {
                String[] keyValue = option.split("=", 2);
                options.put(keyValue[0].trim(), keyValue.length > 1 ? keyValue[1].trim() : "");
            }
        }
        return options;
    }

    private static void startControlServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/ping", exchange -> reply(exchange, 200, "ok"));
        server.createContext("/items", exchange -> reply(exchange, 200, onEventThread(FormsAgent::items)));
        server.createContext("/focus", exchange -> reply(exchange, 200, onEventThread(FormsAgent::focus)));
        server.createContext("/item", exchange -> {
            String name = query(exchange).get("name");
            String value = onEventThread(() -> {
                Component item = find(name);
                return item == null ? null : text(item);
            });
            reply(exchange, value == null ? 404 : 200, value == null ? "no item " + name + " on screen" : value);
        });
        server.createContext("/click", exchange -> {
            String name = query(exchange).get("name");
            Window window = onEventThread(() -> {
                Component item = find(name);
                return item == null ? null : SwingUtilities.getWindowAncestor(item);
            });
            if (window == null) {
                reply(exchange, 404, "no item " + name + " on screen");
                return;
            }
            if (!activate(window)) {
                reply(exchange, 409, "the Forms window didn't become active, so it can't take a click");
                return;
            }
            boolean clicked = onEventThread(() -> {
                Component item = find(name);
                if (item == null) {
                    return false;
                }
                click(item);
                return true;
            });
            reply(exchange, clicked ? 200 : 404, clicked ? "ok" : "no item " + name + " on screen");
        });
        server.setExecutor(null);
        server.start();
        System.err.println("[forms-agent] control port " + port);
    }

    /**
     * Makes the window active for Java, and waits until it is. Forms ignores clicks
     * in an inactive window: the item can't take the focus, so the cursor doesn't
     * move. The OS won't activate it: macOS doesn't let an app started in the
     * background bring itself to the front. So the agent posts the window events
     * AWT gets when the user activates a window. Forms only follows Java's focus
     * state, and the focus of whatever else you are using is left alone.
     */
    private static boolean activate(Window window) {
        if (onEventThread(window::isActive)) {
            return true;
        }
        EventQueue queue = window.getToolkit().getSystemEventQueue();
        queue.postEvent(new WindowEvent(window, WindowEvent.WINDOW_ACTIVATED));
        queue.postEvent(new WindowEvent(window, WindowEvent.WINDOW_GAINED_FOCUS));
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (onEventThread(window::isActive)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** The component that has the keyboard, and the active window: "name&lt;tab&gt;window title". */
    private static String focus() {
        KeyboardFocusManager focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        Component owner = focus.getFocusOwner();
        Window active = focus.getActiveWindow();
        return (owner == null ? "" : owner.getName()) + '\t'
                + (active instanceof Frame frame ? frame.getTitle() : active == null ? "" : active.getName());
    }

    private static String items() {
        StringBuilder lines = new StringBuilder();
        for (Window window : Window.getWindows()) {
            collect(window, lines);
        }
        return lines.toString();
    }

    private static void collect(Component component, StringBuilder lines) {
        if (!component.isShowing()) {
            return;
        }
        String name = component.getName();
        if (name != null && ITEM_NAME.matcher(name).matches()) {
            lines.append(name).append('\t').append(text(component)).append('\n');
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, lines);
            }
        }
    }

    private static Component find(String name) {
        for (Window window : Window.getWindows()) {
            Component found = find(window, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Component find(Component component, String name) {
        if (!component.isShowing()) {
            return null;
        }
        if (name.equals(component.getName())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = find(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Forms' text items (oracle.forms.ui.VTextField) have getText(), but share no AWT type that has it. */
    private static String text(Component component) {
        try {
            Object value = component.getClass().getMethod("getText").invoke(component);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    /**
     * Clicks the middle of the item. The events go through the AWT event queue of
     * the item's window, the way the toolkit delivers a user's click, without
     * moving the real mouse pointer (java.awt.Robot would, and needs extra
     * permissions on macOS).
     */
    private static void click(Component item) {
        Window window = SwingUtilities.getWindowAncestor(item);
        Point point = SwingUtilities.convertPoint(item, item.getWidth() / 2, item.getHeight() / 2, window);
        long now = System.currentTimeMillis();
        EventQueue queue = item.getToolkit().getSystemEventQueue();
        // A press asks the OS to focus the window too, which macOS refuses for a
        // window in the background. Forms navigates when its item gains the focus,
        // so give it the focus event a click in the front window would.
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != item) {
            queue.postEvent(new FocusEvent(item, FocusEvent.FOCUS_GAINED, false, owner));
        }
        queue.postEvent(mouse(window, MouseEvent.MOUSE_PRESSED, now, point, InputEvent.BUTTON1_DOWN_MASK));
        queue.postEvent(mouse(window, MouseEvent.MOUSE_RELEASED, now + 1, point, 0));
        queue.postEvent(mouse(window, MouseEvent.MOUSE_CLICKED, now + 2, point, 0));
    }

    private static MouseEvent mouse(Window window, int id, long when, Point point, int modifiers) {
        return new MouseEvent(window, id, when, modifiers, point.x, point.y, 1, false, MouseEvent.BUTTON1);
    }

    private interface Task<T> {
        T run();
    }

    private static <T> T onEventThread(Task<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            EventQueue.invokeAndWait(() -> result.set(task.run()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result.get();
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> parameters = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] keyValue = pair.split("=", 2);
                parameters.put(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
                        keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "");
            }
        }
        return parameters;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
