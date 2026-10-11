package com.ts.ticketing.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.ts.ticketing.security.JwtService;
import com.ts.ticketing.security.Role;

/**
 * Black-box HTTP client for integration tests. Uses the JDK HttpClient (one connection per
 * in-flight request), so hundreds of calls really hit the server at the same time.
 */
public final class TestApi {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final JwtService jwt;

    public TestApi(int port, JwtService jwt) {
        this.baseUrl = "http://localhost:" + port;
        this.jwt = jwt;
    }

    public record Response(int status, java.net.http.HttpHeaders headers, JsonNode body) {
        public String text(String field) {
            return body.path(field).asText(null);
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    // ---- tokens -------------------------------------------------------------------------

    public String userToken(String userId) {
        return jwt.issue(userId, Role.USER).token();
    }

    public String adminToken() {
        return jwt.issue("admin", Role.ADMIN).token();
    }

    /** A user id no other test uses. */
    public static String uniqueUser(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- endpoints ----------------------------------------------------------------------

    public Response createShow(Map<String, Object> body) {
        return post("/shows", adminToken(), body, Map.of());
    }

    /** Creates a show and returns its id. */
    public String createShow(List<String> seats, long pricePaise, Integer perUserLimit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "test-show");
        body.put("seats", seats);
        body.put("price_paise", pricePaise);
        if (perUserLimit != null) {
            body.put("per_user_limit", perUserLimit);
        }
        Response res = createShow(body);
        assertThat(res.status()).as("create show: %s", res.body()).isEqualTo(201);
        return res.text("id");
    }

    public Response reserve(String token, String showId, List<String> seats, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seats", seats);
        body.put("idempotency_key", key);
        return post("/shows/" + showId + "/reserve", token, body, Map.of());
    }

    public Response reserve(String token, String showId, List<String> seats) {
        return reserve(token, showId, seats, UUID.randomUUID().toString());
    }

    public Response cancel(String token, String reservationId) {
        return post("/reservations/" + reservationId + "/cancel", token, null, Map.of());
    }

    public Response getShow(String token, String showId) {
        return getPath("/shows/" + showId, token);
    }

    public Response getPath(String path, String token) {
        return send(request(path, token, Map.of()).GET());
    }

    /** status of each seat label, from GET /shows/{id}. */
    public Map<String, String> seatStatuses(String showId) {
        Response res = getShow(userToken("observer"), showId);
        assertThat(res.status()).isEqualTo(200);
        Map<String, String> statuses = new LinkedHashMap<>();
        res.body().path("seats").forEach(s -> statuses.put(s.path("label").asText(), s.path("status").asText()));
        return statuses;
    }

    /** Value of an unlabelled Prometheus sample; 0 if absent. */
    public double metric(String name) {
        Matcher m = Pattern.compile("^" + Pattern.quote(name) + "\\s+(\\S+)$", Pattern.MULTILINE).matcher(metrics());
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    public String metrics() {
        try {
            return CLIENT.send(HttpRequest.newBuilder(URI.create(baseUrl + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Value of a Prometheus sample whose labels include {@code label="value"}; 0 if absent. */
    public double metric(String name, String label, String value) {
        Pattern line = Pattern.compile("^" + Pattern.quote(name) + "\\{[^}]*" + Pattern.quote(label + "=\"" + value + "\"")
                + "[^}]*}\\s+(\\S+)$", Pattern.MULTILINE);
        Matcher m = line.matcher(metrics());
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    public Response post(String path, String token, Object body, Map<String, String> headers) {
        try {
            String json = body == null ? "" : body instanceof String s ? s : JSON.writeValueAsString(body);
            return send(request(path, token, headers).POST(HttpRequest.BodyPublishers.ofString(json)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest.Builder request(String path, String token, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        headers.forEach(b::header);
        return b;
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> res = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = res.body() == null || res.body().isBlank() ? NullNode.getInstance() : JSON.readTree(res.body());
            return new Response(res.statusCode(), res.headers(), body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- concurrency --------------------------------------------------------------------

    /**
     * Runs {@code n} tasks on virtual threads that all wait on one start gate, then fire
     * together. Returns the results in task order.
     */
    public static <T> List<T> concurrently(int n, IntFunction<T> task) {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(index);
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>(n);
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
