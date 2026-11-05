package org.vaadin.formshybrid.formsui;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * One hybrid screen for the whole test run (ADR-0004, decisions 4 and 5): the
 * demo schema reset, {@code hello-view} on a free port, and the Forms client
 * started with the agent and {@code record=names}, with Playwright attached to
 * its embedded browser over CDP. The Docker environment, the form and the bean
 * are assumed to be running and deployed; see {@link #unavailableReason()}.
 *
 * <p>Environment, as for the demo's scripts: {@code HYBRID_FORMS_URL},
 * {@code HYBRID_JDBC_URL}, {@code HYBRID_DB_USER}, {@code HYBRID_DB_PASSWORD}.
 * Logs of the started processes go to {@code target/forms-ui-tests/}.
 */
public final class HybridScreen implements AutoCloseable {

    private static final String FORMS_URL = env("HYBRID_FORMS_URL", "http://localhost:7777/forms");
    private static final String JDBC_URL = env("HYBRID_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/XEPDB1");
    private static final String DB_USER = env("HYBRID_DB_USER", "hybrid");
    private static final String DB_PASSWORD = env("HYBRID_DB_PASSWORD", "hybrid");

    private final Path repo = Path.of(System.getProperty("hybrid.repo.dir", "..")).toAbsolutePath().normalize();
    private final Path agentJar = Path.of(System.getProperty("forms.agent.jar", "target/forms-ui-tests.jar"));
    private final Path logs = Path.of("target", "forms-ui-tests");

    private Process helloView;
    private int viewPort;
    private Playwright playwright;
    private Client main;

    static HybridScreen start() throws Exception {
        HybridScreen screen = new HybridScreen();
        try {
            screen.startAll();
        } catch (Exception | Error e) {
            screen.close();
            throw e;
        }
        return screen;
    }

    /** The Forms client, through the agent. */
    public FormsClient forms() {
        return main.forms();
    }

    /** The embedded view's page, inside the Forms client. */
    public Page view() {
        return main.view();
    }

    /**
     * A Forms client started for one test, next to the shared one: on another
     * config, logged in as {@code user} through the Forms logon dialog. Close it
     * when done.
     */
    public Client startClient(String config, String user, String password) throws Exception {
        return startClient(config, user, password, "forms-client-" + config + "-" + user + ".log",
                view -> view.locator("h3").textContent().startsWith("Notes on"));
    }

    /** A running Forms client: the agent, the embedded view, and the process. */
    public static final class Client implements AutoCloseable {

        private final Process process;
        private final FormsClient forms;
        private final Browser browser;
        private final Page view;

        private Client(Process process, FormsClient forms, Browser browser, Page view) {
            this.process = process;
            this.forms = forms;
            this.browser = browser;
            this.view = view;
        }

        /** The Forms client, through the agent. */
        public FormsClient forms() {
            return forms;
        }

        /** The embedded view's page, inside the Forms client. */
        public Page view() {
            return view;
        }

        @Override
        public void close() {
            browser.close(); // over CDP this only disconnects
            stop(process);
        }
    }

    /** Stops the view's server (hello-view), as when it crashes or is redeployed. */
    public void stopView() {
        stop(helloView);
        helloView = null;
    }

    /** Starts the view's server on its port, unless it's running; waits until it answers. */
    public void startView() throws IOException, InterruptedException {
        if (helloView != null && helloView.isAlive()) {
            return;
        }
        // Append, so the log keeps the server's earlier lives too.
        File log = logs.resolve("hello-view.log").toFile();
        helloView = new ProcessBuilder("mvn", "-q", "-B", "spring-boot:run",
                // No dev tools: Copilot's overlay covers the view's corner, where
                // the notes view has its Add note button, and users never see it.
                "-Dspring-boot.run.arguments=--server.port=" + viewPort + " --vaadin.devmode.devTools.enabled=false")
                .directory(repo.resolve("hello-view").toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start();
        String url = "http://localhost:" + viewPort + "/";
        waitFor("hello-view at " + url, helloView, "hello-view.log", Duration.ofMinutes(4), () -> answers(url));
    }

    /** A single number from the demo schema, for example a count to compare the screen with. */
    public long queryLong(String sql, Object... parameters) {
        return ((Number) queryRow(sql, parameters)[0]).longValue();
    }

    /**
     * One row from the demo schema, through a new session: it sees only what
     * was committed, as the other side of the hybrid screen would.
     */
    public Object[] queryRow(String sql, Object... parameters) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
                PreparedStatement statement = prepare(connection, sql, parameters);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new AssertionError("No row: " + sql);
            }
            Object[] row = new Object[result.getMetaData().getColumnCount()];
            for (int i = 0; i < row.length; i++) {
                row[i] = result.getObject(i + 1);
            }
            return row;
        } catch (SQLException e) {
            throw new AssertionError("Query failed: " + sql, e);
        }
    }

    /** Changes the demo schema and commits, for example to put back what a test changed. */
    public int update(String sql, Object... parameters) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
                PreparedStatement statement = prepare(connection, sql, parameters)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new AssertionError("Update failed: " + sql, e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
        return statement;
    }

    /** Why the tests can't run here, if the Docker environment isn't up. */
    static Optional<String> unavailableReason() {
        URI forms = URI.create(FORMS_URL);
        if (!reachable(forms.getHost(), forms.getPort() == -1 ? 80 : forms.getPort())) {
            return Optional.of("Forms isn't reachable at " + FORMS_URL + ": start the Docker environment first");
        }
        DriverManager.setLoginTimeout(5);
        try (Connection ignored = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD)) {
            return Optional.empty();
        } catch (SQLException e) {
            return Optional.of("The demo database isn't reachable at " + JDBC_URL + ": " + e.getMessage());
        }
    }

    private void startAll() throws Exception {
        Files.createDirectories(logs);
        if (!Files.isRegularFile(agentJar)) {
            throw new IllegalStateException("No agent jar at " + agentJar + ": run the tests with mvn verify");
        }

        // A locked screen stops the embedded browser and real input, and macOS
        // locks it when the display sleeps: keep it awake while the tests run.
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            if (screenLocked()) {
                throw new AssertionError("The screen is locked: unlock it, then run the tests again");
            }
            new ProcessBuilder("caffeinate", "-d", "-w", String.valueOf(ProcessHandle.current().pid()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        }

        // Before anything connects: the reset kills the HYBRID sessions.
        run(List.of("./setup-db.sh"), repo, "setup-db.log", Duration.ofMinutes(3));

        viewPort = freePort();
        Files.deleteIfExists(logs.resolve("hello-view.log"));
        startView();
        String viewUrl = "http://localhost:" + viewPort + "/";

        playwright = Playwright.create();
        // Ready once Forms has pushed its first customer: the bean and the form's
        // triggers are up, so a click now isn't lost to a form still starting.
        main = startClient("hybrid", null, null, "forms-client.log",
                view -> view.locator("h3").textContent().matches(".*\\(#\\d+\\)"));
    }

    private Client startClient(String config, String user, String password, String log, Predicate<Page> ready)
            throws Exception {
        String viewUrl = "http://localhost:" + viewPort + "/";
        int controlPort = freePort();
        int cdpPort = freePort();
        ProcessBuilder builder = new ProcessBuilder("./run-forms-client.sh").directory(repo.toFile());
        String javaOpts = "-javaagent:" + agentJar.toAbsolutePath() + "=control=" + controlPort + ",cdp=" + cdpPort
                + " -Dhybrid.url=" + viewUrl;
        builder.environment().merge("JAVA_OPTS", javaOpts, (existing, added) -> existing + " " + added);
        builder.environment().put("HYBRID_FORMS_PARAMS", "&record=names");
        builder.environment().put("HYBRID_FORMS_CONFIG", config);
        Process process = start(builder, log);
        try {
            FormsClient forms = new FormsClient(controlPort);
            waitFor("the agent's control port", process, log, Duration.ofMinutes(2), forms::isUp);
            if (user != null) {
                waitFor("the Forms logon dialog, and logging in as " + user, process, log, Duration.ofMinutes(1),
                        () -> forms.logon(user, password));
                waitFor("the form to open after logging in as " + user, process, log,
                        Duration.ofMinutes(1), () -> !forms.items().isEmpty());
            }
            waitFor("the embedded browser's CDP port", process, log, Duration.ofMinutes(2),
                    () -> answers("http://127.0.0.1:" + cdpPort + "/json/version"));
            // Attach only once the browser has the view's page: Playwright doesn't
            // reliably pick up a page created after it attached.
            waitFor("the embedded view's page (" + viewUrl + ")", process, log, Duration.ofMinutes(1),
                    () -> pageUrls(cdpPort).stream().anyMatch(url -> url.startsWith(viewUrl)));

            Browser browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + cdpPort);
            Page view = browser.contexts().stream().flatMap(c -> c.pages().stream())
                    .filter(p -> p.url().startsWith(viewUrl)).findFirst()
                    .orElseThrow(() -> new AssertionError("Playwright doesn't see the view's page; the browser has "
                            + pageUrls(cdpPort)));
            waitFor("the view to show the form's first record", process, log, Duration.ofMinutes(1),
                    () -> ready.test(view));
            // Forms ignores clicks until its window has been active once, and macOS
            // doesn't let an app started in the background activate itself. The agent
            // clicks its title bar, which needs Accessibility permission; without it,
            // wait for the user to click the window.
            if (!forms.activate()) {
                System.out.println("Oracle Forms UI tests: the Forms window isn't active. Click it once, or give the"
                        + " app that runs the tests (Terminal, your IDE) Accessibility permission in System Settings.");
                waitFor("active Forms window (click it once, or grant Accessibility permission: see the README)",
                        process, log, Duration.ofMinutes(1), forms::isActive);
            }
            return new Client(process, forms, browser, view);
        } catch (Exception | Error e) {
            stop(process);
            throw e;
        }
    }

    @Override
    public void close() {
        if (main != null) {
            main.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        stop(helloView);
    }

    private Process start(List<String> command, Path directory, String log) throws IOException {
        return start(new ProcessBuilder(command).directory(directory.toFile()), log);
    }

    private Process start(ProcessBuilder builder, String log) throws IOException {
        File logFile = logs.resolve(log).toFile();
        return builder.redirectErrorStream(true).redirectOutput(logFile).start();
    }

    private void run(List<String> command, Path directory, String log, Duration timeout) throws Exception {
        Process process = start(command, directory, log);
        if (!process.waitFor(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
            stop(process);
            throw new AssertionError(command + " didn't finish in " + timeout + tail(log));
        }
        if (process.exitValue() != 0) {
            throw new AssertionError(command + " failed with exit code " + process.exitValue() + tail(log));
        }
    }

    private void waitFor(String what, Process process, String log, Duration timeout, BooleanSupplier ready)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!ready.getAsBoolean()) {
            if (!process.isAlive()) {
                throw new AssertionError("Stopped before " + what + " was ready" + tail(log));
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No " + what + " after " + timeout + tail(log));
            }
            Thread.sleep(500);
        }
    }

    /** The end of a log, plus the agent's CDP failure if there was one: the likely cause. */
    private String tail(String log) {
        try {
            List<String> lines = Files.readAllLines(logs.resolve(log));
            String cdpFailure = lines.stream().filter(l -> l.contains("[cdp-agent] failed")).findFirst()
                    .map(l -> "\n" + l + " (see ADR-0004: swing-kit or jcefmaven changed?)").orElse("");
            return cdpFailure + "\n--- last lines of " + logs.resolve(log) + ":\n"
                    + String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
        } catch (IOException e) {
            return "";
        }
    }

    private static void stop(Process process) {
        if (process == null) {
            return;
        }
        // run-forms-client.sh execs the client JVM, whose JCEF helpers are its children;
        // mvn spring-boot:run forks the view's JVM.
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** macOS: whether the session's screen is locked (ioreg's CGSSessionScreenIsLocked). */
    private static boolean screenLocked() {
        try {
            Process ioreg = new ProcessBuilder("ioreg", "-n", "Root", "-d1").redirectErrorStream(true).start();
            String out = new String(ioreg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return out.contains("\"CGSSessionScreenIsLocked\"=Yes");
        } catch (IOException e) {
            return false;
        }
    }

    /** The URLs of the browser's pages, from its CDP target list. */
    private static List<String> pageUrls(int cdpPort) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + cdpPort + "/json/list")
                    .toURL().openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(5000);
            String json = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<String> urls = new ArrayList<>();
            Matcher pages = Pattern.compile("\"type\":\\s*\"page\"[^}]*?\"url\":\\s*\"([^\"]*)\"|\"url\":\\s*\"([^\"]*)\"[^}]*?\"type\":\\s*\"page\"")
                    .matcher(json);
            while (pages.find()) {
                urls.add(pages.group(1) != null ? pages.group(1) : pages.group(2));
            }
            return urls;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean answers(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(5000);
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
