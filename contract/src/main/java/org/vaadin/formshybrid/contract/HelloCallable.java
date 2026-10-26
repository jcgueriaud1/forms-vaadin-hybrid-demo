package org.vaadin.formshybrid.contract;

import com.vaadin.swingkit.core.SwingVaadinCallable;

/**
 * Calls the host form makes on the embedded hello view. swing-kit routes each call
 * to the route view implementing this interface, matched by its fully qualified
 * name, so the bean and the view must share this exact type.
 */
public interface HelloCallable extends SwingVaadinCallable {

    /** The host form moved to another customer. */
    void showCustomer(String id, String name);
}
