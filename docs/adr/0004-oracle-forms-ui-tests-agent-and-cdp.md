---
status: accepted
---

# Oracle Forms UI tests drive the Forms client through an in-JVM agent and the embedded view over CDP

An **Oracle Forms UI test** drives the running Forms client, and the embedded
view inside it, the way a user would. It then asserts what crosses the bean and
what was committed. Nothing off the shelf drives both halves of one hybrid
screen: the Forms client draws its own Java components, and the view runs in a
JCEF browser inside that same JVM. So the test reaches each half through the
door that half already has, without adding test code to the bean or the form.
Verified on 2026-11-04 on macOS x86_64 against the real client.

## Decisions

1. **Forms side: a java agent inside the client JVM.** The harness adds
   `-javaagent:…` through `JAVA_OPTS` (`run-forms-client.sh` passes it on). The
   agent offers a local control port with commands to navigate, type, press keys
   and read items or the status bar. It finds components by name and posts the
   events a user's input produces to the client's AWT event queue, so Forms
   triggers fire as they would for a user. It doesn't use `java.awt.Robot`, which
   would move the real mouse and needs Accessibility permission on macOS.
   On macOS, Forms ignores clicks until its window has been active once, and
   macOS 15 doesn't let an app started in the background activate itself
   (`Desktop.requestForeground` and System Events were both ignored). The harness
   therefore waits until the window has been active, and asks for one click if it
   isn't. After that, the agent re-activates the window for Java (window and
   focus events) before each click, so you can switch to other apps.
2. **Component names come from `record=names`.** The harness adds
   `&record=names` through `HYBRID_FORMS_PARAMS`; the config already passes
   `record=%record%`. The Forms components are then named
   `BLOCK_ITEM_<row>`, for example `CUSTOMERS_ORDERS_TOTAL_0`. Without it they
   are anonymous (`VTextField0`). The suffix is the **visible row slot**, not the
   record number, so the agent maps a record key, such as customer 103, to its
   slot through `CUSTOMERS_ID_<n>`.
3. **Vaadin side: Playwright over CDP into the embedded browser.** swing-kit
   3.0.1 doesn't let the app set `CefSettings`. So when swing-kit's
   `JCefVaadinPanelBuilder` loads, the agent sets `remote_debugging_port` and a
   per-process `root_cache_path` on jcefmaven's private static
   `CefAppBuilder.DEFAULT_CEF_SETTINGS` by reflection, and each new builder
   clones those settings. It does this from a `ClassFileTransformer` that never
   changes a class, only because FSAL loads swing-kit and jcefmaven through its
   own `FormsURLClassLoader`. The test then calls
   `chromium().connectOverCDP(...)` and uses DramaFinder as in any Browser UI
   test. When swing-kit offers these settings as options
   ([ticket draft](../swing-kit-jcef-options-ticket.md)), the reflection goes
   and the agent only drives Forms.
4. **Known data.** The suite runs `setup-db.sh` before anything connects,
   because it kills the HYBRID sessions. Each test also restores the rows it
   changed, so tests don't depend on their order.
5. **What the harness starts.** It starts `hello-view` with
   `mvn spring-boot:run` on a free port, passed to the bean with
   `-Dhybrid.url`. It can't run in the test JVM: Spring Boot repackages its jar,
   and Vaadin's dev mode looks for its project folder. It also starts the Forms
   client through `run-forms-client.sh`, and waits for the agent's port and the
   CDP port. It assumes the Docker environment is running, with the form and the
   bean deployed, and skips the tests when it isn't.
6. **Local only for now:** a separate `forms-ui-tests` module, outside the
   normal build (`mvn -Pforms-ui-tests -pl forms-ui-tests verify`), run before releases
   and upgrades. A nightly self-hosted x86_64 runner can run the same module
   later.

## Considered options

- **Test hooks in `VaadinBean`** (extra properties only tests use). They can't
  see Forms items or the status bar, and they would ship in the bean jar.
- **`Robot` or image matching from outside the JVM.** No hooks needed, but it
  can't read values, and it breaks with fonts, themes and window positions.
- **Wait for a swing-kit release with the CDP option.** Cleaner, but the public
  demo pins 3.0.1 and couldn't test itself until then.
- **Bytecode instrumentation (ByteBuddy) of `CefAppBuilder.build()`.** It isn't
  needed, because jcefmaven clones a mutable static default. Reflection on that
  default is smaller and changes no code.
- **GitHub-hosted runners.** The Forms image can't be published (Oracle's
  licence) and needs Oracle's installers to build.

## Consequences

- x86_64 only, like Forms 12.2.1.4 itself.
- The CDP part depends on two jcefmaven 135.0.20 internals: the
  `DEFAULT_CEF_SETTINGS` field and the clone in the constructor. If an upgrade
  breaks it, the agent logs `[cdp-agent] failed` and the CDP port never opens,
  so the tests fail rather than pass silently.
- Each client gets its own CEF cache directory. With the default directory, a
  second JCEF process hands off to one already running, such as the demo you
  have open, and its debugging port never opens.
- Nothing test-related ships in the bean jar or the form.
- In tests, set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`: attaching over CDP
  doesn't need Playwright's bundled browsers.
