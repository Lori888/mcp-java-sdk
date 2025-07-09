/*
 * 2025-07-03 copy from
 * https://github.com/ZachGerman/mcp-java-sdk  StreamableHttpServerTransportProvider branch
 * mcp/src/main/java/io/modelcontextprotocol/server/McpAsyncStreamableHttpServer.java
 * and refactor use jdk8.
 *
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.StreamableHttpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.DeafaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Model Context Protocol (MCP) server implementation that provides asynchronous
 * communication using Project Reactor's Mono and Flux types.
 *
 * <p>
 * This server implements the MCP specification, enabling AI models to expose tools,
 * resources, and prompts through a standardized interface. Key features include:
 * <ul>
 * <li>Asynchronous communication using reactive programming patterns
 * <li>Dynamic tool registration and management
 * <li>Resource handling with URI-based addressing
 * <li>Prompt template management
 * <li>Real-time client notifications for state changes
 * <li>Structured logging with configurable severity levels
 * <li>Support for client-side AI model sampling
 * </ul>
 *
 * <p>
 * The server follows a lifecycle:
 * <ol>
 * <li>Initialization - Accepts client connections and negotiates capabilities
 * <li>Normal Operation - Handles client requests and sends notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This implementation uses Project Reactor for non-blocking operations, making it
 * suitable for high-throughput scenarios and reactive applications. All operations return
 * Mono or Flux types that can be composed into reactive pipelines.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 * @author Zachary German
 */
public class McpAsyncStreamableHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(McpAsyncStreamableHttpServer.class);

    private final StreamableHttpServerTransportProvider httpTransportProvider;

    private final ObjectMapper objectMapper;

    private final McpSchema.ServerCapabilities serverCapabilities;

    private final McpSchema.Implementation serverInfo;

    private final String instructions;

    private final Duration requestTimeout;

    private final McpUriTemplateManagerFactory uriTemplateManagerFactory;

    // Core server features
    private final McpServerFeatures.Async features;

    /**
     * Creates a new McpAsyncStreamableHttpServer.
     */
    McpAsyncStreamableHttpServer(StreamableHttpServerTransportProvider httpTransportProvider, ObjectMapper objectMapper,
                                 McpServerFeatures.Async features, Duration requestTimeout,
                                 McpUriTemplateManagerFactory uriTemplateManagerFactory) {
        this.httpTransportProvider = httpTransportProvider;
        this.objectMapper = objectMapper;
        this.features = features;
        this.serverInfo = features.getServerInfo();
        this.serverCapabilities = features.getServerCapabilities();
        this.instructions = features.getInstructions();
        this.requestTimeout = requestTimeout;
        this.uriTemplateManagerFactory = uriTemplateManagerFactory != null ? uriTemplateManagerFactory
                : new DeafaultMcpUriTemplateManagerFactory();

        setupRequestHandlers();
        setupSessionFactory();
    }

    public McpServerTransportProvider getTransportProvider() {
        return this.httpTransportProvider;
    }

    /**
     * Sets up the request handlers for standard MCP methods.
     */
    private void setupRequestHandlers() {
        Map<String, McpServerSession.RequestHandler<?>> requestHandlers = new HashMap<>();

        // Ping handler
        requestHandlers.put(McpSchema.METHOD_PING, (exchange, params) -> Mono.just(Collections.emptyMap()));

        // Tool handlers
        if (serverCapabilities.getTools() != null) {
            requestHandlers.put(McpSchema.METHOD_TOOLS_LIST, createToolsListHandler());
            requestHandlers.put(McpSchema.METHOD_TOOLS_CALL, createToolsCallHandler());
        }

        // Resource handlers
        if (serverCapabilities.getResources() != null) {
            requestHandlers.put(McpSchema.METHOD_RESOURCES_LIST, createResourcesListHandler());
            requestHandlers.put(McpSchema.METHOD_RESOURCES_READ, createResourcesReadHandler());
            requestHandlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, createResourceTemplatesListHandler());
        }

        // Prompt handlers
        if (serverCapabilities.getPrompts() != null) {
            requestHandlers.put(McpSchema.METHOD_PROMPT_LIST, createPromptsListHandler());
            requestHandlers.put(McpSchema.METHOD_PROMPT_GET, createPromptsGetHandler());
        }

        // Logging handlers
        if (serverCapabilities.getLogging() != null) {
            requestHandlers.put(McpSchema.METHOD_LOGGING_SET_LEVEL, createLoggingSetLevelHandler());
        }

        // Completion handlers
        if (serverCapabilities.getCompletions() != null) {
            requestHandlers.put(McpSchema.METHOD_COMPLETION_COMPLETE, createCompletionCompleteHandler());
        }

        this.requestHandlers = requestHandlers;
    }

    private Map<String, McpServerSession.RequestHandler<?>> requestHandlers;

    private Map<String, McpServerSession.NotificationHandler> notificationHandlers;

    /**
     * Sets up notification handlers.
     */
    private void setupNotificationHandlers() {
        Map<String, McpServerSession.NotificationHandler> handlers = new HashMap<>();

        handlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> {
            logger.info("[INIT] Received initialized notification - initialization complete!");
            return Mono.empty();
        });

        // Roots change notification handler
        handlers.put(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED, createRootsListChangedHandler());

        this.notificationHandlers = handlers;
    }

    /**
     * Sets up the session factory for the HTTP transport provider.
     */
    private void setupSessionFactory() {
        setupNotificationHandlers();

        httpTransportProvider.setStreamableHttpSessionFactory(sessionId -> new McpServerSession(sessionId,
                requestTimeout, this::handleInitializeRequest, Mono::empty, requestHandlers, notificationHandlers));
    }

    /**
     * Handles initialization requests from clients.
     */
    private Mono<McpSchema.InitializeResult> handleInitializeRequest(McpSchema.InitializeRequest initializeRequest) {
        return Mono.defer(() -> {
            logger.info("[INIT] Client initialize request - Protocol: {}, Capabilities: {}, Info: {}",
                    initializeRequest.getProtocolVersion(), initializeRequest.getCapabilities(),
                    initializeRequest.getClientInfo());

            // Protocol version negotiation
            String serverProtocolVersion = McpSchema.LATEST_PROTOCOL_VERSION;
            if (!McpSchema.LATEST_PROTOCOL_VERSION.equals(initializeRequest.getProtocolVersion())) {
                logger.warn("[INIT] Client requested protocol version: {}, server supports: {}",
                        initializeRequest.getProtocolVersion(), serverProtocolVersion);
            }

            logger.debug("[INIT] Server capabilities: {}", serverCapabilities);
            logger.debug("[INIT] Server info: {}", serverInfo);
            logger.debug("[INIT] Instructions: {}", instructions);
            McpSchema.InitializeResult result = new McpSchema.InitializeResult(serverProtocolVersion,
                    serverCapabilities, serverInfo, instructions);
            logger.info("[INIT] Sending initialize response: {}", result);
            return Mono.just(result);
        });
    }

    // Request handler creation methods
    private McpServerSession.RequestHandler<McpSchema.ListToolsResult> createToolsListHandler() {
        return (exchange, params) -> {
            List<McpSchema.Tool> regularTools = features.getTools().stream().map(McpServerFeatures.AsyncToolSpecification::getTool).collect(Collectors.toList());
            List<McpSchema.Tool> streamingTools = features.getStreamTools()
                    .stream()
                    .map(McpServerFeatures.AsyncStreamingToolSpecification::getTool)
                    .collect(Collectors.toList());
            List<McpSchema.Tool> allTools = new ArrayList<>(regularTools);
            allTools.addAll(streamingTools);
            return Mono.just(new McpSchema.ListToolsResult(allTools, null));
        };
    }

    private McpServerSession.RequestHandler<McpSchema.CallToolResult> createToolsCallHandler() {
        return new McpServerSession.StreamingRequestHandler<McpSchema.CallToolResult>() {
            @Override
            public Mono<McpSchema.CallToolResult> handle(McpAsyncServerExchange exchange, Object params) {
                McpSchema.CallToolRequest callToolRequest = objectMapper.convertValue(params, McpSchema.CallToolRequest.class);

                // Check regular tools first
                Optional<McpServerFeatures.AsyncToolSpecification> regularTool = features.getTools()
                        .stream()
                        .filter(tool -> callToolRequest.getName().equals(tool.getTool().getName()))
                        .findFirst();

                if (regularTool.isPresent()) {
                    return regularTool.get().getCall().apply(exchange, callToolRequest.getArguments());
                }

                // Check streaming tools (take first result)
                Optional<McpServerFeatures.AsyncStreamingToolSpecification> streamingTool = features.getStreamTools()
                        .stream()
                        .filter(tool -> callToolRequest.getName().equals(tool.getTool().getName()))
                        .findFirst();

                if (streamingTool.isPresent()) {
                    return streamingTool.get().getCall().apply(exchange, callToolRequest.getArguments()).next();
                }

                return Mono.error(new RuntimeException("Tool not found: " + callToolRequest.getName()));
            }

            @Override
            public Flux<McpSchema.CallToolResult> handleStreaming(McpAsyncServerExchange exchange, Object params) {
                McpSchema.CallToolRequest callToolRequest = objectMapper.convertValue(params, McpSchema.CallToolRequest.class);

                // Check streaming tools first (preferred for streaming)
                Optional<McpServerFeatures.AsyncStreamingToolSpecification> streamingTool = features.getStreamTools()
                        .stream()
                        .filter(tool -> callToolRequest.getName().equals(tool.getTool().getName()))
                        .findFirst();

                if (streamingTool.isPresent()) {
                    return streamingTool.get().getCall().apply(exchange, callToolRequest.getArguments());
                }

                // Fallback to regular tools (convert Mono to Flux)
                Optional<McpServerFeatures.AsyncToolSpecification> regularTool = features.getTools()
                        .stream()
                        .filter(tool -> callToolRequest.getName().equals(tool.getTool().getName()))
                        .findFirst();

                if (regularTool.isPresent()) {
                    return regularTool.get().getCall().apply(exchange, callToolRequest.getArguments()).flux();
                }

                return Flux.error(new RuntimeException("Tool not found: " + callToolRequest.getName()));
            }
        };
    }

    private McpServerSession.RequestHandler<McpSchema.ListResourcesResult> createResourcesListHandler() {
        return (exchange, params) -> {
            List<McpSchema.Resource> resources = features.getResources()
                    .values()
                    .stream()
                    .map(McpServerFeatures.AsyncResourceSpecification::getResource)
                    .collect(Collectors.toList());
            return Mono.just(new McpSchema.ListResourcesResult(resources, null));
        };
    }

    private McpServerSession.RequestHandler<McpSchema.ReadResourceResult> createResourcesReadHandler() {
        return (exchange, params) -> {
            McpSchema.ReadResourceRequest resourceRequest = objectMapper.convertValue(params, McpSchema.ReadResourceRequest.class);
            String resourceUri = resourceRequest.getUri();

            return features.getResources()
                    .values()
                    .stream()
                    .filter(spec -> uriTemplateManagerFactory.create(spec.getResource().getUri()).matches(resourceUri))
                    .findFirst()
                    .map(spec -> spec.getReadHandler().apply(exchange, resourceRequest))
                    .orElse(Mono.error(new RuntimeException("Resource not found: " + resourceUri)));
        };
    }

    private McpServerSession.RequestHandler<McpSchema.ListResourceTemplatesResult> createResourceTemplatesListHandler() {
        return (exchange, params) -> Mono
                .just(new McpSchema.ListResourceTemplatesResult(features.getResourceTemplates(), null));
    }

    private McpServerSession.RequestHandler<McpSchema.ListPromptsResult> createPromptsListHandler() {
        return (exchange, params) -> {
            List<McpSchema.Prompt> prompts = features.getPrompts()
                    .values()
                    .stream()
                    .map(McpServerFeatures.AsyncPromptSpecification::getPrompt)
                    .collect(Collectors.toList());
            return Mono.just(new McpSchema.ListPromptsResult(prompts, null));
        };
    }

    private McpServerSession.RequestHandler<McpSchema.GetPromptResult> createPromptsGetHandler() {
        return (exchange, params) -> {
            McpSchema.GetPromptRequest promptRequest = objectMapper.convertValue(params, McpSchema.GetPromptRequest.class);

            return features.getPrompts()
                    .values()
                    .stream()
                    .filter(spec -> spec.getPrompt().getName().equals(promptRequest.getName()))
                    .findFirst()
                    .map(spec -> spec.getPromptHandler().apply(exchange, promptRequest))
                    .orElse(Mono.error(new RuntimeException("Prompt not found: " + promptRequest.getName())));
        };
    }

    private McpServerSession.RequestHandler<Object> createLoggingSetLevelHandler() {
        return (exchange, params) -> {
            McpSchema.SetLevelRequest setLevelRequest = objectMapper.convertValue(params, McpSchema.SetLevelRequest.class);
            exchange.setMinLoggingLevel(setLevelRequest.getLevel());
            return Mono.just(Collections.emptyMap());
        };
    }

    private McpServerSession.RequestHandler<McpSchema.CompleteResult> createCompletionCompleteHandler() {
        return (exchange, params) -> {
            McpSchema.CompleteRequest completeRequest = objectMapper.convertValue(params, McpSchema.CompleteRequest.class);

            return features.getCompletions()
                    .values()
                    .stream()
                    .filter(spec -> spec.getReferenceKey().equals(completeRequest.getRef()))
                    .findFirst()
                    .map(spec -> spec.getCompletionHandler().apply(exchange, completeRequest))
                    .orElse(Mono.error(new RuntimeException("Completion not found: " + completeRequest.getRef())));
        };
    }

    private McpServerSession.NotificationHandler createRootsListChangedHandler() {
        return (exchange, params) -> {
            List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = features.getRootsChangeConsumers();
            if (rootsChangeConsumers.isEmpty()) {
                return Mono
                        .fromRunnable(() -> logger.warn("Roots list changed notification, but no consumers provided"));
            }

            return exchange.listRoots()
                    .flatMap(listRootsResult -> Flux.fromIterable(rootsChangeConsumers)
                            .flatMap(consumer -> consumer.apply(exchange, listRootsResult.getRoots()))
                            .onErrorResume(error -> {
                                logger.error("Error handling roots list change notification", error);
                                return Mono.empty();
                            })
                            .then());
        };
    }

    /**
     * Get the server capabilities.
     */
    public McpSchema.ServerCapabilities getServerCapabilities() {
        return serverCapabilities;
    }

    /**
     * Get the server implementation information.
     */
    public McpSchema.Implementation getServerInfo() {
        return serverInfo;
    }

    /**
     * Gracefully closes the server.
     */
    public Mono<Void> closeGracefully() {
        return httpTransportProvider.closeGracefully();
    }

    /**
     * Close the server immediately.
     */
    public void close() {
        httpTransportProvider.close();
    }

    /**
     * Notifies clients that the list of available tools has changed.
     */
    public Mono<Void> notifyToolsListChanged() {
        return httpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
    }

    /**
     * Notifies clients that the list of available resources has changed.
     */
    public Mono<Void> notifyResourcesListChanged() {
        return httpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
    }

    /**
     * Notifies clients that resources have been updated.
     */
    public Mono<Void> notifyResourcesUpdated(McpSchema.ResourcesUpdatedNotification notification) {
        return httpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED, notification);
    }

    /**
     * Notifies clients that the list of available prompts has changed.
     */
    public Mono<Void> notifyPromptsListChanged() {
        return httpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
    }

    /**
     * Creates a new builder for configuring and creating McpAsyncStreamableHttpServer
     * instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating instances of McpAsyncServer
     */
    public static class Builder {

        private McpSchema.Implementation serverInfo;

        private McpSchema.ServerCapabilities serverCapabilities;

        private String instructions;

        private Duration requestTimeout = Duration.ofSeconds(30);

        private ObjectMapper objectMapper = new ObjectMapper();

        private String mcpEndpoint = "/mcp";

        private Supplier<String> sessionIdProvider;

        private McpUriTemplateManagerFactory uriTemplateManagerFactory = new DeafaultMcpUriTemplateManagerFactory();

        private final List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();

        private final List<McpServerFeatures.AsyncStreamingToolSpecification> streamTools = new ArrayList<>();

        private final Map<String, McpServerFeatures.AsyncResourceSpecification> resources = new HashMap<>();

        private final List<McpSchema.ResourceTemplate> resourceTemplates = new ArrayList<>();

        private final Map<String, McpServerFeatures.AsyncPromptSpecification> prompts = new HashMap<>();

        private final Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();

        private final List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = new ArrayList<>();

        /**
         * Sets the server implementation information.
         */
        public Builder serverInfo(String name, String version) {
            return serverInfo(name, null, version);
        }

        /**
         * Sets the server implementation information.
         */
        public Builder serverInfo(String name, String title, String version) {
            Assert.hasText(name, "Server name must not be empty");
            Assert.hasText(version, "Server version must not be empty");
            this.serverInfo = new McpSchema.Implementation(name, version);
            return this;
        }

        /**
         * Sets the server capabilities.
         */
        public Builder serverCapabilities(McpSchema.ServerCapabilities capabilities) {
            this.serverCapabilities = capabilities;
            return this;
        }

        /**
         * Sets the server instructions.
         */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /**
         * Sets the request timeout duration.
         */
        public Builder requestTimeout(Duration timeout) {
            Assert.notNull(timeout, "Request timeout must not be null");
            this.requestTimeout = timeout;
            return this;
        }

        /**
         * Sets the JSON object mapper.
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "ObjectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Sets the MCP endpoint path.
         */
        public Builder withMcpEndpoint(String endpoint) {
            Assert.hasText(endpoint, "MCP endpoint must not be empty");
            this.mcpEndpoint = endpoint;
            return this;
        }

        /**
         * Sets the session ID provider.
         */
        public Builder withSessionIdProvider(Supplier<String> provider) {
            Assert.notNull(provider, "Session ID provider must not be null");
            this.sessionIdProvider = provider;
            return this;
        }

        /**
         * Sets the URI template manager factory.
         */
        public Builder withUriTemplateManagerFactory(McpUriTemplateManagerFactory factory) {
            Assert.notNull(factory, "URI template manager factory must not be null");
            this.uriTemplateManagerFactory = factory;
            return this;
        }

        /**
         * Adds a tool specification.
         */
        public Builder withTool(McpServerFeatures.AsyncToolSpecification toolSpec) {
            Assert.notNull(toolSpec, "Tool specification must not be null");
            this.tools.add(toolSpec);
            return this;
        }

        /**
         * Adds a streaming tool specification.
         */
        public Builder withStreamingTool(McpServerFeatures.AsyncStreamingToolSpecification toolSpec) {
            Assert.notNull(toolSpec, "Streaming tool specification must not be null");
            this.streamTools.add(toolSpec);
            return this;
        }

        /**
         * Adds a resource specification.
         */
        public Builder withResource(String uri, McpServerFeatures.AsyncResourceSpecification resourceSpec) {
            Assert.hasText(uri, "Resource URI must not be empty");
            Assert.notNull(resourceSpec, "Resource specification must not be null");
            this.resources.put(uri, resourceSpec);
            return this;
        }

        /**
         * Adds a resource template.
         */
        public Builder withResourceTemplate(McpSchema.ResourceTemplate template) {
            Assert.notNull(template, "Resource template must not be null");
            this.resourceTemplates.add(template);
            return this;
        }

        /**
         * Adds a prompt specification.
         */
        public Builder withPrompt(String name, McpServerFeatures.AsyncPromptSpecification promptSpec) {
            Assert.hasText(name, "Prompt name must not be empty");
            Assert.notNull(promptSpec, "Prompt specification must not be null");
            this.prompts.put(name, promptSpec);
            return this;
        }

        /**
         * Adds a completion specification.
         */
        public Builder withCompletion(McpSchema.CompleteReference reference,
                                      McpServerFeatures.AsyncCompletionSpecification completionSpec) {
            Assert.notNull(reference, "Completion reference must not be null");
            Assert.notNull(completionSpec, "Completion specification must not be null");
            this.completions.put(reference, completionSpec);
            return this;
        }

        /**
         * Adds a roots change consumer.
         */
        public Builder withRootsChangeConsumer(
                BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>> consumer) {
            Assert.notNull(consumer, "Roots change consumer must not be null");
            this.rootsChangeConsumers.add(consumer);
            return this;
        }

        /**
         * Builds the McpAsyncStreamableHttpServer instance.
         */
        public McpAsyncStreamableHttpServer build() {
            Assert.notNull(serverInfo, "Server info must be set");

            // Create Streamable HTTP transport provider
            StreamableHttpServerTransportProvider.Builder transportBuilder = StreamableHttpServerTransportProvider
                    .builder()
                    .withObjectMapper(objectMapper)
                    .withMcpEndpoint(mcpEndpoint);

            if (sessionIdProvider != null) {
                transportBuilder.withSessionIdProvider(sessionIdProvider);
            }

            StreamableHttpServerTransportProvider httpTransportProvider = transportBuilder.build();

            // Create server features
            McpServerFeatures.Async features = new McpServerFeatures.Async(serverInfo, serverCapabilities, tools,
                    resources, resourceTemplates, prompts, completions, rootsChangeConsumers, instructions,
                    streamTools);

            return new McpAsyncStreamableHttpServer(httpTransportProvider, objectMapper, features, requestTimeout,
                    uriTemplateManagerFactory);
        }
    }
}