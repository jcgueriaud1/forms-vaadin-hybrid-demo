package org.vaadin.formshybrid.contract;

import com.vaadin.swingkit.core.SwingVaadinCallable;

/**
 * Signs a view in as the Forms user (#17). The bean carries only a one-time
 * token that the form got from the database; the view's server redeems it to
 * learn the user. A user name never travels through the bean: it runs on the
 * user's machine, so anything it sends could be forged.
 */
public interface SignInCallable extends SwingVaadinCallable {

    /** A token from {@code hybrid.issue_token}, valid once, for about a minute. */
    void signIn(String token);
}
