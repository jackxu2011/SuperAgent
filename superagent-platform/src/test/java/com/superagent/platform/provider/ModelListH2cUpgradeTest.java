package com.superagent.platform.provider;

import com.superagent.core.chat.RunCredential;
import com.superagent.runtime.cli.CliPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A local relay that takes the socket for an h2c upgrade and then never answers (claude-code-router does this):
 * the model list must still arrive, so the fetcher must speak HTTP/1.1 on plain http.
 */
class ModelListH2cUpgradeTest {

    @TempDir
    Path dir;

    @Test
    void modelListSurvivesARelayThatSwallowsTheH2cUpgrade() throws Exception {
        List<Socket> hung = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            Thread relay = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        boolean upgrade = false;
                        for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
                            upgrade |= line.toLowerCase().startsWith("upgrade:");
                        }
                        if (upgrade) {
                            synchronized (hung) { hung.add(s); }   // the socket is taken, no reply ever comes
                            continue;
                        }
                        byte[] body = ("{\"data\":[{\"id\":\"claude-opus-4-5\",\"max_input_tokens\":200000}],"
                                + "\"has_more\":false}").getBytes(StandardCharsets.UTF_8);
                        OutputStream out = s.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                        out.flush();
                        s.close();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            relay.setDaemon(true);
            relay.start();

            ProviderProbe noCli = new ProviderProbe(new CliPaths(dir.toString())) {
                @Override
                public Optional<Path> findBinary(String cli) {
                    return Optional.empty();
                }
            };
            MachineCredentials machine = new MachineCredentials(k -> null, dir, dir);
            ProviderModelFetcher fetcher = new ProviderModelFetcher(noCli, machine);
            String base = "http://127.0.0.1:" + server.getLocalPort();

            var result = fetcher.fetch("claude", new RunCredential("c", base, "sk-test", true));

            assertThat(result.models()).extracting(m -> m.id()).containsExactly("claude-opus-4-5");
            synchronized (hung) {
                assertThat(hung).isEmpty();   // no request was left waiting on an upgrade
                hung.forEach(s -> { try { s.close(); } catch (Exception ignored) { } });
            }
        }
    }
}
