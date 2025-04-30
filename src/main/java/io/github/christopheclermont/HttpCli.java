package io.github.christopheclermont;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Command(name = "httpcli", mixinStandardHelpOptions = true,
        description = "Simple HTTP REST CLI with native-image support")
public class HttpCli implements Runnable {

    @Option(names = "--url", required = true, description = "Request URL")
    private String url;

    @Option(names = "--method", required = true, description = "HTTP method (GET, POST, PUT, DELETE, ...)")
    private String method;

    @Option(names = "--header", arity = "1", description = "Repeatable header: \"Name: Value\"", split = ";")
    private List<String> headers = new ArrayList<>();

    public enum AuthMode {none, basic, header, token, oauth2}

    @Option(names = "--authentication", description = "Auth mode: ${COMPLETION-CANDIDATES}")
    private AuthMode auth = AuthMode.none;

    @Option(names = "--data", description = "Request body data")
    private String data;

    @Option(names = "--output", description = "Output file path (defaults to stdout)")
    private Path output;

    @Option(names = "--debug", description = "Enable debug output")
    private boolean debug = false;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new HttpCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            HttpRequest.Builder reqBld = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60));
            // Method & body
            String m = method.toUpperCase();
            if (data != null && !data.isEmpty() && List.of("POST", "PUT", "PATCH", "DELETE").contains(m)) {
                reqBld.method(m, BodyPublishers.ofString(data));
            } else {
                reqBld.method(m, BodyPublishers.noBody());
            }

            // Headers
            for (String h : headers) {
                String[] parts = h.split(":", 2);
                if (parts.length == 2) reqBld.header(parts[0].trim(), parts[1].trim());
            }

            // Authentication
            applyAuth(reqBld);

            HttpRequest request = reqBld.build();
            if (debug) System.err.println(">> " + request);

            HttpResponse<String> resp = client.send(request, BodyHandlers.ofString());
            if (debug) System.err.println("<< " + resp.statusCode() + " " + resp.headers());

            // Output
            if (output != null) {
                Files.writeString(output, resp.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                System.out.println(resp.body());
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (debug) e.printStackTrace();
            throw new CommandLine.ExecutionException(new CommandLine(this), "Execution failed", e);
        }
    }

    private void applyAuth(HttpRequest.Builder builder) throws IOException, InterruptedException {
        switch (auth) {
            case basic -> {
                String bp = System.getenv().getOrDefault("BASIC_AUTH", "");
                if (bp.isEmpty()) throw new IllegalArgumentException("BASIC_AUTH env var missing");
                builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(bp.getBytes()));
            }
            case header -> {
                // assume user passed full header via --header
                break;
            }
            case token -> {
                String tok = System.getenv().getOrDefault("BEARER_TOKEN", "");
                if (tok.isEmpty()) throw new IllegalArgumentException("BEARER_TOKEN env var missing");
                builder.header("Authorization", "Bearer " + tok);
            }
            case oauth2 -> {
                String clientId = getenvOrFail("OAUTH2_CLIENT_ID");
                String clientSecret = getenvOrFail("OAUTH2_CLIENT_SECRET");
                String tokenUrl = getenvOrFail("OAUTH2_TOKEN_URL");
                // Fetch token
                String body = "grant_type=client_credentials"
                        + "&client_id=" + clientId
                        + "&client_secret=" + clientSecret;
                HttpRequest tReq = HttpRequest.newBuilder()
                        .uri(URI.create(tokenUrl))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> tResp = client.send(tReq, BodyHandlers.ofString());
                ObjectMapper om = new ObjectMapper();
                JsonNode jn = om.readTree(tResp.body());
                String accessToken = jn.get("access_token").asText();
                builder.header("Authorization", "Bearer " + accessToken);
            }
            case none -> { /* no auth */ }
        }
    }

    private static String getenvOrFail(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException(name + " env var missing");
        return v;
    }
}
