import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-capacity probe (not a correctness test; that is burst.sh). Sends the same request shape
 * as burst.py's stampede (2,000 users, 1-2 seats per request, half aimed at 5 hot seats, 180 seats)
 * from a JDK HttpClient on virtual threads, which costs far less CPU per request than Python. Used
 * to measure what the service itself can take, see LOAD-TESTING.md.
 *
 *   java loadtest/LoadProbe.java <BASE_URL> <ADMIN_SECRET> [requests=20000] [connections=1000]
 *
 * Needs JDK 21+ (single-file source launch). Prints throughput, latency percentiles, the outcome
 * mix and the show's final counts.
 */
public class LoadProbe {
    static final HttpClient C = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    static final Pattern ID = Pattern.compile("\"id\":\"([^\"]+)\"");

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: java loadtest/LoadProbe.java <BASE_URL> <ADMIN_SECRET> [requests] [connections]");
            System.exit(2);
        }
        String base = a[0].replaceAll("/+$", ""), secret = a[1];
        int requests = a.length > 2 ? Integer.parseInt(a[2]) : 20_000;
        int conc = a.length > 3 ? Integer.parseInt(a[3]) : 1_000;
        int users = 2000;
        String admin = find(TOKEN, post(base + "/auth/token", null, "{\"user_id\":\"probe-admin\"}", Map.of("X-Admin-Secret", secret)).body());
        StringBuilder seats = new StringBuilder();
        List<String> labels = new ArrayList<>();
        for (char r = 'A'; r <= 'I'; r++) for (int c = 1; c <= 20; c++) labels.add("" + r + c);
        for (String l : labels) seats.append(seats.isEmpty() ? "" : ",").append('"').append(l).append('"');
        String show = find(ID, post(base + "/shows", admin, "{\"name\":\"probe\",\"seats\":[" + seats + "],\"price_paise\":100}", Map.of()).body());
        String run = UUID.randomUUID().toString().substring(0, 6);
        String[] tokens = new String[users];
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore s = new Semaphore(200);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < users; i++) { int u = i; fs.add(ex.submit(() -> { s.acquire(); try {
                tokens[u] = find(TOKEN, post(base + "/auth/token", null, "{\"user_id\":\"p-" + run + "-" + u + "\"}", Map.of()).body());
            } finally { s.release(); } return null; })); }
            for (var f : fs) f.get();
        }
        Random rnd = new Random(7);
        List<String> hot = labels.subList(0, 5);
        ConcurrentHashMap<String, AtomicLong> outcomes = new ConcurrentHashMap<>();
        long[] lat = new long[requests];
        Semaphore inflight = new Semaphore(conc);
        long t0 = System.nanoTime();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                int n = rnd.nextDouble() < 0.7 ? 1 : 2;
                Set<String> want = new TreeSet<>();
                while (want.size() < n) want.add(rnd.nextBoolean() ? hot.get(rnd.nextInt(5)) : labels.get(rnd.nextInt(labels.size())));
                String body = "{\"seats\":[\"" + String.join("\",\"", want) + "\"],\"idempotency_key\":\"" + UUID.randomUUID() + "\"}";
                String tok = tokens[rnd.nextInt(users)];
                int idx = i;
                inflight.acquire();
                ex.submit(() -> {
                    long s = System.nanoTime();
                    String key;
                    try {
                        var r = post(base + "/shows/" + show + "/reserve", tok, body, Map.of());
                        key = r.statusCode() + (r.statusCode() >= 300 ? " " + r.body().replaceAll(".*\"error\":\"([^\"]+)\".*", "$1") : "");
                    } catch (Exception e) { key = "transport " + e.getClass().getSimpleName(); }
                    finally { inflight.release(); }
                    lat[idx] = System.nanoTime() - s;
                    outcomes.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
                });
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        Arrays.sort(lat);
        System.out.printf("%d requests, %d connections: %.1fs, %.0f req/s%n", requests, conc, secs, requests / secs);
        System.out.printf("latency ms p50 %d p95 %d p99 %d max %d%n", lat[requests / 2] / 1_000_000,
                lat[(int) (requests * .95)] / 1_000_000, lat[(int) (requests * .99)] / 1_000_000, lat[requests - 1] / 1_000_000);
        System.out.println(new TreeMap<>(outcomes));
        System.out.println(post(base + "/shows/" + show, admin, null, Map.of()).body().replaceAll(",\"seats\":.*", "}"));
    }

    static HttpResponse<String> post(String url, String token, String body, Map<String, String> h) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        h.forEach(b::header);
        b = body == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(body));
        return C.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) throw new IllegalStateException(s);
        return m.group(1);
    }
}
