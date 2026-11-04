package org.vaadin.formshybrid.formsui;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The running Forms client, driven through the agent's control port. Items are
 * named as {@code record=names} names them: {@code BLOCK_ITEM_<visible row>}.
 * The row is the slot on screen, not the record number, so tests find a record's
 * row by its key with {@link #rowOf}.
 */
public final class FormsClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;

    FormsClient(int controlPort) {
        this.base = "http://127.0.0.1:" + controlPort;
    }

    boolean isUp() {
        try {
            return send("GET", "/ping").statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether a Forms window is active. Forms ignores clicks until its window has
     * been active once; after that, the agent's clicks work even in the background.
     */
    boolean isActive() {
        String[] ownerWindow = call("GET", "/focus").split("\t", -1);
        return !ownerWindow[1].isEmpty();
    }

    /** Every named item on screen and its value, in screen order. */
    public Map<String, String> items() {
        Map<String, String> items = new LinkedHashMap<>();
        for (String line : call("GET", "/items").split("\n")) {
            if (!line.isEmpty()) {
                String[] nameValue = line.split("\t", 2);
                items.put(nameValue[0], nameValue.length > 1 ? nameValue[1] : "");
            }
        }
        return items;
    }

    /** The value of one item on screen, for example {@code item("CUSTOMERS_ORDERS_TOTAL", 0)}. */
    public String item(String blockItem, int row) {
        return call("GET", "/item?name=" + encode(blockItem + "_" + row));
    }

    /** Clicks an item, as a user would: Forms navigates there and fires its triggers. */
    public void click(String blockItem, int row) {
        call("POST", "/click?name=" + encode(blockItem + "_" + row));
    }

    /** The visible row that shows the record whose key item has this value. */
    public int rowOf(String keyItem, String key) {
        String prefix = keyItem + "_";
        for (Map.Entry<String, String> item : items().entrySet()) {
            String name = item.getKey();
            if (name.startsWith(prefix) && name.substring(prefix.length()).matches("\\d+")
                    && key.equals(item.getValue())) {
                return Integer.parseInt(name.substring(prefix.length()));
            }
        }
        throw new AssertionError("No " + keyItem + " = " + key + " on screen: " + items());
    }

    private String call(String method, String path) {
        try {
            HttpResponse<String> response = send(method, path);
            if (response.statusCode() != 200) {
                throw new AssertionError("Forms agent: " + method + " " + path + " → " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new AssertionError("Forms agent not reachable: " + e, e);
        }
    }

    private HttpResponse<String> send(String method, String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
