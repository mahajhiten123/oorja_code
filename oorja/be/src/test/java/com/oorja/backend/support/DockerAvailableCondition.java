package com.oorja.backend.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Skips the Testcontainers-backed tests when no Docker daemon is reachable (a laptop without Docker),
 * unless {@code -Doorja.it.requireDocker=true} (or env OORJA_IT_REQUIRE_DOCKER=true) is set - which CI should do,
 * so that "no Docker" can never silently turn the integration suite into a green no-op.
 */
public class DockerAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean available;
        try {
            available = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            available = false;
        }
        if (available) {
            return ConditionEvaluationResult.enabled("Docker is available");
        }
        boolean required = Boolean.getBoolean("oorja.it.requireDocker")
                || "true".equalsIgnoreCase(System.getenv("OORJA_IT_REQUIRE_DOCKER"));
        if (required) {
            throw new IllegalStateException(
                    "Docker is required for the integration tests (oorja.it.requireDocker=true) but no daemon is reachable.");
        }
        return ConditionEvaluationResult.disabled("Docker is not available - integration tests skipped");
    }
}
