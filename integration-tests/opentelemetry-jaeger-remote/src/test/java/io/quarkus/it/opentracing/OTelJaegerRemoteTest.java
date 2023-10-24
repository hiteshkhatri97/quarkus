package io.quarkus.it.opentracing;

import static com.github.dockerjava.api.model.HostConfig.newHostConfig;

import java.time.Duration;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.jr.ob.JSON;
import com.fasterxml.jackson.jr.stree.JacksonJrsTreeCodec;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.quarkus.test.junit.QuarkusTest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@QuarkusTest
public class OTelJaegerRemoteTest {

    private static final OkHttpClient client = new OkHttpClient();
    private static final int QUERY_PORT = 16686;
    private static final int COLLECTOR_PORT = 14250;
    private static final int HEALTH_PORT = 14269;
    private static final String JAEGER_URL = "http://localhost";
    @Inject
    OpenTelemetry openTelemetry;
    private static final DockerClient dockerClient;

    static {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        dockerClient = DockerClientImpl.getInstance(config, httpClient);
        if (dockerClient.listContainersCmd().exec().stream()
                .noneMatch(container -> container.getNames()[0].equals("/jaeger"))) {
            ExposedPort queryPort = ExposedPort.tcp(QUERY_PORT);
            ExposedPort collectorPort = ExposedPort.tcp(COLLECTOR_PORT);
            ExposedPort hostPort = ExposedPort.tcp(HEALTH_PORT);
            Ports portBindings = new Ports();
            portBindings.bind(queryPort, Ports.Binding.bindPort(QUERY_PORT));
            portBindings.bind(collectorPort, Ports.Binding.bindPort(COLLECTOR_PORT));
            portBindings.bind(hostPort, Ports.Binding.bindPort(HEALTH_PORT));
            CreateContainerResponse container = dockerClient
                    .createContainerCmd("ghcr.io/open-telemetry/opentelemetry-java/jaeger:1.32")
                    .withExposedPorts(queryPort, collectorPort, hostPort)
                    .withHostConfig(newHostConfig().withPortBindings(portBindings))
                    .withName("jaeger")
                    .exec();
            dockerClient.startContainerCmd(container.getId()).exec();
        }
    }

    @AfterAll
    static void teardown() {
        dockerClient.listContainersCmd().exec()
                .forEach(container -> {
                    dockerClient.stopContainerCmd(container.getId()).exec();
                    dockerClient.removeContainerCmd(container.getId()).exec();
                });
    }

    @Test
    void testJaegerRemoteIntegration() {
        createTestSpan(openTelemetry);
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(OTelJaegerRemoteTest::assertJaegerHaveTrace);
    }

    private void createTestSpan(OpenTelemetry openTelemetry) {
        Span span = openTelemetry.getTracer(getClass().getCanonicalName()).spanBuilder("Test span").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.addEvent("Test event");
        } catch (Throwable t) {
            span.recordException(t);
            throw t;
        } finally {
            span.end();
        }
    }

    private static boolean assertJaegerHaveTrace() {
        try {

            String serviceName = ConfigProvider.getConfig().getConfigValue("quarkus.application.name").getValue();
            String url = String.format(
                    "%s/api/traces?service=%s",
                    String.format(JAEGER_URL + ":%d", QUERY_PORT),
                    serviceName);

            Request request = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .build();

            TreeNode json;
            try (Response response = client.newCall(request).execute()) {
                json = JSON.builder()
                        .treeCodec(new JacksonJrsTreeCodec())
                        .build()
                        .treeFrom(response.body().byteStream());
            }

            return json.get("data").get(0).get("traceID") != null;
        } catch (Exception e) {
            return false;
        }
    }

}
