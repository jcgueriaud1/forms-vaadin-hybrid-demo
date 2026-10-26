---
status: accepted for the demo
---

# The Forms 12.2.1.4 client runs on Java 21 (uncertified), via the standalone launcher

The swing-kit bean runs inside the Forms client JVM (ADR-0001), so the embedded
browser (JCEF, through swing-kit 3.0.1's client) runs on whatever Java the Forms
client uses. We run the Forms client on **Java 21**, launched with Oracle's
**Forms Standalone Launcher** (`java -jar frmsal.jar -url …?config=hybrid`, see
`run-forms-client.sh`), even though Oracle doesn't certify 12.2.1.4 on it.
Verified on 2026-10-26 on macOS: the form, the bean, JCEF and both directions of
communication work. Newer Forms releases are certified on Java 11/17, so
production has a supported way forward.

## Considered options

- **Java 8 through OpenWebStart** (the certified, normal Web Start deployment).
  swing-kit 3.0.1's client and its JCEF build are Java 8 bytecode, so this might
  work, but it is untested. OpenWebStart also ignores the JNLP's `j2se` version
  (`java_version=17+` only logs "your JRE 1.8.0_472 does not match requested JRE
  17+") and always uses its bundled Java 8, so Java 8 would be the only option
  there.
- **Run the browser out of process.** The bean starts a separate JVM and
  positions its window over the bean area. Robust about versions, but window
  overlay and focus handling are fragile. Last resort only.

## Consequences

- Bean jars are listed in the config's `archive=` as for Web Start. FSAL
  downloads and caches them itself.
- Every config section FSAL launches needs `baseSAAfile=basesaa.txt`, or the
  servlet answers FRM-93136.
- On macOS, JCEF on Java 17+ needs `--add-opens` for `java.desktop/sun.awt`,
  `sun.lwawt` and `sun.lwawt.macosx`. JCEF must also start only after the bean is
  showing and the window has settled, or the AWT and AppKit threads deadlock.
- This is a demo setup. Production needs a Forms release certified on Java 17+,
  or Oracle's certification of this one.
