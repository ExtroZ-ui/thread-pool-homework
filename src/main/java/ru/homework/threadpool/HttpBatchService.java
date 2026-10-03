package ru.homework.threadpool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


public final class HttpBatchService {
    public record Config(int core, int max, int queue, int keepAliveSeconds, int timeoutSeconds) {
        public Config {
            if (core < 1 || max < core || queue < 1 || keepAliveSeconds < 1 || timeoutSeconds < 1)
                throw new IllegalArgumentException("Require core >= 1, max >= core, queue/timeouts >= 1");
        }
    }

    public record Result(int index, String url, Integer status, double milliseconds,
                         String worker, String error) {
        public boolean successful() { return status != null && status >= 200 && status < 300; }
    }

    public record Batch(List<Result> results, double milliseconds, int largestPoolSize,
                        long completedTasks, boolean terminated) {}

    public Batch execute(List<URI> urls, Config config, boolean monitor) throws InterruptedException {
        AtomicInteger sequence = new AtomicInteger();
        
        ThreadPoolExecutor pool = new ThreadPoolExecutor(config.core(), config.max(),
                config.keepAliveSeconds(), TimeUnit.SECONDS, new ArrayBlockingQueue<>(config.queue()),
                runnable -> new Thread(runnable, "http-worker-" + sequence.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        ExecutorCompletionService<Result> completion = new ExecutorCompletionService<>(pool);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Thread observer = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    System.out.printf("[pool] size=%d active=%d queued=%d completed=%d%n",
                            pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size(),
                            pool.getCompletedTaskCount());
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "pool-monitor");
        observer.setDaemon(true);
        List<Result> results = new ArrayList<>();
        long start = System.nanoTime();
        int submitted = 0;
        if (monitor) observer.start();
        try {
            for (int i = 0; i < urls.size(); i++) {
                final int index = i;
                boolean accepted = false;
                while (!accepted) {
                    try {
                        completion.submit(() -> request(client, index, urls.get(index), config));
                        submitted++;
                        accepted = true;
                    } catch (RejectedExecutionException full) {
                        
                        if (results.size() < submitted) results.add(take(completion));
                        else Thread.yield(); 
                    }
                }
            }
            while (results.size() < submitted) results.add(take(completion));
        } finally {
            observer.interrupt();
            pool.shutdown();
            try {
                if (!pool.awaitTermination(config.timeoutSeconds() + 5L, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    pool.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
                throw interrupted;
            } finally {
                client.close();
            }
        }
        results.sort(Comparator.comparingInt(Result::index));
        return new Batch(List.copyOf(results), (System.nanoTime() - start) / 1_000_000.0,
                pool.getLargestPoolSize(), pool.getCompletedTaskCount(), pool.isTerminated());
    }

    private static Result take(ExecutorCompletionService<Result> completion) throws InterruptedException {
        try { return completion.take().get(); }
        catch (ExecutionException exception) {
            throw new IllegalStateException("Unexpected task failure", exception.getCause());
        }
    }

    private static Result request(HttpClient client, int index, URI url, Config config) {
        long start = System.nanoTime();
        Integer status = null;
        String error = "";
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("User-Agent", "ThreadPoolHomework/1.0").GET().build();
            
            status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error = "InterruptedException";
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName() + ": " +
                    (exception.getMessage() == null ? "request failed" : exception.getMessage());
        }
        return new Result(index, url.toString(), status, (System.nanoTime() - start) / 1_000_000.0,
                Thread.currentThread().getName(), error);
    }
}
