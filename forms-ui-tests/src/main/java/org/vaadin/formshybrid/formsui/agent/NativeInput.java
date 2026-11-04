package org.vaadin.formshybrid.formsui.agent;

import java.awt.AWTException;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Real mouse and keyboard input, through java.awt.Robot, for what only the OS
 * can do: move the native focus between Forms and the embedded browser (#29).
 * The posted events of {@link FormsAgent} never reach the native browser, and
 * the bean decides who has the keyboard from native mouse presses. Robot input
 * goes to the app in front, so each call first makes sure the Forms window is,
 * and it needs Accessibility permission on macOS (ADR-0004).
 */
final class NativeInput {

    /** Bean area as record=names names it: the embedded view. */
    private static final String VIEW = "CONTROL_VAADIN_VIEW_0";

    private NativeInput() {
    }

    /** Clicks the middle of a Forms item. Returns why it couldn't, or null. */
    static String clickItem(String name, int count) {
        Point point = FormsAgent.onEventThread(() -> {
            Component item = FormsAgent.find(name);
            if (item == null) {
                return null;
            }
            Point corner = item.getLocationOnScreen();
            return new Point(corner.x + item.getWidth() / 2, corner.y + item.getHeight() / 2);
        });
        return point == null ? "no item " + name + " on screen" : click(point, count);
    }

    /**
     * Clicks a point of the embedded view, given as "x,y" in the page's CSS
     * pixels, as Playwright's bounding boxes give them. At the browser's 100 %
     * zoom, a CSS pixel is a Java (macOS) point.
     */
    static String clickView(String xy, int count) {
        String[] parts = xy.split(",");
        double x = Double.parseDouble(parts[0]);
        double y = Double.parseDouble(parts[1]);
        Point point = FormsAgent.onEventThread(() -> {
            Component view = FormsAgent.find(VIEW);
            if (view == null) {
                return null;
            }
            Component browser = browserIn(view);
            Point corner = (browser != null ? browser : view).getLocationOnScreen();
            return new Point(corner.x + (int) Math.round(x), corner.y + (int) Math.round(y));
        });
        return point == null ? "no embedded view (" + VIEW + ") on screen" : click(point, count);
    }

    /**
     * Presses keys in order, each a {@code KeyEvent.VK_} name without "VK_",
     * optionally with modifiers: "UP", "META+A", "SHIFT+TAB". Returns why it
     * couldn't, or null.
     */
    static String keys(String keys) {
        List<int[]> strokes = new ArrayList<>();
        for (String stroke : keys.split(",")) {
            String[] parts = stroke.trim().split("\\+");
            int[] codes = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String key = switch (parts[i].toUpperCase()) {
                    case "CTRL" -> "CONTROL";
                    case "CMD" -> "META";
                    default -> parts[i].toUpperCase();
                };
                try {
                    codes[i] = KeyEvent.class.getField("VK_" + key).getInt(null);
                } catch (ReflectiveOperationException e) {
                    return "unknown key " + parts[i] + " in " + stroke;
                }
            }
            strokes.add(codes);
        }
        String inactive = activate();
        if (inactive != null) {
            return inactive;
        }
        Robot robot = robot();
        for (int[] codes : strokes) {
            for (int code : codes) {
                robot.keyPress(code);
            }
            for (int i = codes.length - 1; i >= 0; i--) {
                robot.keyRelease(codes[i]);
            }
        }
        robot.waitForIdle();
        return null;
    }

    private static String click(Point point, int count) {
        String inactive = activate();
        if (inactive != null) {
            return inactive;
        }
        Robot robot = robot();
        robot.mouseMove(point.x, point.y);
        for (int i = 0; i < count; i++) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        robot.waitForIdle();
        return null;
    }

    private static String activate() {
        return FormsAgent.activateNatively() ? null
                : "the Forms window isn't in front, so it can't take real input: give the app that runs the"
                        + " tests Accessibility permission, and keep other apps from taking the front";
    }

    /** swing-kit's JCEF browser component inside the bean area (org.cef.browser.CefBrowserWr). */
    private static Component browserIn(Component component) {
        if (component.isShowing() && component.getClass().getName().startsWith("org.cef.")) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = browserIn(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Robot robot() {
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(30);
            return robot;
        } catch (AWTException e) {
            throw new IllegalStateException("No java.awt.Robot: " + e, e);
        }
    }
}
