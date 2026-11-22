package com.oorja.backend.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole test JVM. Spring caches application contexts across test classes, so a
 * per-class container would leave later classes talking to a dead port; a single lazily-started container (stopped
 * by Testcontainers' Ryuk when the JVM exits) keeps every cached context valid.
 */
final class SharedPostgres {

    private static PostgreSQLContainer<?> container;

    private SharedPostgres() {}

    static synchronized PostgreSQLContainer<?> get() {
        if (container == null) {
            PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
            pg.start();
            container = pg;
        }
        return container;
    }
}
