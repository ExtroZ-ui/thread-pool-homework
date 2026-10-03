package ru.homework.threadpool;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;


public final class IntegrationTest {
    public static void main(String[] args) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverWorkers = Executors.newFixedThreadPool(12);
        server.setExecutor(serverWorkers);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        server.createContext("/", exchange -> {
            int concurrent = active.incrementAndGet();
            peak.accumulateAndGet(concurrent, Math::max);
            try {
                String path = exchange.getRequestURI().getPath();
                Thread.sleep(path.equals("/slow") ? 2000 : 150);
                int status = path.equals("/missing") ? 404 : 200;
                exchange.sendResponseHeaders(status, -1);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); exchange.close(); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var urls = new ArrayList<URI>();
            for (int i = 0; i < 20; i++) urls.add(URI.create(base + (i == 19 ? "/missing" : "/ok")));
            var batch = new HttpBatchService().execute(urls, new HttpBatchService.Config(2, 4, 1, 1, 3), false);
            check(batch.results().size() == 20, "All 20 results retained with queue capacity 1");
            check(batch.results().stream().filter(r -> r.successful()).count() == 19, "19 successful HTTP responses");
            check(batch.results().get(19).status() == 404, "HTTP 404 retained as response");
            check(peak.get() > 1 && peak.get() <= 4, "Concurrent requests within maximum workers");
            check(batch.largestPoolSize() == 4, "Pool grows from core to maximum");
            check(batch.completedTasks() == 20 && batch.terminated(), "Tasks completed and executor terminated");
            for (int i = 0; i < 20; i++) {
                var result = batch.results().get(i);
                check(result.index() == i && result.worker().startsWith("http-worker-") && result.milliseconds() > 0,
                        "Result " + i + " ordered, measured and executed by custom worker");
            }
            var errors = new HttpBatchService().execute(java.util.List.of(URI.create(base + "/slow"),
                    URI.create("http://127.0.0.1:0/unavailable")), new HttpBatchService.Config(1, 2, 1, 1, 1), false);
            check(errors.results().get(0).status() == null && errors.results().get(0).error().contains("HttpTimeoutException"), "Timeout recorded");
            check(errors.results().get(1).status() == null && !errors.results().get(1).error().isEmpty(), "Connection failure recorded");
            check(errors.terminated(), "Pool terminated after request errors");
            boolean invalid = false;
            try { new HttpBatchService.Config(4, 2, 1, 1, 1); }
            catch (IllegalArgumentException expected) { invalid = true; }
            check(invalid, "Invalid configuration rejected");
            System.out.printf("PASS: 30 assertions; 20 requests; peak concurrency=%d; largest pool=%d; batch=%.2f ms%n",
                    peak.get(), batch.largestPoolSize(), batch.milliseconds());
        } finally {
            server.stop(0);
            serverWorkers.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
