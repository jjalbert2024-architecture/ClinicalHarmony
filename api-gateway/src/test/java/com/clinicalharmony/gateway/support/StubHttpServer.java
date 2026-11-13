package com.clinicalharmony.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A tiny real HTTP server for tests (JDK built-in, no WireMock): routes by path prefix (longest
 * wins), records every request, and can answer slowly. Stands in for Keycloak's token endpoint and
 * the downstream services. Same idea as patient-index-service's, plus delays and a multi-threaded
 * executor so a slow route doesn't block the others.
 */
public final class StubHttpServer implements AutoCloseable {

    public record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    public record Response(int status, String body, long delayMillis) {
        public Response(int status, String body) {
            this(status, body, 0);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Response>> routes = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    /** A local port with nothing listening — "service down". */
    public static String deadBaseUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubHttpServer route(String pathPrefix, Function<Request, Response> handler) {
        routes.put(pathPrefix, handler);
        return this;
    }

    public List<Request> requestsTo(String pathPrefix) {
        return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    public void reset() {
        requests.clear();
        routes.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()), body);
        requests.add(request);
        Response response = routes.entrySet().stream()
                .filter(e -> request.path().startsWith(e.getKey()))
                .max(Map.Entry.comparingByKey((a, b) -> Integer.compare(a.length(), b.length())))
                .map(e -> e.getValue().apply(request))
                .orElse(new Response(404, "{\"error\":\"Not found\",\"message\":\"no stub route\"}"));
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException ignored) {
            // client gave up (timeout tests)
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
