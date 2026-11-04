package org.vaadin.formshybrid.formsui;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Gives Oracle Forms UI tests the {@link HybridScreen}: started once for the
 * whole run, closed when the run ends. Without the Docker environment the tests
 * are reported as skipped, with the reason, instead of failing.
 */
public final class HybridScreenExtension implements ExecutionCondition, BeforeAllCallback, ParameterResolver {

    private static final String UNAVAILABLE = "unavailable";
    private static final String START_FAILURE = "startFailure";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String reason = store(context).computeIfAbsent(UNAVAILABLE,
                key -> HybridScreen.unavailableReason().orElse(""), String.class);
        return reason.isEmpty()
                ? ConditionEvaluationResult.enabled("The Docker environment is up")
                : ConditionEvaluationResult.disabled(reason);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = store(context);
        // A failed start is reported for every test class, but tried only once.
        Throwable failure = store.get(START_FAILURE, Throwable.class);
        if (failure != null) {
            throw new AssertionError("The hybrid screen didn't start (see the first failing test)", failure);
        }
        if (store.get(HybridScreen.class) == null) {
            try {
                store.put(HybridScreen.class, HybridScreen.start());
            } catch (Exception | Error e) {
                store.put(START_FAILURE, e);
                throw e;
            }
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == HybridScreen.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return store(context).get(HybridScreen.class, HybridScreen.class);
    }

    private static ExtensionContext.Store store(ExtensionContext context) {
        return context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL);
    }
}
