package com.oorja.backend.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs N copies of a task at the same instant (a barrier releases them together) and collects every outcome. */
public final class Parallel {

    private Parallel() {}

    @FunctionalInterface
    public interface Task<T> {
        T call(int index) throws Exception;
    }

    /** Either the value a task returned or the exception it threw - concurrency tests assert on both. */
    public record Outcome<T>(T value, Throwable error) {
        public boolean failed() {
            return error != null;
        }
    }

    public static <T> List<Outcome<T>> run(int threads, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier start = new CyclicBarrier(threads);
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await(60, TimeUnit.SECONDS);
                    try {
                        return new Outcome<T>(task.call(index), null);
                    } catch (Throwable t) {
                        return new Outcome<T>(null, t);
                    }
                }));
            }
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
