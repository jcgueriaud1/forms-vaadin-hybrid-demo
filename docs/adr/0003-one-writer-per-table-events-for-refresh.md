---
status: accepted
---

# One writer per table on a hybrid screen; refresh through bean events; identity by one-time token

The host form and the embedded view share one Oracle schema (#4) but hold
**separate database sessions**, so their changes are separate transactions.
Forms locks a row as soon as its user starts editing it (`SELECT … FOR UPDATE
NOWAIT`, the default automatic locking), and refuses to save with FRM-40654
when another session changed the row after Forms queried it. A Vaadin `UPDATE`
on a row that Forms holds locked waits until the Forms user commits or cancels.

## Decisions

1. **One writer per table, per hybrid screen.** Each table a screen shows has
   exactly one side that may change it; the other side only reads it. In the
   orders scenario (#7), Vaadin owns `ORDERS`, and Forms owns `CUSTOMERS`
   (read-only in the demo). Same-row conflicts between the two halves of a
   screen therefore can't happen.

2. **The writer tells the reader, through the bean, after it commits.**
   - Vaadin → Forms: after its commit, the view emits an event carrying what
     changed (for example `orderSaved` with the customer id and new total). The
     form updates that record's display items, or re-queries the record, and
     resets the record status so Forms doesn't ask to save.
   - Forms → Vaadin: after Forms commits (`POST-FORMS-COMMIT`), the form sets a
     bean property, and the bean calls a refresh method on the view.
   - Nothing is pushed before the commit: the reader must never show data that
     could still be rolled back.

3. **Vaadin's writes still guard against legacy screens.** During a migration,
   other Forms screens (not part of the hybrid screen) may still change the same
   tables. So Vaadin:
   - keeps each transaction short (one user action);
   - locks with a bounded wait (`SELECT … FOR UPDATE WAIT 5`) and, on timeout,
     tells the user the record is being edited in Forms, instead of freezing the
     view;
   - checks that the row didn't change since it was read (a version column or
     the original values), and asks the user to reload when it did.

4. **Identity: shared user in the demo, one-time token in production.**
   - The demo keeps one shared database user (`HYBRID`) for both sides.
   - The bean runs on the user's machine, so anything it sends could be forged.
     The view must never trust a user name that comes through the bean.
   - Production design: the form inserts a one-time token row (random value,
     the Forms user, a short expiry) **through its own database session**, then
     passes only the token through the bean. The view's server redeems it: it
     reads the row, checks the expiry, deletes the row, and opens its session
     for that user. The view then tags its database work with that user (for
     example `DBMS_SESSION.SET_IDENTIFIER` or Oracle proxy authentication) for
     auditing and row-level security.

## Considered options

- **Both sides may write the same tables, with locks resolving collisions.**
  Closer to an unplanned migration, but every screen then needs conflict
  handling on both sides, and Forms' immediate locks make the Vaadin side block
  or fail often.
- **Re-read on navigation only.** Nothing to build, but each side can show stale
  data until the user moves to another record.
- **Pass the Forms user name through the bean.** Simple, but forgeable on the
  client. Acceptable only to display a name, never to authorise anything.

## Consequences

- Before building a hybrid screen, decide and document which side owns each of
  its tables.
- The contract (`HybridEvents`, `HelloCallable`) grows one event per kind of
  change and a refresh method on the view.
- Implementing the identity token is separate work (#17).
