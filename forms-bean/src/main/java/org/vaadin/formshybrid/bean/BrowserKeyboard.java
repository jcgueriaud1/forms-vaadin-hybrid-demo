package org.vaadin.formshybrid.bean;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;

import javax.swing.SwingUtilities;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefFocusHandlerAdapter;

import com.vaadin.swingkit.client.JVaadinPanel;

/**
 * Keeps the keyboard with the embedded browser while the user works in it
 * (issue #8).
 *
 * The browser is a native view: clicking it gives it the keyboard, but AWT's
 * focus owner stays on the last Forms item. Keys the page doesn't consume (Tab,
 * Enter, arrows, shortcuts) are then also delivered to that Forms item, so Down
 * in the Vaadin grid moved the Forms record. And on macOS, Cmd+C/V/X/A/Z only
 * work if the host implements them (there is no Chrome Edit menu).
 *
 * So while the browser has focus, key events for the Forms window are consumed,
 * and on macOS the edit shortcuts are sent to the browser's focused frame. The
 * browser gets the keyboard when the user presses the mouse inside the view,
 * and loses it on a press elsewhere in the window, when a Forms dialog opens,
 * or when Tab leaves the page.
 */
final class BrowserKeyboard {

    private static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");

    private final JVaadinPanel panel;
    private final CefBrowser browser;
    private volatile boolean browserFocused;
    private final KeyEventDispatcher keys = this::dispatch;
    private final AWTEventListener mouse = this::onMouse;

    private BrowserKeyboard(JVaadinPanel panel, CefBrowser browser) {
        this.panel = panel;
        this.browser = browser;
    }

    /** Installs the handling for {@code panel}, or returns null if its browser can't be reached. */
    static BrowserKeyboard install(JVaadinPanel panel) {
        CefBrowser browser = browserOf(panel);
        if (browser == null) {
            BeanLog.log("no CefBrowser found: keyboard handling for the view not installed");
            return null;
        }
        BrowserKeyboard keyboard = new BrowserKeyboard(panel, browser);
        browser.getClient().addFocusHandler(new CefFocusHandlerAdapter() {
            @Override
            public boolean onSetFocus(CefBrowser b, FocusSource source) {
                // A page load (or Vaadin navigation) must not take the keyboard
                // from the Forms item the user is in; a click still can.
                return source == FocusSource.FOCUS_SOURCE_NAVIGATION;
            }

            @Override
            public void onGotFocus(CefBrowser b) {
                keyboard.setBrowserFocused(true, "browser got focus");
            }

            @Override
            public void onTakeFocus(CefBrowser b, boolean next) {
                keyboard.setBrowserFocused(false, "Tab left the page");
            }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyboard.keys);
        Toolkit.getDefaultToolkit().addAWTEventListener(keyboard.mouse, AWTEvent.MOUSE_EVENT_MASK);
        return keyboard;
    }

    void uninstall() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        Toolkit.getDefaultToolkit().removeAWTEventListener(mouse);
        browser.getClient().removeFocusHandler();
    }

    /** A Forms dialog opened over the view: its keys belong to Forms. */
    void formsTookFocus() {
        setBrowserFocused(false, "a Forms dialog opened");
    }

    private void setBrowserFocused(boolean focused, String why) {
        if (browserFocused != focused) {
            browserFocused = focused;
            BeanLog.log("keyboard " + (focused ? "to the view" : "back to Forms") + ": " + why);
        }
    }

    private boolean dispatch(KeyEvent e) {
        if (!browserFocused || !inFormsWindow(e.getComponent())) {
            return false;
        }
        if (MAC && (e.getModifiersEx() & InputEvent.META_DOWN_MASK) != 0) {
            if (e.getKeyCode() == KeyEvent.VK_Q) {
                return false; // let Cmd+Q quit
            }
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                editShortcut(e);
            }
        }
        return true; // the browser already had the key: Forms must not act on it too
    }

    /** Sends a macOS edit shortcut to the browser's focused frame. */
    private void editShortcut(KeyEvent e) {
        CefFrame frame = browser.getFocusedFrame();
        if (frame == null) {
            return;
        }
        boolean shift = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_C: frame.copy(); break;
            case KeyEvent.VK_V: frame.paste(); break;
            case KeyEvent.VK_X: frame.cut(); break;
            case KeyEvent.VK_A: frame.selectAll(); break;
            case KeyEvent.VK_Z:
                if (shift) {
                    frame.redo();
                } else {
                    frame.undo();
                }
                break;
            default:
                break;
        }
    }

    private void onMouse(AWTEvent e) {
        // A press in the window is reported to AWT even when it lands on the
        // native browser, and not always on the panel's own components, so
        // decide by where on screen it landed.
        if (e.getID() != MouseEvent.MOUSE_PRESSED) {
            return;
        }
        MouseEvent m = (MouseEvent) e;
        if (!inFormsWindow(m.getComponent())) {
            return;
        }
        if (panel.isShowing()) {
            Point at = m.getLocationOnScreen();
            Rectangle view = new Rectangle(panel.getLocationOnScreen(), panel.getSize());
            if (view.contains(at)) {
                setBrowserFocused(true, "mouse pressed in the view");
                return;
            }
        }
        setBrowserFocused(false, "mouse pressed in Forms");
    }

    private boolean inFormsWindow(Component c) {
        Window ours = SwingUtilities.getWindowAncestor(panel);
        return c != null && ours != null && SwingUtilities.getWindowAncestor(c) == ours;
    }

    /**
     * swing-kit keeps its CefBrowser in a private field of JCefJVaadinPanel; the
     * panel it returns is a generated proxy subclass, so look up the hierarchy.
     * The bean and swing-kit share a class loader, so no --add-opens is needed.
     */
    private static CefBrowser browserOf(JVaadinPanel panel) {
        for (Class<?> c = panel.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("cefBrowser");
                f.setAccessible(true);
                return (CefBrowser) f.get(panel);
            } catch (NoSuchFieldException e) {
                // not declared here: try the superclass
            } catch (ReflectiveOperationException | RuntimeException e) {
                BeanLog.log("cannot reach swing-kit's CefBrowser", e);
                return null;
            }
        }
        BeanLog.log("swing-kit's panel has no cefBrowser field");
        return null;
    }
}
