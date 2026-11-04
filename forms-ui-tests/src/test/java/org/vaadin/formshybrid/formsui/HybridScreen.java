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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

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
    private Process formsClient;
    private Playwright playwright;
    private FormsClient forms;
    private Page view;

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
        return forms;
    }

    /** The embedded view's page, inside the Forms client. */
    public Page view() {
        return view;
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

        // Before anything connects: the reset kills the HYBRID sessions.
        run(List.of("./setup-db.sh"), repo, "setup-db.log", Duration.ofMinutes(3));

        int viewPort = freePort();
        helloView = start(List.of("mvn", "-q", "-B", "spring-boot:run",
                "-Dspring-boot.run.arguments=--server.port=" + viewPort), repo.resolve("hello-view"), "hello-view.log");
        String viewUrl = "http://localhost:" + viewPort + "/";
        waitFor("hello-view at " + viewUrl, helloView, "hello-view.log", Duration.ofMinutes(4), () -> answers(viewUrl));

        int controlPort = freePort();
        int cdpPort = freePort();
        ProcessBuilder client = new ProcessBuilder("./run-forms-client.sh").directory(repo.toFile());
        String javaOpts = "-javaagent:" + agentJar.toAbsolutePath() + "=control=" + controlPort + ",cdp=" + cdpPort
                + " -Dhybrid.url=" + viewUrl;
        client.environment().merge("JAVA_OPTS", javaOpts, (existing, added) -> existing + " " + added);
        client.environment().put("HYBRID_FORMS_PARAMS", "&record=names");
        formsClient = start(client, "forms-client.log");
        forms = new FormsClient(controlPort);
        waitFor("the agent's control port", formsClient, "forms-client.log", Duration.ofMinutes(2), forms::isUp);
        waitFor("the embedded browser's CDP port", formsClient, "forms-client.log", Duration.ofMinutes(2),
                () -> answers("http://127.0.0.1:" + cdpPort + "/json/version"));

        playwright = Playwright.create();
        Browser browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + cdpPort);
        waitFor("the embedded view's page", formsClient, "forms-client.log", Duration.ofMinutes(1),
                () -> browser.contexts().stream().flatMap(c -> c.pages().stream())
                        .anyMatch(p -> p.url().startsWith(viewUrl)));
        view = browser.contexts().stream().flatMap(c -> c.pages().stream())
                .filter(p -> p.url().startsWith(viewUrl)).findFirst().orElseThrow();
        // Ready once Forms has pushed its first customer: the bean and the form's
        // triggers are up, so a click now isn't lost to a form still starting.
        waitFor("the first customer pushed by Forms", formsClient, "forms-client.log", Duration.ofMinutes(1),
                () -> view.locator("h3").textContent().matches(".*\\(#\\d+\\)"));
        // Forms ignores clicks until its window has been active once, and macOS
        // doesn't let an app started in the background activate itself. The agent
        // clicks its title bar, which needs Accessibility permission; without it,
        // wait for the user to click the window.
        if (!forms.activate()) {
            System.out.println("Oracle Forms UI tests: the Forms window isn't active. Click it once, or give the app"
                    + " that runs the tests (Terminal, your IDE) Accessibility permission in System Settings.");
            waitFor("active Forms window (click it once, or grant Accessibility permission: see the README)",
                    formsClient, "forms-client.log", Duration.ofMinutes(1), forms::isActive);
        }
    }

    @Override
    public void close() {
        if (playwright != null) {
            playwright.close(); // over CDP this only disconnects
        }
        stop(formsClient);
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
