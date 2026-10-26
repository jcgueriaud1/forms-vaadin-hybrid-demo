# Forms hosts Vaadin through a VBean wrapping swing-kit's JVaadinPanel

A hybrid screen is a running Oracle Forms module (the **host form**) that shows a
Vaadin view (the **embedded view**) inside one of its canvases. The bridge is a
Forms Pluggable Java Component — a `oracle.forms.ui.VBean` subclass placed in a
bean area — that wraps Vaadin swing-kit's `JVaadinPanel`. Forms talks to the view
through `SET_CUSTOM_PROPERTY` → bean → `SwingVaadinCallable`; the view talks back
through `VaadinSwingEvent` → bean → custom item event → the form's
`WHEN-CUSTOM-ITEM-EVENT` trigger. We chose this shape because it is the shape of an
incremental migration: Forms stays the shell while screens are swapped for Vaadin
one at a time, and it is the use case swing-kit was built for.

## Considered options

- **Vaadin hosts Forms.** Not possible: no browser can run the Forms Java client
  in a page (applets are gone). At most a Vaadin page can *launch* Forms in a
  separate JNLP window — the two share only the database, not a screen or live
  communication, which proves nothing about coexistence.
- **A third desktop shell** (a Swing app running the Forms client and a
  `JVaadinPanel` side by side). More work, and it invents a deployment model no
  Forms shop uses; the next step toward production would be to throw it away.

## Consequences

- The bean runs inside the Forms client JVM, so it must share that JVM's Java
  version with swing-kit (see ADR-0002).
- The host form and the embedded view hold separate database sessions. Who owns
  writes per screen, and how user identity reaches the view, is unresolved and
  blocks production use.
