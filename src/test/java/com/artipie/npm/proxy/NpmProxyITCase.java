/*
 * The MIT License (MIT) Copyright (c) 2020-2022 artipie.com
 * https://github.com/artipie/npm-adapter/LICENSE.txt
 */
package com.artipie.npm.proxy;

import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.client.Settings;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.npm.RandomFreePort;
import com.artipie.npm.proxy.http.NpmProxySlice;
import com.artipie.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/**
 * Integration test for NPM Proxy.
 *
 * It uses MockServer container to emulate Remote registry responses,
 * and Node container to run npm install command.
 *
 * @since 0.1
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "deprecation"})
@DisabledOnOs(OS.WINDOWS)
@org.testcontainers.junit.jupiter.Testcontainers
public final class NpmProxyITCase {
    /**
     * Vertx instance.
     */
    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Port to listen for NPM Proxy adapter.
     */
    private static int listenPort;

    /**
     * Jetty client.
     */
    private final JettyClientSlices client = new JettyClientSlices(
        new Settings.WithFollowRedirects(true)
    );

    /**
     * Node test container.
     */
    @org.testcontainers.junit.jupiter.Container
    private final NodeContainer npmcnter = new NodeContainer()
        .withCommand("tail", "-f", "/dev/null");

    /**
     * Verdaccio test container.
     */
    @org.testcontainers.junit.jupiter.Container
    private final VerdaccioContainer verdaccio = new VerdaccioContainer()
        .withExposedPorts(4873);

    /**
     * Vertx slice instance.
     */
    private VertxSliceServer srv;

    @Test
    public void installModule() throws IOException, InterruptedException {
        final Container.ExecResult result = this.npmcnter.execInContainer(
            "npm",
            "--registry",
            String.format(
                "http://host.testcontainers.internal:%d/npm-proxy",
                NpmProxyITCase.listenPort
            ),
            "install",
            "asdas"
        );
        MatcherAssert.assertThat(
            result.getStdout(),
            new AllOf<>(
                Arrays.asList(
                    new StringContains("+ asdas@1.0.0"),
                    new StringContains("added 1 package")
                )
            )
        );
    }

    @Test
    public void packageNotFound() throws IOException, InterruptedException {
        final Container.ExecResult result = this.npmcnter.execInContainer(
            "npm",
            "--registry",
            String.format(
                "http://host.testcontainers.internal:%d/npm-proxy",
                NpmProxyITCase.listenPort
            ),
            "install",
            "packageNotFound"
        );
        MatcherAssert.assertThat(result.getExitCode(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            result.getStderr(),
            new StringContains(
                String.format(
                    //@checkstyle LineLengthCheck (1 line)
                    "Not Found - GET http://host.testcontainers.internal:%d/npm-proxy/packageNotFound",
                    NpmProxyITCase.listenPort
                )
            )
        );
    }

    @Test
    public void assetNotFound() throws IOException, InterruptedException {
        final Container.ExecResult result = this.npmcnter.execInContainer(
            "npm",
            "--registry",
            String.format(
                "http://host.testcontainers.internal:%d/npm-proxy",
                NpmProxyITCase.listenPort
            ),
            "install",
            "assetNotFound"
        );
        MatcherAssert.assertThat(result.getExitCode(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            result.getStderr(),
            new StringContains(
                String.format(
                    //@checkstyle LineLengthCheck (1 line)
                    "Not Found - GET http://host.testcontainers.internal:%d/npm-proxy/assetNotFound",
                    NpmProxyITCase.listenPort
                )
            )
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        final String address = this.verdaccio.getContainerIpAddress();
        final Integer port = this.verdaccio.getFirstMappedPort();
        this.client.start();
        final NpmProxy npm = new NpmProxy(
            URI.create(String.format("http://%s:%d", address, port)),
            new InMemoryStorage(),
            this.client
        );
        final NpmProxySlice slice = new NpmProxySlice("npm-proxy", npm);
        this.srv = new VertxSliceServer(NpmProxyITCase.VERTX, slice, NpmProxyITCase.listenPort);
        this.srv.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        this.srv.stop();
        this.client.stop();
    }

    @BeforeAll
    static void prepare() throws IOException {
        NpmProxyITCase.listenPort = new RandomFreePort().value();
        Testcontainers.exposeHostPorts(NpmProxyITCase.listenPort);
    }

    @AfterAll
    static void finish() {
        NpmProxyITCase.VERTX.close();
    }

    /**
     * Inner subclass to instantiate Node container.
     * @since 0.1
     */
    private static class NodeContainer extends GenericContainer<NodeContainer> {
        NodeContainer() {
            super("node:14-alpine");
        }
    }

    /**
     * Inner subclass to instantiate Npm container.
     *
     * We need this class because a situation with generics in testcontainers.
     * See https://github.com/testcontainers/testcontainers-java/issues/238
     * @since 0.1
     */
    private static class VerdaccioContainer extends GenericContainer<VerdaccioContainer> {
        VerdaccioContainer() {
            super("verdaccio/verdaccio");
        }
    }
}
