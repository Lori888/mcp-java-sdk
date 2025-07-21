/*
 * 2025-07-02 copy from
 * https://github.com/ZachGerman/mcp-java-sdk  StreamableHttpServerTransportProvider branch
 * mcp/src/main/java/io/modelcontextprotocol/server/transport/StreamableHttpServerTransportProvider.java
 * and refactor use jdk8.
 *
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * MCP Streamable HTTP transport provider that uses a single session class to manage all
 * streams and transports.
 *
 * <p>
 * Key improvements over the original implementation:
 * <ul>
 * <li>Manages server-client sessions, including transport registration.
 * <li>Handles HTTP requests and HTTP/SSE responses and streams.
 * <li>Provides callbacks for session lifecycle and errors.
 * <li>Supports graceful shutdown.
 * <li>Enforces allowed 'Origin' header values if configured.
 * <li>Provides a default session ID provider if none is configured.
 * </ul>
 *
 * @author Zachary German
 */
@WebServlet(asyncSupported = true)
public class StreamableHttpServerTransportProvider extends HttpServlet implements McpServerTransportProvider {

    private static final Logger logger = LoggerFactory.getLogger(StreamableHttpServerTransportProvider.class);

    public static final String UTF_8 = "UTF-8";

    public static final String APPLICATION_JSON = "application/json";

    public static final String TEXT_EVENT_STREAM = "text/event-stream";

    public static final String SESSION_ID_HEADER = "Mcp-Session-Id";

    public static final String LAST_EVENT_ID_HEADER = "Last-Event-Id";

    public static final String MESSAGE_EVENT_TYPE = "message";

    public static final String ACCEPT_HEADER = "Accept";

    public static final String ORIGIN_HEADER = "Origin";

    public static final String ALLOW_ORIGIN_HEADER = "Access-Control-Allow-Origin";

    public static final String ALLOW_ORIGIN_DEFAULT_VALUE = "*";

    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    public static final String CACHE_CONTROL_HEADER = "Cache-Control";

    public static final String CONNECTION_HEADER = "Connection";

    public static final String CACHE_CONTROL_NO_CACHE = "no-cache";

    public static final String CONNECTION_KEEP_ALIVE = "keep-alive";

    public static final String MCP_SESSION_ID = "MCP-Session-ID";

    public static final String DEFAULT_MCP_ENDPOINT = "/mcp";

    /** com.fasterxml.jackson.databind.ObjectMapper */
    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();

    /** UUID.randomUUID().toString() */
    private static final Supplier<String> DEFAULT_SESSION_ID_PROVIDER = () -> UUID.randomUUID().toString();

    /** JSON object mapper for serialization/deserialization */
    private final ObjectMapper objectMapper;

    /** The endpoint path for handling MCP requests */
    private final String mcpEndpoint;

    /** Supplier for generating unique session IDs */
    private final Supplier<String> sessionIdProvider;

    /** Sessions map, keyed by Session ID */
    private static final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();

    /** Flag indicating if the transport is in the process of shutting down */
    private final AtomicBoolean isClosing = new AtomicBoolean(false);

    /** Optional allowed 'Origin' header value list. Not enforced if empty. */
    private final List<String> allowedOrigins = new ArrayList<>();

    /** Callback interface for session lifecycle and errors */
    private SessionHandler sessionHandler;

    /** Factory for McpServerSession takes session IDs */
    private McpServerSession.StreamableHttpSessionFactory streamableHttpSessionFactory;

    /**
     * <ul>
     * <li>Manages server-client sessions, including transport registration.
     * <li>Handles HTTP requests and HTTP/SSE responses and streams.
     * </ul>
     * @param objectMapper ObjectMapper - Default:
     * com.fasterxml.jackson.databind.ObjectMapper
     * @param mcpEndpoint String - Default: '/mcp'
     * @param sessionIdProvider Supplier(String) - Default: UUID.randomUUID().toString()
     */
    public StreamableHttpServerTransportProvider(ObjectMapper objectMapper, String mcpEndpoint,
                                                 Supplier<String> sessionIdProvider) {
        this.objectMapper = Utils.requireNonNullElse(objectMapper, DEFAULT_OBJECT_MAPPER);
        this.mcpEndpoint = Utils.requireNonNullElse(mcpEndpoint, DEFAULT_MCP_ENDPOINT);
        this.sessionIdProvider = Utils.requireNonNullElse(sessionIdProvider, DEFAULT_SESSION_ID_PROVIDER);
    }

    /**
     * <ul>
     * <li>Manages server-client sessions, including transport registration.
     * <li>Handles HTTP requests and HTTP/SSE responses and streams.
     * </ul>
     */
    public StreamableHttpServerTransportProvider() {
        /*
         * objectMapper ObjectMapper - Default:com.fasterxml.jackson.databind.ObjectMapper
         * mcpEndpoint String - Default: '/mcp'
         * sessionIdProvider Supplier(String) - Default: UUID.randomUUID().toString()
         */
        this(null, null, null);
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        // Required but not used for this implementation
    }

    public void setStreamableHttpSessionFactory(McpServerSession.StreamableHttpSessionFactory sessionFactory) {
        this.streamableHttpSessionFactory = sessionFactory;
    }

    public void setSessionHandler(SessionHandler sessionHandler) {
        this.sessionHandler = sessionHandler;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins.clear();
        this.allowedOrigins.addAll(allowedOrigins);
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            logger.debug("No active sessions to broadcast message to");
            return Mono.empty();
        }

        logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

        return Flux.fromIterable(sessions.values())
                .flatMap(session -> session.sendNotification(method, params).doOnError(e -> {
                    logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
                    if (sessionHandler != null) {
                        sessionHandler.onSendNotificationError(session.getId(), e);
                    }
                }).onErrorComplete())
                .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            isClosing.set(true);
            logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
            return Flux.fromIterable(sessions.values())
                    .flatMap(session -> session.closeGracefully()
                            .doOnError(e -> logger.error("Error closing session {}: {}", session.getId(), e.getMessage()))
                            .onErrorComplete())
                    .then();
        });
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String requestURI = request.getRequestURI();
        logger.info("GET request received for URI: '{}' with headers: {}", requestURI, extractHeaders(request));

        if (!validateOrigin(request, response) || !validateEndpoint(requestURI, response)
                || !validateNotClosing(response)) {
            return;
        }

        String acceptHeader = request.getHeader(ACCEPT_HEADER);
        if (acceptHeader == null || !acceptHeader.contains(TEXT_EVENT_STREAM)) {
            logger.debug("Accept header missing or does not include {}", TEXT_EVENT_STREAM);
            sendErrorResponse(response, "Accept header must include text/event-stream");
            return;
        }

        String sessionId = request.getHeader(SESSION_ID_HEADER);
        if (sessionId == null) {
            sendErrorResponse(response, "Session ID missing in request header");
            return;
        }

        McpServerSession session = sessions.get(sessionId);
        if (session == null) {
            handleSessionNotFound(sessionId, request, response);
            return;
        }

        // Delayed until version negotiation is implemented.
        /*
         * if (session.getState().equals(session.STATE_INITIALIZED) &&
         * request.getHeader(PROTOCOL_VERSION_HEADER) == null) {
         * sendErrorResponse(response, "Protocol version missing in request header"); }
         */

        // Set up SSE connection
        response.setContentType(TEXT_EVENT_STREAM);
        response.setCharacterEncoding(UTF_8);
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_NO_CACHE);
        response.setHeader(CONNECTION_HEADER, CONNECTION_KEEP_ALIVE);
        response.setHeader(SESSION_ID_HEADER, sessionId);

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);

        String lastEventId = request.getHeader(LAST_EVENT_ID_HEADER);

        if (lastEventId == null) { // Just opening a listening stream
            SseTransport sseTransport = new SseTransport(objectMapper, response, asyncContext, lastEventId,
                    session.LISTENING_TRANSPORT, sessionId);
            session.registerTransport(session.LISTENING_TRANSPORT, sseTransport);
            logger.debug("Registered SSE transport {} for session {}", session.LISTENING_TRANSPORT, sessionId);
        }
        else { // Asking for a stream to replay events from a previous request
            String transportId = Utils.getAndIncrement();
            SseTransport sseTransport = new SseTransport(objectMapper, response, asyncContext, lastEventId,
                    transportId, sessionId);
            session.registerTransport(transportId, sseTransport);
            logger.debug("Registered SSE transport {} for session {}", session.LISTENING_TRANSPORT, sessionId);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String requestURI = request.getRequestURI();
        logger.info("POST request received for URI: '{}' with headers: {}", requestURI, extractHeaders(request));

        if (!validateOrigin(request, response) || !validateEndpoint(requestURI, response)
                || !validateNotClosing(response)) {
            return;
        }

        String acceptHeader = request.getHeader(ACCEPT_HEADER);
        if (acceptHeader == null
                || (!acceptHeader.contains(APPLICATION_JSON) || !acceptHeader.contains(TEXT_EVENT_STREAM))) {
            logger.debug("Accept header validation failed. Header: {}", acceptHeader);
            sendErrorResponse(response, "Accept header must include both application/json and text/event-stream");
            return;
        }

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);

        StringBuilder body = new StringBuilder();
        ServletInputStream inputStream = request.getInputStream();

        inputStream.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() throws IOException {
                int len;
                byte[] buffer = new byte[1024];
                while (inputStream.isReady() && (len = inputStream.read(buffer)) != -1) {
                    body.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                }
            }

            @Override
            public void onAllDataRead() throws IOException {
                try {
                    logger.debug("Parsing JSON-RPC message: {}", body);
                    JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

                    boolean isInitializeRequest = false;
                    String sessionId = request.getHeader(SESSION_ID_HEADER);

                    if (message instanceof McpSchema.JSONRPCRequest) {
                        McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) message;
                        if (McpSchema.METHOD_INITIALIZE.equals(req.getMethod())) {
                            isInitializeRequest = true;
                            logger.debug("Detected initialize request");
                            if (sessionId == null) {
                                sessionId = sessionIdProvider.get();
                                logger.debug("Created new session ID for initialize request: {}", sessionId);
                            }
                        }
                    }

                    if (!isInitializeRequest && sessionId == null) {
                        sendErrorResponse(response, "Session ID missing in request header");
                        asyncContext.complete();
                        return;
                    }

                    McpServerSession session = getOrCreateSession(sessionId, isInitializeRequest);
                    if (session == null) {
                        logger.error("Failed to create session for sessionId: {}", sessionId);
                        handleSessionNotFound(sessionId, request, response);
                        asyncContext.complete();
                        return;
                    }

                    // Delayed until version negotiation is implemented.
                    /*
                     * if (session.getState().equals(session.STATE_INITIALIZED) &&
                     * request.getHeader(PROTOCOL_VERSION_HEADER) == null) {
                     * sendErrorResponse(response,
                     * "Protocol version missing in request header"); }
                     */

                    logger.debug("Using session: {}", sessionId);

                    response.setHeader(SESSION_ID_HEADER, sessionId);

                    // Determine response type and create appropriate transport if needed
                    ResponseType responseType = detectResponseType(message, session);
                    final String transportId;
                    final Object id;
                    if (message instanceof JSONRPCRequest) {
                        JSONRPCRequest req = (JSONRPCRequest) message;
                        id = req.getId();
                    }
                    else if (message instanceof JSONRPCResponse) {
                        JSONRPCResponse resp = (JSONRPCResponse) message;
                        id = resp.getId();
                    }
                    else {
                        id = null;
                    }
                    if (id instanceof String) {
                        transportId = (String) id;
                    }
                    else if (id instanceof Integer) {
                        transportId = id.toString();
                    }
                    else {
                        transportId = null;
                    }

                    if (responseType == ResponseType.STREAM) {
                        logger.debug("Handling STREAM response type");
                        response.setContentType(TEXT_EVENT_STREAM);
                        response.setCharacterEncoding(UTF_8);
                        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_NO_CACHE);
                        response.setHeader(CONNECTION_HEADER, CONNECTION_KEEP_ALIVE);

                        SseTransport sseTransport = new SseTransport(objectMapper, response, asyncContext, null,
                                transportId, sessionId);
                        session.registerTransport(transportId, sseTransport);
                    }
                    else {
                        logger.debug("Handling IMMEDIATE response type");
                        // Only set content type for requests, not notifications
                        if (message instanceof McpSchema.JSONRPCRequest) {
                            logger.debug("Setting content type to APPLICATION_JSON for request response");
                            response.setContentType(APPLICATION_JSON);
                        }
                        else {
                            logger.debug("Not setting content type for notification (empty response expected)");
                        }

                        if (transportId != null) { // Not needed for notifications (null
                            // transportId)
                            HttpTransport httpTransport = new HttpTransport(objectMapper, response, asyncContext);
                            session.registerTransport(transportId, httpTransport);
                        }
                    }

                    // Handle the message
                    logger.debug("About to handle message: {} with transport: {}", message.getClass().getSimpleName(),
                            transportId);

                    // For notifications, we need to handle the HTTP response manually
                    // since no JSON response is sent
                    if (message instanceof McpSchema.JSONRPCNotification) {
                        session.handle(message).doOnSuccess(v -> {
                            logger.debug("Message handling completed successfully for transport: {}", transportId);
                            logger.debug("[NOTIFICATION] Sending empty HTTP response for notification");
                            try {
                                if (!response.isCommitted()) {
                                    response.setStatus(HttpServletResponse.SC_OK);
                                    response.setCharacterEncoding("UTF-8");
                                }
                                asyncContext.complete();
                            }
                            catch (Exception e) {
                                logger.error("Failed to send notification response: {}", e.getMessage());
                                asyncContext.complete();
                            }
                        }).doOnError(e -> {
                            logger.error("Error in message handling: {}", e.getMessage(), e);
                            asyncContext.complete();
                        }).doFinally(signalType -> {
                            logger.debug("Unregistering transport: {} with signal: {}", transportId, signalType);
                            session.unregisterTransport(transportId);
                        }).contextWrite(Context.of(MCP_SESSION_ID, sessionId)).subscribe();
                    }
                    else {
                        // For requests, let the transport handle the response
                        session.handle(message)
                                .doOnSuccess(v -> logger.info("Message handling completed successfully for transport: {}",
                                        transportId))
                                .doOnError(e -> logger.error("Error in message handling: {}", e.getMessage(), e))
                                .doFinally(signalType -> {
                                    logger.debug("Unregistering transport: {} with signal: {}", transportId, signalType);
                                    session.unregisterTransport(transportId);
                                })
                                .contextWrite(Context.of(MCP_SESSION_ID, sessionId))
                                .subscribe(null, error -> {
                                    logger.error("Error in message handling chain: {}", error.getMessage(), error);
                                    asyncContext.complete();
                                });
                    }

                }
                catch (Exception e) {
                    logger.error("Error processing message: ", e);
                    sendErrorResponse(response, "Invalid JSON-RPC message: " + e.getMessage());
                    asyncContext.complete();
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Error reading request body: ", t);
                try {
                    sendErrorResponse(response, "Error reading request: " + t.getMessage());
                }
                catch (IOException e) {
                    logger.error("Failed to write error response", e);
                }
                asyncContext.complete();
            }
        });
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String requestURI = request.getRequestURI();
        if (!requestURI.endsWith(mcpEndpoint)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String sessionId = request.getHeader(SESSION_ID_HEADER);
        if (sessionId == null) {
            sendErrorResponse(response, "Session ID missing in request header");
            return;
        }

        McpServerSession session = sessions.remove(sessionId);
        if (session == null) {
            handleSessionNotFound(sessionId, request, response);
            return;
        }

        session.closeGracefully().contextWrite(Context.of(MCP_SESSION_ID, sessionId)).subscribe();
        logger.debug("Session closed: {}", sessionId);
        if (sessionHandler != null) {
            sessionHandler.onSessionClose(sessionId);
        }

        response.setStatus(HttpServletResponse.SC_OK);
    }

    private boolean validateOrigin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!allowedOrigins.isEmpty()) {
            String origin = request.getHeader(ORIGIN_HEADER);
            if (!allowedOrigins.contains(origin)) {
                logger.debug("Origin header does not match allowed origins: '{}'", origin);
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            else {
                response.setHeader(ALLOW_ORIGIN_HEADER, origin);
            }
        }
        else {
            response.setHeader(ALLOW_ORIGIN_HEADER, ALLOW_ORIGIN_DEFAULT_VALUE);
        }
        return true;
    }

    private boolean validateEndpoint(String requestURI, HttpServletResponse response) throws IOException {
        if (!requestURI.endsWith(mcpEndpoint)) {
            logger.debug("URI does not match MCP endpoint: '{}'", mcpEndpoint);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        return true;
    }

    private boolean validateNotClosing(HttpServletResponse response) throws IOException {
        if (isClosing.get()) {
            logger.debug("Server is shutting down, rejecting request");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
            return false;
        }
        return true;
    }

    protected McpServerSession getOrCreateSession(String sessionId, boolean createIfMissing) {
        McpServerSession session = sessions.get(sessionId);
        logger.debug("Looking for session: {}, found: {}", sessionId, session != null);
        if (session == null && createIfMissing) {
            logger.debug("Creating new session: {}", sessionId);
            session = streamableHttpSessionFactory.create(sessionId);
            sessions.put(sessionId, session);
            logger.debug("Created new session: {}", sessionId);
            if (sessionHandler != null) {
                sessionHandler.onSessionCreate(sessionId, null);
            }
        }
        return session;
    }

    private ResponseType detectResponseType(McpSchema.JSONRPCMessage message, McpServerSession session) {
        if (message instanceof McpSchema.JSONRPCRequest) {
            McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) message;
            if (McpSchema.METHOD_INITIALIZE.equals(request.getMethod())) {
                return ResponseType.IMMEDIATE;
            }

            // Check if handler returns Flux (streaming) or Mono (immediate)
            McpServerSession.RequestHandler<?> handler = session.getRequestHandler(request.getMethod());
            if (handler != null && handler instanceof McpServerSession.StreamingRequestHandler) {
                return ResponseType.STREAM;
            }
            return ResponseType.IMMEDIATE;
        }
        else {
            return ResponseType.IMMEDIATE;
        }
    }

    private void handleSessionNotFound(String sessionId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        sendErrorResponse(response, "Session not found: " + sessionId);
        if (sessionHandler != null) {
            sessionHandler.onSessionNotFound(sessionId, request, response);
        }
    }

    private void sendErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setContentType(APPLICATION_JSON);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.getWriter().write(createErrorJson(message));
    }

    private String createErrorJson(String message) {
        try {
            return objectMapper.writeValueAsString(new McpError(message));
        }
        catch (IOException e) {
            logger.error("Failed to serialize error message", e);
            return "{\"error\":\"" + message + "\"}";
        }
    }

    @Override
    public void destroy() {
        closeGracefully().block();
        super.destroy();
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private ObjectMapper objectMapper = DEFAULT_OBJECT_MAPPER;

        private String mcpEndpoint = DEFAULT_MCP_ENDPOINT;

        private Supplier<String> sessionIdProvider = DEFAULT_SESSION_ID_PROVIDER;

        public Builder withObjectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "ObjectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder withMcpEndpoint(String mcpEndpoint) {
            Assert.hasText(mcpEndpoint, "MCP endpoint must not be empty");
            this.mcpEndpoint = mcpEndpoint;
            return this;
        }

        public Builder withSessionIdProvider(Supplier<String> sessionIdProvider) {
            Assert.notNull(sessionIdProvider, "SessionIdProvider must not be null");
            this.sessionIdProvider = sessionIdProvider;
            return this;
        }

        public StreamableHttpServerTransportProvider build() {
            return new StreamableHttpServerTransportProvider(objectMapper, mcpEndpoint, sessionIdProvider);
        }

    }

    private enum ResponseType {

        IMMEDIATE, STREAM

    }

    /**
     * SSE transport implementation.
     */
    private static class SseTransport implements McpServerTransport {

        private static final Logger logger = LoggerFactory.getLogger(SseTransport.class);

        private final ObjectMapper objectMapper;

        private final HttpServletResponse response;

        private final AsyncContext asyncContext;

        private final Sinks.Many<SseEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

        private final Map<String, SseEvent> eventHistory = new ConcurrentHashMap<>();

        private final String id;

        private final String sessionId;

        public SseTransport(ObjectMapper objectMapper, HttpServletResponse response, AsyncContext asyncContext,
                            String lastEventId, String transportId, String sessionId) {
            this.objectMapper = objectMapper;
            this.response = response;
            this.asyncContext = asyncContext;
            this.id = transportId;
            this.sessionId = sessionId;

            setupSseStream(lastEventId);
        }

        private void setupSseStream(String lastEventId) {
            try {
                PrintWriter writer = response.getWriter();

                eventSink.asFlux().doOnNext(event -> {
                    try {
                        if (event.getId() != null) {
                            writer.write("id: " + event.getId() + "\n");
                        }
                        if (event.getEvent() != null) {
                            writer.write("event: " + event.getEvent() + "\n");
                        }
                        writer.write("data: " + event.getData() + "\n\n");
                        writer.flush();

                        if (writer.checkError()) {
                            throw new IOException("Client disconnected");
                        }
                    }
                    catch (IOException e) {
                        logger.debug("Error writing to SSE stream: {}", e.getMessage());
                        asyncContext.complete();
                    }
                }).doOnComplete(() -> {
                    try {
                        writer.close();
                    }
                    finally {
                        asyncContext.complete();
                    }
                }).doOnError(e -> {
                    logger.error("Error in SSE stream: {}", e.getMessage());
                    asyncContext.complete();
                }).contextWrite(Context.of(MCP_SESSION_ID, response.getHeader(SESSION_ID_HEADER))).subscribe();

                // Replay events if requested
                if (lastEventId != null) {
                    replayEventsAfter(lastEventId);
                }

            }
            catch (IOException e) {
                logger.error("Failed to setup SSE stream: {}", e.getMessage());
                asyncContext.complete();
            }
        }

        private void replayEventsAfter(String lastEventId) {
            try {
                McpServerSession session = sessions.get(sessionId);
                String transportIdOfLastEventId = session.getTransportIdForEvent(lastEventId);
                Map<String, SseEvent> transportEventHistory = session
                        .getTransportEventHistory(transportIdOfLastEventId);
                List<String> eventIds = transportEventHistory.keySet()
                        .stream()
                        .map(Long::parseLong)
                        .filter(key -> key > Long.parseLong(lastEventId))
                        .sorted()
                        .map(String::valueOf)
                        .collect(Collectors.toList());
                for (String eventId : eventIds) {
                    SseEvent event = transportEventHistory.get(eventId);
                    if (event != null) {
                        eventSink.tryEmitNext(event);
                    }
                }
            }
            catch (NumberFormatException e) {
                logger.warn("Invalid last event ID: {}", lastEventId);
            }
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            try {
                String jsonText = objectMapper.writeValueAsString(message);
                String eventId = sessions.get(sessionId).incrementAndGetEventId(id);
                SseEvent event = new SseEvent(eventId, MESSAGE_EVENT_TYPE, jsonText);

                eventHistory.put(eventId, event);
                logger.debug("Sending SSE event {}: {}", eventId, jsonText);
                eventSink.tryEmitNext(event);

                if (message instanceof McpSchema.JSONRPCResponse) {
                    logger.debug("Completing SSE stream after sending response");
                    eventSink.tryEmitComplete();
                    sessions.get(sessionId).setTransportEventHistory(id, eventHistory);
                }

                return Mono.empty();
            }
            catch (Exception e) {
                logger.error("Failed to send message: {}", e.getMessage());
                return Mono.error(e);
            }
        }

        /**
         * Sends a stream of messages for Flux responses.
         */
        public Mono<Void> sendMessageStream(Flux<JSONRPCMessage> messageStream) {
            return messageStream.doOnNext(message -> {
                try {
                    String jsonText = objectMapper.writeValueAsString(message);
                    String eventId = sessions.get(sessionId).incrementAndGetEventId(id);
                    SseEvent event = new SseEvent(eventId, MESSAGE_EVENT_TYPE, jsonText);

                    eventHistory.put(eventId, event);
                    logger.debug("Sending SSE stream event {}: {}", eventId, jsonText);
                    eventSink.tryEmitNext(event);
                }
                catch (Exception e) {
                    logger.error("Failed to send stream message: {}", e.getMessage());
                    eventSink.tryEmitError(e);
                }
            }).doOnComplete(() -> {
                logger.debug("Completing SSE stream after sending all stream messages");
                eventSink.tryEmitComplete();
                sessions.get(sessionId).setTransportEventHistory(id, eventHistory);
            }).then();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                eventSink.tryEmitComplete();
                if (sessions.get(sessionId) != null) {
                    sessions.get(sessionId).setTransportEventHistory(id, eventHistory);
                } else {
                    logger.warn("session not found for SSE transport close: {}", sessionId);
                }
                logger.debug("SSE transport closed gracefully");
            });
        }
    }

    /**
     * HTTP transport implementation for immediate responses.
     */
    private static class HttpTransport implements McpServerTransport {

        private static final Logger logger = LoggerFactory.getLogger(HttpTransport.class);

        private final ObjectMapper objectMapper;

        private final HttpServletResponse response;

        private final AsyncContext asyncContext;

        public HttpTransport(ObjectMapper objectMapper, HttpServletResponse response, AsyncContext asyncContext) {
            this.objectMapper = objectMapper;
            this.response = response;
            this.asyncContext = asyncContext;
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    if (response.isCommitted()) {
                        logger.warn("Response already committed, cannot send message");
                        return;
                    }

                    response.setCharacterEncoding("UTF-8");
                    response.setStatus(HttpServletResponse.SC_OK);

                    // For notifications, don't write any content (empty response)
                    if (message instanceof McpSchema.JSONRPCNotification) {
                        logger.debug("Sending empty 200 response for notification");
                        // Just complete the response with no content
                    }
                    else {
                        // For requests/responses, write JSON content
                        String jsonText = objectMapper.writeValueAsString(message);
                        PrintWriter writer = response.getWriter();
                        writer.write(jsonText);
                        writer.flush();
                        logger.debug("Successfully sent immediate response: {}", jsonText);
                    }
                }
                catch (Exception e) {
                    logger.error("Failed to send message: {}", e.getMessage(), e);
                    try {
                        if (!response.isCommitted()) {
                            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        }
                    }
                    catch (Exception ignored) {
                    }
                }
                finally {
                    asyncContext.complete();
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                try {
                    asyncContext.complete();
                }
                catch (Exception e) {
                    logger.debug("Error completing async context: {}", e.getMessage());
                }
                logger.debug("HTTP transport closed gracefully");
            });
        }
    }
}