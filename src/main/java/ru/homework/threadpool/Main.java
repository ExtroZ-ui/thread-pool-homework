package ru.homework.threadpool;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class Main {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        try { run(args); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Execution interrupted");
            System.exit(130);
        } catch (Exception exception) {
            System.err.println("Error: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of("urls", "output", "core", "max", "queue", "keep-alive", "timeout");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help")) {
                System.out.println("Usage: --urls urls.txt --output results.csv --core 4 --max 8 " +
                        "--queue 8 --keep-alive 30 --timeout 10");
                return;
            }
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2)) || i + 1 >= args.length)
                throw new IllegalArgumentException("Unknown option or missing value: " + args[i]);
            String key = args[i].substring(2);
            if (options.put(key, args[++i]) != null) throw new IllegalArgumentException("Duplicate option: " + key);
        }
        var config = new HttpBatchService.Config(number(options, "core", 4), number(options, "max", 8),
                number(options, "queue", 8), number(options, "keep-alive", 30), number(options, "timeout", 10));
        Path input = Path.of(options.getOrDefault("urls", "urls.txt"));
        List<URI> urls = new ArrayList<>();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            URI uri = URI.create(line);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("Invalid HTTP(S) URL at line " + lineNumber);
            urls.add(uri);
        }
        if (urls.isEmpty()) throw new IllegalArgumentException("URL list is empty");
        System.out.printf("Requests=%d core=%d max=%d queue=%d keepAlive=%ds timeout=%ds%n",
                urls.size(), config.core(), config.max(), config.queue(), config.keepAliveSeconds(), config.timeoutSeconds());
        var batch = new HttpBatchService().execute(urls, config, true);
        System.out.println("\n # | Status | Time (ms) | Worker | URL / Error");
        for (var result : batch.results()) {
            System.out.printf("%2d | %6s | %9.2f | %s | %s%s%n", result.index() + 1,
                    result.status() == null ? "ERROR" : result.status(), result.milliseconds(),
                    result.worker(), result.url(), result.error().isEmpty() ? "" : " / " + result.error());
        }
        long success = batch.results().stream().filter(HttpBatchService.Result::successful).count();
        long networkErrors = batch.results().stream().filter(r -> r.status() == null).count();
        DoubleSummaryStatistics times = batch.results().stream().mapToDouble(HttpBatchService.Result::milliseconds).summaryStatistics();
        System.out.printf("%nSummary: total=%d success(2xx)=%d HTTP non-2xx=%d networkErrors=%d%n",
                urls.size(), success, urls.size() - success - networkErrors, networkErrors);
        System.out.printf("Request time: min=%.2f avg=%.2f max=%.2f ms; batch=%.2f ms%n",
                times.getMin(), times.getAverage(), times.getMax(), batch.milliseconds());
        System.out.printf("Pool: largest=%d completed=%d terminated=%s%n", batch.largestPoolSize(), batch.completedTasks(), batch.terminated());
        Path output = Path.of(options.getOrDefault("output", "results.csv"));
        if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
        List<String> csv = new ArrayList<>();
        csv.add("index,url,status,time_ms,worker,error");
        for (var result : batch.results()) csv.add(String.format(Locale.ROOT, "%d,%s,%s,%.2f,%s,%s",
                result.index() + 1, quote(result.url()), result.status() == null ? "" : result.status(),
                result.milliseconds(), quote(result.worker()), quote(result.error())));
        Files.write(output, csv, StandardCharsets.UTF_8);
        System.out.println("CSV saved: " + output.toAbsolutePath());
    }

    private static int number(Map<String, String> options, String key, int fallback) {
        return Integer.parseInt(options.getOrDefault(key, Integer.toString(fallback)));
    }

    private static String quote(String text) { return "\"" + text.replace("\"", "\"\"") + "\""; }
}
