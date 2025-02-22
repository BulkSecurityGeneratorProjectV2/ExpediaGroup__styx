/*
  Copyright (C) 2013-2022 Expedia Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package com.hotels.styx.routing.handlers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.net.HostAndPort;
import com.hotels.styx.NettyExecutor;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHeaderNames;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.api.ResponseEventListener;
import com.hotels.styx.api.extension.Origin;
import com.hotels.styx.api.extension.service.ConnectionPoolSettings;
import com.hotels.styx.api.extension.service.TlsSettings;
import com.hotels.styx.client.Connection;
import com.hotels.styx.client.OriginStatsFactory;
import com.hotels.styx.client.StyxHostHttpClient;
import com.hotels.styx.client.applications.metrics.OriginMetrics;
import com.hotels.styx.client.connectionpool.ConnectionPool;
import com.hotels.styx.client.connectionpool.ExpiringConnectionFactory;
import com.hotels.styx.client.connectionpool.SimpleConnectionPoolFactory;
import com.hotels.styx.client.netty.connectionpool.NettyConnectionFactory;
import com.hotels.styx.config.schema.Schema;
import com.hotels.styx.infrastructure.configuration.yaml.JsonNodeConfig;
import com.hotels.styx.metrics.CentralisedMetrics;
import com.hotels.styx.routing.RoutingObject;
import com.hotels.styx.routing.config.RoutingObjectFactory;
import com.hotels.styx.routing.config.StyxObjectDefinition;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.extension.service.ConnectionPoolSettings.defaultConnectionPoolSettings;
import static com.hotels.styx.client.HttpConfig.newHttpConfigBuilder;
import static com.hotels.styx.client.HttpRequestOperationFactory.Builder.httpRequestOperationFactoryBuilder;
import static com.hotels.styx.config.schema.SchemaDsl.atLeastOne;
import static com.hotels.styx.config.schema.SchemaDsl.bool;
import static com.hotels.styx.config.schema.SchemaDsl.field;
import static com.hotels.styx.config.schema.SchemaDsl.integer;
import static com.hotels.styx.config.schema.SchemaDsl.list;
import static com.hotels.styx.config.schema.SchemaDsl.object;
import static com.hotels.styx.config.schema.SchemaDsl.optional;
import static com.hotels.styx.config.schema.SchemaDsl.string;
import static com.hotels.styx.routing.config.RoutingSupport.missingAttributeError;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * A routing object that proxies all incoming traffic to a remote host.
 */
public class HostProxy implements RoutingObject {
    public static final Schema.FieldType SCHEMA = object(
            field("host", string()),
            optional("tlsSettings", object(
                    optional("trustAllCerts", bool()),
                    optional("sslProvider", string()),

                    // We could pull them out to a separate configuration block:
                    optional("trustStorePath", string()),
                    optional("trustStorePassword", string()),
                    optional("protocols", list(string())),
                    optional("cipherSuites", list(string())),
                    optional("additionalCerts", list(object(
                            field("alias", string()),
                            field("certificatePath", string())
                    ))),
                    atLeastOne("trustAllCerts",
                            "trustStorePath",
                            "trustStorePassword",
                            "protocols",
                            "cipherSuites",
                            "additionalCerts")
            )),
            optional("connectionPool", object(
                    optional("maxConnections", integer()),
                    optional("maxPendingConnections", integer()),
                    optional("connectTimeoutMillis", integer()),
                    optional("socketTimeoutMillis", integer()),
                    optional("pendingConnectionTimeoutMillis", integer()),
                    optional("connectionExpirationSeconds", integer()),
                    atLeastOne("maxConnections",
                            "maxPendingConnections",
                            "connectTimeoutMillis",
                            "socketTimeoutMillis",
                            "pendingConnectionTimeoutMillis",
                            "connectionExpirationSeconds")
            )),
            optional("responseTimeoutMillis", integer()),
            optional("maxHeaderSize", integer()),
            optional("metricPrefix", string()),
            optional("executor", string()),
            optional("overrideHostHeader", bool())
    );

    private final String errorMessage;
    private final StyxHostHttpClient client;
    private final OriginMetrics originMetrics;
    private final boolean overrideHostHeader;
    private volatile boolean active = true;


    // Visible for testing
    final String host;
    // Visible for testing
    final int port;

    public HostProxy(String host, int port, StyxHostHttpClient client, OriginMetrics originMetrics,
                     boolean overrideHostHeader) {
        this.host = requireNonNull(host);
        this.port = port;
        this.errorMessage = format("HostProxy %s:%d is stopped but received traffic.", host, port);
        this.client = requireNonNull(client);
        this.originMetrics = requireNonNull(originMetrics);
        this.overrideHostHeader = overrideHostHeader;
    }

    @Override
    public Eventual<LiveHttpResponse> handle(LiveHttpRequest request, HttpInterceptor.Context context) {
        if (active) {
            LiveHttpRequest modifiedRequest = modifyHostHeaderIfNeeded(request);

            return new Eventual<>(
                    ResponseEventListener.from(client.sendRequest(modifiedRequest, context))
                            .whenCancelled(originMetrics::requestCancelled)
                            .apply());
        } else {
            return Eventual.error(new IllegalStateException(errorMessage));
        }
    }

    @Override
    public CompletableFuture<Void> stop() {
        active = false;
        client.close();
        return completedFuture(null);
    }

    private LiveHttpRequest modifyHostHeaderIfNeeded(LiveHttpRequest request) {
        if (overrideHostHeader && !host.isEmpty()) {
            String hostAndPort = host + ":" + port;
            return request.newBuilder().header(HttpHeaderNames.HOST, hostAndPort).build();
        } else {
            return request;
        }
    }

    /**
     * HostProxy configuration.
     */
    public static class HostProxyConfiguration {
        private final String host;
        private final ConnectionPoolSettings connectionPool;
        private final TlsSettings tlsSettings;
        private final int responseTimeoutMillis;
        private final int maxHeaderSize;
        private final String metricPrefix;
        private final String executor;
        private final boolean overrideHostHeader;

        public HostProxyConfiguration(
                String host,
                ConnectionPoolSettings connectionPool,
                TlsSettings tlsSettings,
                int responseTimeoutMillis,
                int maxHeaderSize,
                String metricPrefix,
                String executor,
                boolean overrideHostHeader) {
            this.host = host;
            this.connectionPool = connectionPool;
            this.tlsSettings = tlsSettings;
            this.responseTimeoutMillis = responseTimeoutMillis;
            this.maxHeaderSize = maxHeaderSize;
            this.metricPrefix = metricPrefix;
            this.executor = executor;
            this.overrideHostHeader = overrideHostHeader;
        }

        @JsonProperty("host")
        public String host() {
            return host;
        }

        @JsonProperty("connectionPool")
        public ConnectionPoolSettings connectionPool() {
            return connectionPool;
        }

        @JsonProperty("tlsSettings")
        public TlsSettings tlsSettings() {
            return tlsSettings;
        }

        @JsonProperty("responseTimeoutMillis")
        public int responseTimeoutMillis() {
            return responseTimeoutMillis;
        }

        @JsonProperty("maxHeaderSize")
        public int maxHeaderSize() {
            return maxHeaderSize;
        }

        @JsonProperty("metricPrefix")
        public String metricPrefix() {
            return metricPrefix;
        }

        @JsonProperty("executor")
        public String executor() {
            return executor;
        }

        @JsonProperty("overrideHostHeader")
        public boolean isOverrideHostHeader() {
            return overrideHostHeader;
        }

    }

    /**
     * A factory for creating HostProxy routingObject objects.
     */
    public static class Factory implements RoutingObjectFactory {
        private static final int DEFAULT_REQUEST_TIMEOUT = 60000;
        private static final int DEFAULT_TLS_PORT = 443;
        private static final int DEFAULT_HTTP_PORT = 80;
        public static final int USE_DEFAULT_MAX_HEADER_SIZE = 0;

        @Override
        public RoutingObject build(List<String> fullName, Context context, StyxObjectDefinition configBlock) {
            JsonNodeConfig config = new JsonNodeConfig(configBlock.config());

            ConnectionPoolSettings poolSettings = config.get("connectionPool", ConnectionPoolSettings.class)
                    .orElse(defaultConnectionPoolSettings());

            TlsSettings tlsSettings = config.get("tlsSettings", TlsSettings.class)
                    .orElse(null);

            int responseTimeoutMillis = config.get("responseTimeoutMillis", Integer.class)
                    .orElse(DEFAULT_REQUEST_TIMEOUT);

            int maxHeaderSize = config.get("maxHeaderSize", Integer.class).orElse(USE_DEFAULT_MAX_HEADER_SIZE);

            String metricPrefix = config.get("metricPrefix", String.class)
                    .orElse("routing.objects");

            String executorName = config.get("executor", String.class)
                    .orElse("Styx-Client-Global-Worker");

            String objectName = fullName.get(fullName.size() - 1);

            NettyExecutor executor = context.executors().get(executorName)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    format("HostProxy(%s) configuration error: executor='%s' not declared.",
                                            objectName,
                                            executorName)))
                    .component4();

            HostAndPort hostAndPort = config.get("host")
                    .map(HostAndPort::fromString)
                    .map(it -> addDefaultPort(it, tlsSettings))
                    .orElseThrow(() -> missingAttributeError(configBlock, join(".", fullName), "host"));

            boolean overrideHostHeader = config.get("overrideHostHeader", Boolean.class).orElse(false);

            return createHostProxyHandler(
                    executor,
                    context.environment().centralisedMetrics(),
                    hostAndPort,
                    poolSettings,
                    tlsSettings,
                    responseTimeoutMillis,
                    maxHeaderSize,
                    metricPrefix,
                    objectName,
                    overrideHostHeader);
        }

        private static HostAndPort addDefaultPort(HostAndPort hostAndPort, TlsSettings tlsSettings) {
            if (hostAndPort.hasPort()) {
                return hostAndPort;
            }

            int defaultPort = Optional.ofNullable(tlsSettings)
                    .map(it -> DEFAULT_TLS_PORT)
                    .orElse(DEFAULT_HTTP_PORT);

            return HostAndPort.fromParts(hostAndPort.getHost(), defaultPort);
        }

        @NotNull
        public static HostProxy createHostProxyHandler(
                NettyExecutor executor,
                CentralisedMetrics metrics,
                HostAndPort hostAndPort,
                ConnectionPoolSettings poolSettings,
                TlsSettings tlsSettings,
                int responseTimeoutMillis,
                int maxHeaderSize,
                String appId,
                String originId,
                boolean overrideHostHeader) {

            String host = hostAndPort.getHost();
            int port = hostAndPort.getPort();

            Origin origin = newOriginBuilder(host, port)
                    .applicationId(appId)
                    .id(originId)
                    .build();

            OriginMetrics originMetrics = new OriginMetrics(metrics, origin);

            ConnectionPool.Factory connectionPoolFactory = new SimpleConnectionPoolFactory.Builder()
                    .connectionFactory(
                            connectionFactory(
                                    executor,
                                    tlsSettings,
                                    responseTimeoutMillis,
                                    maxHeaderSize,
                                    theOrigin -> originMetrics,
                                    poolSettings.connectionExpirationSeconds()))
                    .connectionPoolSettings(poolSettings)
                    .metrics(metrics)
                    .build();

            return new HostProxy(host, port,
                StyxHostHttpClient.create(connectionPoolFactory.create(origin)),
                originMetrics,
                overrideHostHeader);
        }

        private static Connection.Factory connectionFactory(
                NettyExecutor executor,
                TlsSettings tlsSettings,
                int responseTimeoutMillis,
                int maxHeaderSize,
                OriginStatsFactory originStatsFactory,
                long connectionExpiration) {

            // Uses the default executor for now:
            NettyConnectionFactory factory = new NettyConnectionFactory.Builder()
                    .httpRequestOperationFactory(
                            httpRequestOperationFactoryBuilder()
                                    .flowControlEnabled(true)
                                    .originStatsFactory(originStatsFactory)
                                    .responseTimeoutMillis(responseTimeoutMillis)
                                    .build()
                    )
                    .executor(executor)
                    .tlsSettings(tlsSettings)
                    .httpConfig(newHttpConfigBuilder().setMaxHeadersSize(maxHeaderSize).build())
                    .build();

            if (connectionExpiration > 0) {
                return new ExpiringConnectionFactory(connectionExpiration, factory);
            } else {
                return factory;
            }
        }

    }

}
