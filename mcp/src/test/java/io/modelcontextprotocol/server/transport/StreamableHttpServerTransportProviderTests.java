/*
 * copy from https://github.com/modelcontextprotocol/java-sdk/pull/290/files and refactor use jdk8.
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StreamableHttpServerTransportProvider}.
 */
class StreamableHttpServerTransportProviderTests {

    private StreamableHttpServerTransportProvider transportProvider;

    private McpServerSession.Factory sessionFactory;

    private McpServerSession mockSession;

    private McpServerTransport capturedTransport;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();

        mockSession = mock(McpServerSession.class);
        sessionFactory = mock(McpServerSession.Factory.class);

        when(sessionFactory.create(any(McpServerTransport.class))).thenAnswer(invocation -> {
            capturedTransport = invocation.getArgument(0);
            return mockSession;
        });
        when(mockSession.closeGracefully()).thenReturn(Mono.empty());
        when(mockSession.sendNotification(any(), any())).thenReturn(Mono.empty());
        when(mockSession.handle(any(JSONRPCMessage.class))).thenReturn(Mono.empty());
        when(mockSession.getId()).thenReturn("test-session-id");

        transportProvider = new StreamableHttpServerTransportProvider(objectMapper, "/mcp", null);
        transportProvider.setSessionFactory(sessionFactory);
    }

    @Test
    void shouldNotifyClients() {
        String sessionId = UUID.randomUUID().toString();
        Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();
        sessions.put(sessionId, mockSession);

        // Use reflection to set the sessions map in the transport provider
        try {
            java.lang.reflect.Field sessionsField = StreamableHttpServerTransportProvider.class
                    .getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            sessionsField.set(transportProvider, sessions);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to set sessions field", e);
        }

        String method = "testNotification";
        Map<String, Object> params = Collections.singletonMap("key", "value");
        StepVerifier.create(transportProvider.notifyClients(method, params)).verifyComplete();

        verify(mockSession).sendNotification(eq(method), eq(params));
    }

    @Test
    void shouldCloseGracefully() {
        String sessionId = UUID.randomUUID().toString();
        Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();
        sessions.put(sessionId, mockSession);

        // Use reflection to set the sessions map in the transport provider
        try {
            java.lang.reflect.Field sessionsField = StreamableHttpServerTransportProvider.class
                    .getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            sessionsField.set(transportProvider, sessions);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to set sessions field", e);
        }

        StepVerifier.create(transportProvider.closeGracefully()).verifyComplete();

        verify(mockSession).closeGracefully();
    }

    @Test
    void shouldHandlePostRequestForInitialize() throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);

        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader("Accept")).thenReturn("application/json, text/event-stream");
        when(request.getHeader(StreamableHttpServerTransportProvider.SESSION_ID_HEADER)).thenReturn(null);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));
        String initializeRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0.0\"}},\"id\":1}";
        when(request.getReader()).thenReturn(new java.io.BufferedReader(new java.io.StringReader(initializeRequest)));
        when(response.getWriter()).thenReturn(writer);
        AsyncContext asyncContext = mock(AsyncContext.class);
        when(request.startAsync()).thenReturn(asyncContext);

        transportProvider.doPost(request, response);

        verify(sessionFactory).create(any(McpServerTransport.class));
        ArgumentCaptor<JSONRPCMessage> messageCaptor = ArgumentCaptor.forClass(JSONRPCMessage.class);
        verify(mockSession).handle(messageCaptor.capture());
        JSONRPCMessage capturedMessage = messageCaptor.getValue();
        assertThat(capturedMessage).isInstanceOf(JSONRPCRequest.class);
        JSONRPCRequest capturedRequest = (JSONRPCRequest) capturedMessage;
        assertThat(capturedRequest.getMethod()).isEqualTo(McpSchema.METHOD_INITIALIZE);
        verify(response, atLeastOnce()).setHeader(eq(StreamableHttpServerTransportProvider.SESSION_ID_HEADER),
                anyString());
    }

    @Test
    void shouldHandlePostRequestWithExistingSession() throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter stringWriter = new StringWriter();
        String sessionId = UUID.randomUUID().toString();
        PrintWriter writer = new PrintWriter(stringWriter);
        Map<String, McpServerSession> sessions = new HashMap<>();
        sessions.put(sessionId, mockSession);

        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader("Accept")).thenReturn("application/json, text/event-stream");
        when(request.getHeader(StreamableHttpServerTransportProvider.SESSION_ID_HEADER)).thenReturn(sessionId);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));
        String toolCallRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"test-tool\",\"arguments\":{}},\"id\":2}";
        when(request.getReader()).thenReturn(new java.io.BufferedReader(new java.io.StringReader(toolCallRequest)));
        when(response.getWriter()).thenReturn(writer);

        // Use reflection to set the sessions map in the transport provider
        try {
            java.lang.reflect.Field sessionsField = StreamableHttpServerTransportProvider.class
                    .getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            sessionsField.set(transportProvider, sessions);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to set sessions field", e);
        }

        transportProvider.doPost(request, response);

        ArgumentCaptor<JSONRPCMessage> messageCaptor = ArgumentCaptor.forClass(JSONRPCMessage.class);
        verify(mockSession).handle(messageCaptor.capture());
        JSONRPCMessage capturedMessage = messageCaptor.getValue();
        assertThat(capturedMessage).isInstanceOf(JSONRPCRequest.class);
        JSONRPCRequest capturedRequest = (JSONRPCRequest) capturedMessage;
        assertThat(capturedRequest.getMethod()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
        verify(response).setHeader(eq(StreamableHttpServerTransportProvider.SESSION_ID_HEADER), eq(sessionId));
    }

    @Test
    void shouldHandleGetRequest() throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter stringWriter = new StringWriter();
        String sessionId = UUID.randomUUID().toString();
        AsyncContext asyncContext = mock(AsyncContext.class);
        PrintWriter writer = new PrintWriter(stringWriter);
        Map<String, McpServerSession> sessions = new HashMap<>();
        sessions.put(sessionId, mockSession);

        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader("Accept")).thenReturn("text/event-stream");
        when(request.getHeader(StreamableHttpServerTransportProvider.SESSION_ID_HEADER)).thenReturn(sessionId);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));
        when(request.startAsync()).thenReturn(asyncContext);
        when(response.getWriter()).thenReturn(writer);

        // Use reflection to set the sessions map in the transport provider
        try {
            java.lang.reflect.Field sessionsField = StreamableHttpServerTransportProvider.class
                    .getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            sessionsField.set(transportProvider, sessions);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to set sessions field", e);
        }

        transportProvider.doGet(request, response);

        verify(response).setContentType(StreamableHttpServerTransportProvider.TEXT_EVENT_STREAM);
        verify(response).setCharacterEncoding(StreamableHttpServerTransportProvider.UTF_8);
        verify(response).setHeader("Cache-Control", "no-cache");
        verify(response).setHeader("Connection", "keep-alive");
        verify(response).setHeader(eq(StreamableHttpServerTransportProvider.SESSION_ID_HEADER), eq(sessionId));
        verify(request).startAsync();
        verify(asyncContext).setTimeout(0);
    }

    @Test
    void shouldHandleDeleteRequest() throws IOException, ServletException {
        // Mock HTTP request and response
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        String sessionId = UUID.randomUUID().toString();
        Map<String, McpServerSession> sessions = new HashMap<>();
        sessions.put(sessionId, mockSession);

        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader(StreamableHttpServerTransportProvider.SESSION_ID_HEADER)).thenReturn(sessionId);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));
        when(response.getWriter()).thenReturn(writer);

        // Use reflection to set the sessions map in the transport provider
        try {
            java.lang.reflect.Field sessionsField = StreamableHttpServerTransportProvider.class
                    .getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            sessionsField.set(transportProvider, sessions);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to set sessions field", e);
        }

        transportProvider.doDelete(request, response);

        verify(mockSession).close();
        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertThat(sessions).isEmpty();
    }

    @Test
    void shouldSendMessageThroughTransport() {
        String sessionId = UUID.randomUUID().toString();
        Map<String, McpServerSession> sessions = new HashMap<>();
        sessions.put(sessionId, mockSession);

        // Use reflection to set the sessions map in the transport provider
        try {
            java.lang.reflect.Field sessionsField = StreamableHttpServerTransportProvider.class
                    .getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            sessionsField.set(transportProvider, sessions);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to set sessions field", e);
        }

        // Create a message to send through a mocked SSE stream
        Map<String, String> map1 = new HashMap<>();
        map1.put("name", "test-server");
        map1.put("version", "1.0.0");
        Map<String, Object> map2 = new HashMap<>();
        map2.put("protocolVersion", McpSchema.LATEST_PROTOCOL_VERSION);
        map2.put("serverInfo", map1);
        JSONRPCMessage message = new McpSchema.JSONRPCResponse("2.0", 1, map2, null);

        AtomicReference<String> capturedEventData = new AtomicReference<>();

        StreamableHttpServerTransportProvider.StreamableHttpSseStream mockSseStream = mock(
                StreamableHttpServerTransportProvider.StreamableHttpSseStream.class);
        doAnswer(invocation -> {
            String eventType = invocation.getArgument(0);
            String data = invocation.getArgument(1);
            assertThat(eventType).isEqualTo(StreamableHttpServerTransportProvider.MESSAGE_EVENT_TYPE);
            capturedEventData.set(data);
            return null;
        }).when(mockSseStream).sendEvent(anyString(), anyString());

        Map<String, StreamableHttpServerTransportProvider.StreamableHttpSseStream> sseStreams = new HashMap<>();
        sseStreams.put(sessionId, mockSseStream);
        try {
            java.lang.reflect.Field sseStreamsField = StreamableHttpServerTransportProvider.class
                    .getDeclaredField("sseStreams");
            sseStreamsField.setAccessible(true);
            sseStreamsField.set(transportProvider, sseStreams);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to set sseStreams field", e);
        }

        // Using reflection to access the private constructor
        McpServerTransport transport;
        try {
            Class<?> transportClass = Class.forName(
                    "io.modelcontextprotocol.server.transport.StreamableHttpServerTransportProvider$StreamableHttpServerTransport");
            java.lang.reflect.Constructor<?> constructor = transportClass
                    .getDeclaredConstructor(StreamableHttpServerTransportProvider.class, String.class);
            constructor.setAccessible(true);
            transport = (McpServerTransport) constructor.newInstance(transportProvider, sessionId);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to create transport", e);
        }

        StepVerifier.create(transport.sendMessage(message)).verifyComplete();
        verify(mockSseStream, times(1)).sendEvent(eq(StreamableHttpServerTransportProvider.MESSAGE_EVENT_TYPE),
                anyString());

        String eventData = capturedEventData.get();
        assertThat(eventData).isNotNull();
    }

    @Test
    void shouldHandleInvalidRequestURI() throws IOException, ServletException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getRequestURI()).thenReturn("/wrong-path");
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));

        transportProvider.doGet(request, response);
        transportProvider.doPost(request, response);
        transportProvider.doDelete(request, response);

        verify(response, times(3)).sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void shouldHandleMissingSessionId() throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);

        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader("Accept")).thenReturn("text/event-stream");
        when(request.getHeader(StreamableHttpServerTransportProvider.SESSION_ID_HEADER)).thenReturn(null);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));
        when(response.getWriter()).thenReturn(writer);

        // Execute GET request without Session ID (required)
        transportProvider.doGet(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(response).setContentType(StreamableHttpServerTransportProvider.APPLICATION_JSON);
        assertThat(stringWriter.toString()).contains("Session ID missing");
    }

    @Test
    void shouldHandleSessionNotFound() throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        String sessionId = UUID.randomUUID().toString();

        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader("Accept")).thenReturn("text/event-stream");
        when(request.getHeader(StreamableHttpServerTransportProvider.SESSION_ID_HEADER)).thenReturn(sessionId);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));
        when(response.getWriter()).thenReturn(writer);

        // Execute GET request with non-existent session ID
        transportProvider.doGet(request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        verify(response).setContentType(StreamableHttpServerTransportProvider.APPLICATION_JSON);
        assertThat(stringWriter.toString()).contains("Session not found");
    }

}