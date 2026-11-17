package in.practice.tape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Entirely local TLS/STARTTLS fixture. Never sends mail or accesses a market-data provider. */
@EnabledIfSystemProperty(named="tape.browser.tests", matches="true")
class PrivateBetaJourneyTest {
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[2];
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<String, String> mail = new ConcurrentHashMap<>();
    private final String fixtureKey = UUID.randomUUID().toString();
    private final ObjectMapper json = new ObjectMapper();

    @Test void invitedUsersCanVerifyTradeRecoverAndRetainSessionsAcrossNodesAndRestart() throws Exception {
        Path artifacts = Path.of("target/beta-browser").toAbsolutePath();
        Files.createDirectories(artifacts);
        Path keyStore = artifacts.resolve("fixture.p12");
        Files.deleteIfExists(keyStore); // This fixed path is an ephemeral test certificate, never a user's key.
        String password = UUID.randomUUID().toString();
        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "keytool.exe" : "keytool").toString(),
            "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", keyStore.toString(),
            "-storepass", password, "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2")
            .redirectErrorStream(true).redirectOutput(artifacts.resolve("certificate.log").toFile()).start();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, keytool.exitValue());
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStore)) { store.load(input, password.toCharArray()); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        var tls = SSLContext.getInstance("TLS");
        var trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(store);
        var oldTls = SSLContext.getDefault();
        tls.init(keys.getKeyManagers(), trusts.getTrustManagers(), null);
        SSLContext.setDefault(tls);
        String oldStore = System.getProperty("javax.net.ssl.trustStore"), oldPassword = System.getProperty("javax.net.ssl.trustStorePassword");
        System.setProperty("javax.net.ssl.trustStore", keyStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", password);
        var executor = Executors.newCachedThreadPool();
        HttpsServer proxy = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 30);
        proxy.setHttpsConfigurator(new HttpsConfigurator(tls));
        proxy.setExecutor(executor);
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        fixture.setExecutor(executor);
        String origin = "https://localhost:" + proxy.getAddress().getPort();
        String suffix = UUID.randomUUID().toString().substring(0,8);
        String emails = java.util.stream.IntStream.range(0,8).mapToObj(i -> "beta"+i+"-"+suffix+"@example.test").collect(java.util.stream.Collectors.joining(","));
        try (var smtp = new ServerSocket(0, 20, InetAddress.getLoopbackAddress())) {
            executor.submit(() -> {
                while (!smtp.isClosed()) {
                    try { Socket connection = smtp.accept(); executor.submit(() -> receive(connection, tls)); }
                    catch (IOException closed) { if (!smtp.isClosed()) throw new UncheckedIOException(closed); }
                }
            });
            AccountSecurityTest.start();
            for (String name : List.of("trading", "options")) new ResourceDatabasePopulator(new FileSystemResource("docs/"+name+"-runtime-grants.sql")).execute(AccountSecurityTest.owner.getDataSource());
            var settings = new HashMap<String, Object>();
            settings.put("TAPE_REGISTRATION_MODE", "ALLOWLIST");
            settings.put("TAPE_REGISTRATION_ALLOWLIST", emails);
            settings.put("tape.identity.mail.enabled", true);
            settings.put("tape.identity.mail.scheduled", true);
            settings.put("TAPE_PUBLIC_ORIGIN", origin);
            settings.put("TAPE_MAIL_FROM", "fixture@example.test");
            settings.put("TAPE_SMTP_HOST", "localhost");
            settings.put("spring.mail.host", "localhost");
            settings.put("spring.mail.port", smtp.getLocalPort());
            settings.put("spring.mail.properties.mail.smtp.auth", false);
            settings.put("TAPE_IDENTITY_MAIL_KEY", Base64.getEncoder().encodeToString(new byte[32]));
            settings.put("tape.practice-options.enabled", true);
            settings.put("tape.practice-futures.enabled", true);
            settings.put("spring.datasource.hikari.maximum-pool-size", 8);
            nodes[0] = AccountSecurityTest.startApplication(false, true, false, settings);
            nodes[1] = AccountSecurityTest.startApplication(false, true, false, settings);
            var upstream = HttpClient.newHttpClient();
            proxy.createContext("/", exchange -> {
                try {
                    if (exchange.getRequestURI().getPath().startsWith("/actuator") || exchange.getRequestURI().getPath().startsWith("/internal")) {
                        exchange.sendResponseHeaders(404, -1); return;
                    }
                    var node = nodes[Math.floorMod(requests.getAndIncrement(), 2)];
                    int port = ((ServletWebServerApplicationContext) node).getWebServer().getPort();
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+exchange.getRequestURI()))
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-For", "198.51.100.42")
                        .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
                    for (String name : List.of("Cookie", "Content-Type", "X-CSRF-TOKEN", "Accept"))
                        for (String value : exchange.getRequestHeaders().getOrDefault(name, List.of())) request.header(name, value);
                    var response = upstream.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                    response.headers().map().forEach((name, values) -> { if (!Set.of("transfer-encoding", "connection", "content-length").contains(name)) exchange.getResponseHeaders().put(name, values); });
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
                    if (response.body().length != 0) exchange.getResponseBody().write(response.body());
                } catch (Exception failure) { exchange.sendResponseHeaders(502, -1); }
                finally { exchange.close(); }
            });
            fixture.createContext("/", exchange -> {
                try {
                    if (!fixtureKey.equals(exchange.getRequestHeaders().getFirst("X-Fixture-Key"))) { exchange.sendResponseHeaders(403,-1); return; }
                    byte[] response;
                    if (exchange.getRequestURI().getPath().equals("/restart")) {
                        nodes[0].close();
                        nodes[0] = AccountSecurityTest.startApplication(false, true, false, settings);
                        response = "restarted".getBytes(StandardCharsets.UTF_8);
                    } else response = json.writeValueAsBytes(mail);
                    exchange.sendResponseHeaders(200,response.length);
                    exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            });
            proxy.start(); fixture.start();
            var process = new ProcessBuilder("node", "scripts/beta-journey.mjs", origin, "http://127.0.0.1:"+fixture.getAddress().getPort());
            process.environment().put("TAPE_FIXTURE_KEY", fixtureKey);
            process.environment().put("TAPE_FIXTURE_EMAILS", emails);
            process.redirectErrorStream(true).redirectOutput(artifacts.resolve("journey.log").toFile());
            var journey = process.start();
            assertTrue(journey.waitFor(240, TimeUnit.SECONDS), "Browser journey timed out");
            assertEquals(0, journey.exitValue(), "See target/beta-browser/journey.log (no credentials or tokens logged)");
            DatabaseMaintenance.reconcile(AccountSecurityTest.owner.getDataSource());
            assertTrue(requests.get() > 100);
        } finally {
            proxy.stop(0); fixture.stop(0);
            for (var node : nodes) if (node != null) node.close();
            AccountSecurityTest.stop();
            executor.shutdownNow();
            restoreProperty("javax.net.ssl.trustStore", oldStore);
            restoreProperty("javax.net.ssl.trustStorePassword", oldPassword);
            SSLContext.setDefault(oldTls);
        }
    }

    private void receive(Socket socket, SSLContext tls) {
        try (socket) {
            socket.setSoTimeout(15000);
            Socket active = socket;
            var reader = new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.US_ASCII));
            var writer = new PrintWriter(active.getOutputStream(), true, StandardCharsets.US_ASCII);
            writer.print("220 fixture ESMTP\r\n"); writer.flush();
            String line;
            while ((line=reader.readLine()) != null) {
                if (line.startsWith("EHLO")) writer.print("250-fixture\r\n250-STARTTLS\r\n250 SIZE 32768\r\n");
                else if (line.equals("STARTTLS")) {
                    writer.print("220 Begin TLS\r\n"); writer.flush();
                    var secure = (SSLSocket) tls.getSocketFactory().createSocket(active, "localhost", active.getPort(), false);
                    secure.setUseClientMode(false); secure.startHandshake(); active = secure;
                    reader = new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.US_ASCII));
                    writer = new PrintWriter(active.getOutputStream(), true, StandardCharsets.US_ASCII);
                } else if (line.equals("DATA")) {
                    writer.print("354 End with dot\r\n"); writer.flush();
                    var text = new StringBuilder();
                    while ((line=reader.readLine()) != null && !line.equals(".")) text.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                    var message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.US_ASCII)));
                    String content = message.getContent().toString();
                    var link = java.util.regex.Pattern.compile("https://localhost:[0-9]+/#(?:verify|reset)=[A-Za-z0-9_-]{43}").matcher(content);
                    if (!link.find()) throw new IllegalStateException("Fixture email link missing");
                    mail.put(message.getAllRecipients()[0].toString(), link.group());
                    writer.print("250 Queued\r\n");
                } else if (line.equals("QUIT")) { writer.print("221 Bye\r\n"); writer.flush(); break; }
                else writer.print("250 OK\r\n");
                writer.flush();
            }
        } catch (Exception failure) { /* Sender sees a failed SMTP connection and retains encrypted retry work. */ }
    }
    private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }
    private static void restoreProperty(String name, String value) { if (value == null) System.clearProperty(name); else System.setProperty(name,value); }
}
