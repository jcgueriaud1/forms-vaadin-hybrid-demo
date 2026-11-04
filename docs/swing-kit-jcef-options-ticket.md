# Make JCEF's remote debugging port and cache directory configurable

_Draft for the swing-kit issue tracker. Written for swing-kit-client 3.0.1._

## Problem

`JCefVaadinPanelBuilder.init()` (swing-kit-client 3.0.1, jcefmaven 135.0.20)
creates its own `CefAppBuilder` and only sets the install directory,
`--disable-gpu` on Linux, and the user agent. Applications can't change any other
`CefSettings`, and two of them are needed to test an app that embeds a
`JVaadinPanel`:

1. **`remote_debugging_port`**: with it, Playwright
   (`chromium().connectOverCDP(...)`) or Chrome DevTools can attach to the
   embedded browser. Tests can then use normal Playwright or DramaFinder locators
   against the Vaadin view running inside the Swing app. Without it, the embedded
   view can't be automated at all.
2. **`root_cache_path`**: swing-kit uses CEF's default, and CEF warns about that
   at startup: _"Please customize CefSettings.root_cache_path for your
   application. Use of the default value may lead to unintended process
   singleton behavior."_ In practice, a second swing-kit JVM on the same machine
   (a test run while the app is open, or two test JVMs) hands off to the first
   one: its own browser and debugging port never come up.

## Proposal

Opt-in system properties, read in `JCefVaadinPanelBuilder.init()` (names are a
suggestion; the client reads no system properties of its own today):

| Property | Effect |
|---|---|
| `vaadin.swingkit.jcef.remoteDebuggingPort` | sets `CefSettings.remote_debugging_port` (off by default) |
| `vaadin.swingkit.jcef.rootCachePath` | sets `CefSettings.root_cache_path` |
| `vaadin.swingkit.jcef.args` (optional) | extra Chromium switches, passed to `addJcefArgs` |

A programmatic hook would work too, for example a `Consumer<CefAppBuilder>`
customizer called before `build()`, but system properties also work when the
app's code can't be changed. That's the case for an Oracle Forms client
launched by the Forms Standalone Launcher.

## Evidence

- Setting `remote_debugging_port` on the `CefSettings` is enough. Playwright
  Java 1.60.0 connected to JCEF 135.0.20 (Chromium 135.0.7049.85) without
  `--remote-allow-origins`. It read the Vaadin view's shadow DOM, opened a
  Dialog by double-clicking a Grid row, and took screenshots.
- Tested inside a real host app: an Oracle Forms 12c client (FSAL, Java 21)
  with a `JVaadinPanel` in a Forms bean
  ([forms-vaadin-hybrid-demo](https://github.com/jcgueriaud1/forms-vaadin-hybrid-demo)).

## Current workaround

A java agent sets both fields by reflection on jcefmaven's private static
`CefAppBuilder.DEFAULT_CEF_SETTINGS`, which each new builder clones. It does
this when `JCefVaadinPanelBuilder` is loaded, so it relies on jcefmaven
internals and can break with any upgrade.

## Acceptance criteria

- Without the properties, behaviour is unchanged.
- With `remoteDebuggingPort=9222`, `http://127.0.0.1:9222/json/list` lists the
  embedded page, and `connectOverCDP` works.
- With `rootCachePath`, two swing-kit JVMs on one machine each get their own
  browser.
