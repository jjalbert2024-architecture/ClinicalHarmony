package com.clinicalharmony.patientindex.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A tiny real HTTP server for tests (JDK built-in, no extra dependency): routes by path prefix,
 * records every request. Stands in for Keycloak's token endpoint and GoldWriter.
 */
public final class StubHttpServer implements AutoCloseable {

    public record Request(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    public record Response(int status, String body) {
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
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Longest matching path prefix wins. */
    public StubHttpServer route(String pathPrefix, Function<Request, Response> handler) {
        routes.put(pathPrefix, handler);
        return this;
    }

    public List<Request> requests() {
        return requests;
    }

    public List<Request> requestsTo(String pathPrefix) {
        return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    public void reset() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()), body);
        requests.add(request);
        Response response = routes.entrySet().stream()
                .filter(e -> request.path().startsWith(e.getKey()))
                .max(Map.Entry.comparingByKey((a, b) -> Integer.compare(a.length(), b.length())))
                .map(e -> e.getValue().apply(request))
                .orElse(new Response(404, "{\"error\":\"Not found\",\"message\":\"no stub route\"}"));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
