package com.superagent.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.McpServerSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reads a server's tool list the standard way, over a local command and over streamable HTTP. */
class McpClientTest {

    /**
     * Scripts run through sh rather than executed themselves: a test that writes a script and execs it at once fails
     * now and then with "Text file busy" (ETXTBSY), when a process another test forks at that moment inherits the
     * script's still-open write handle. sh only reads it.
     */
    private static final String SH = "/bin/sh";

    private final McpClient client = new McpClient();

    @Test
    void stdioImmediatelyExitedServerReportsExitCode(@TempDir Path dir) throws IOException {
        Path server = dir.resolve("exit.sh");
        Files.writeString(server, "#!/bin/sh\nexit 3\n");
        assertThatThrownBy(() -> client.listTools(new McpServerSpec("x", ConnectorTransport.STDIO, null, Map.of(),
                SH, List.of(server.toString()), Map.of()), Duration.ofSeconds(10))).hasMessageContaining("退出码 3");
    }

    @Test
    void stdioClosedInputThenDelayedExitReportsExitCode(@TempDir Path dir) throws IOException {
        Path server = dir.resolve("closed-input.sh");
        Files.writeString(server, """
                #!/bin/sh
                read line
                exec 0<&-
                echo '{"jsonrpc":"2.0","id":1,"result":{}}'
                sleep 0.3
                exit 3
                """);
        assertThatThrownBy(() -> client.listTools(new McpServerSpec("x", ConnectorTransport.STDIO, null, Map.of(),
                SH, List.of(server.toString()), Map.of()), Duration.ofSeconds(10)))
                .hasMessageContaining("退出码 3").hasCauseInstanceOf(IOException.class);
    }

    @Test
    void failedWriteReportsDelayedExitAndOriginalCause() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 0.3; exit 3").start();
        IOException failure = new IOException("Broken pipe");
        try {
            assertThatThrownBy(() -> sendWithFailure(process, failure))
                    .hasMessageContaining("退出码 3").hasCause(failure);
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void failedWriteRethrowsOriginalWhenProcessRemainsAlive() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "exec sleep 10").start();
        IOException failure = new IOException("Broken pipe");
        try {
            assertThatThrownBy(() -> sendWithFailure(process, failure)).isSameAs(failure);
            assertThat(process.isAlive()).isTrue();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void interruptedWaitRestoresInterruptAndRethrowsWriteFailure() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "exec sleep 10").start();
        IOException failure = new IOException("Broken pipe");
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> sendWithFailure(process, failure)).isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            process.destroyForcibly();
        }
    }

    private void sendWithFailure(Process process, IOException failure) throws Throwable {
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw failure;
            }
        };
        var method = McpClient.class.getDeclaredMethod("sendOrExplainExit", OutputStream.class, JsonNode.class, Process.class);
        method.setAccessible(true);
        try {
            method.invoke(client, out, new ObjectMapper().createObjectNode(), process);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void stdioServerToolsAcrossPagesWithTheTokenFromEnv(@TempDir Path dir) throws IOException {
        // Answers by line order: initialize, (notification), tools/list page 1, page 2. Logs noise on stdout too.
        Path server = dir.resolve("server.sh");
        Files.writeString(server, """
                #!/bin/sh
                read l; echo 'starting up...'; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}'
                read l
                read l; echo '{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"list_repos","annotations":{"readOnlyHint":true}}],"nextCursor":"p2"}}'
                read l; echo "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":3,\\"result\\":{\\"tools\\":[{\\"name\\":\\"whoami_$TOKEN\\"}]}}"
                read l
                """);

        List<JsonNode> tools = client.listTools(new McpServerSpec("x", ConnectorTransport.STDIO, null, Map.of(),
                SH, List.of(server.toString()), Map.of("TOKEN", "ada")), Duration.ofSeconds(10));

        assertThat(tools).extracting(t -> t.path("name").asText()).containsExactly("list_repos", "whoami_ada");
        assertThat(tools.get(0).path("annotations").path("readOnlyHint").asBoolean()).isTrue();

        Path dies = dir.resolve("dies.sh");
        Files.writeString(dies, "#!/bin/sh\nexit 3\n");
        assertThatThrownBy(() -> client.listTools(new McpServerSpec("x", ConnectorTransport.STDIO, null, Map.of(),
                SH, List.of(dies.toString()), Map.of()), Duration.ofSeconds(10))).hasMessageContaining("退出");
    }

    @Test
    void streamableHttpKeepsTheSessionAndReadsSseAnswers() throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<String> seen = new ArrayList<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", ex -> {
            JsonNode m = json.readTree(ex.getRequestBody());
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            seen.add(m.path("method").asText() + "|" + ex.getRequestHeaders().getFirst("Mcp-Session-Id"));
            if (!"Bearer good".equals(auth)) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            if (!m.has("id")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            String body;
            if ("initialize".equals(m.path("method").asText())) {
                ex.getResponseHeaders().add("Mcp-Session-Id", "s1");
                ex.getResponseHeaders().add("Content-Type", "application/json");
                body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
            } else {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + m.path("id").asInt()
                        + ",\"result\":{\"tools\":[{\"name\":\"create_issue\",\"annotations\":{\"destructiveHint\":false}}]}}\n\n";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        http.start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
            List<JsonNode> tools = client.listTools(new McpServerSpec("x", ConnectorTransport.HTTP, url,
                    Map.of("Authorization", "Bearer good"), null, List.of(), Map.of()), Duration.ofSeconds(10));
            assertThat(tools).extracting(t -> t.path("name").asText()).containsExactly("create_issue");
            assertThat(seen).containsExactly("initialize|null", "notifications/initialized|s1", "tools/list|s1");

            assertThatThrownBy(() -> client.listTools(new McpServerSpec("x", ConnectorTransport.HTTP, url,
                    Map.of("Authorization", "Bearer stale"), null, List.of(), Map.of()), Duration.ofSeconds(10)))
                    .hasMessageContaining("重新授权");
        } finally {
            http.stop(0);
        }
    }
}
