/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link HttpClientSseClientTransport} class.
 * 使用已启动的sse服务替代Docker容器进行测试
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class HttpClientSseClientTransportTests {

    private static final String CUSTOM_SSE_ENDPOINT = "/sse";

    private static final String host = "http://localhost:8080";

    private TestHttpClientSseClientTransport transport;

    static class TestEventSourceListener extends EventSourceListener {

        private final List<Consumer<String>> messageHandlers = new ArrayList<>();

        public void onMessage(Consumer<String> handler) {
            messageHandlers.add(handler);
        }

        @Override
        public void onOpen(EventSource eventSource, Response response) {
            // 可选：连接建立时触发
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            for (Consumer<String> handler : messageHandlers) {
                handler.accept(data); // 触发消费者处理数据
            }
        }

        @Override
        public void onClosed(EventSource eventSource) {
            // 可选：连接关闭时触发
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            if (t != null) {
                t.printStackTrace();
            }
        }
    }

    // Test class to access protected methods
    static class TestHttpClientSseClientTransport extends HttpClientSseClientTransport {

        private final AtomicInteger inboundMessageCount = new AtomicInteger(0);
        private final TestEventSourceListener listener = new TestEventSourceListener();

        public TestHttpClientSseClientTransport(final String baseUri) {
            super(new OkHttpClient.Builder().build(), new Request.Builder(), baseUri, CUSTOM_SSE_ENDPOINT, new ObjectMapper());
        }

        public int getInboundMessageCount() {
            return inboundMessageCount.get();
        }

        public void simulateEndpointEvent(String jsonMessage) {
            // 触发监听器中的 onEvent 方法
            listener.onEvent(null, null, "endpoint", jsonMessage);
            inboundMessageCount.incrementAndGet();
        }

        public void simulateMessageEvent(String jsonMessage) {
            // 触发监听器中的 onEvent 方法
            listener.onEvent(null, null, "message", jsonMessage);
            inboundMessageCount.incrementAndGet();
        }
    }

    @BeforeEach
    void setUp() {
        transport = new TestHttpClientSseClientTransport(host);
        transport.connect(Function.identity()).block();
    }

    @AfterEach
    void afterEach() {
        if (transport != null) {
            assertThatCode(() -> transport.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
        }
    }

    @Test
    void testMessageProcessing() {
        // Create a test message
        JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
                Collections.singletonMap("key", "value"));

        // Simulate receiving the message
        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"method\": \"test-method\"," +
                "\"id\": \"test-id\"," +
                "\"params\": {\"key\": \"value\"}" +
                "}");

        // Subscribe to messages and verify
        StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

        assertThat(transport.getInboundMessageCount()).isEqualTo(1);
    }

    @Test
    void testResponseMessageProcessing() {
        // Simulate receiving a response message
        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"id\": \"test-id\"," +
                "\"result\": {\"status\": \"success\"}" +
                "}");

        // Create and send a request message
        JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
                Collections.singletonMap("key", "value"));

        // Verify message handling
        StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

        assertThat(transport.getInboundMessageCount()).isEqualTo(1);
    }

    @Test
    void testErrorMessageProcessing() {
        // Simulate receiving an error message
        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"id\": \"test-id\"," +
                "\"error\": {" +
                "\"code\": -32600," +
                "\"message\": \"Invalid Request\"" +
                "}" +
                "}");

        // Create and send a request message
        JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
                Collections.singletonMap("key", "value"));

        // Verify message handling
        StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

        assertThat(transport.getInboundMessageCount()).isEqualTo(1);
    }

    @Test
    void testNotificationMessageProcessing() {
        // Simulate receiving a notification message (no id)
        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"method\": \"update\"," +
                "\"params\": {\"status\": \"processing\"}" +
                "}");

        // Verify the notification was processed
        assertThat(transport.getInboundMessageCount()).isEqualTo(1);
    }

    @Test
    void testGracefulShutdown() {
        // Test graceful shutdown
        StepVerifier.create(transport.closeGracefully()).verifyComplete();

        // Create a test message
        JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
                Collections.singletonMap("key", "value"));

        // Verify message is not processed after shutdown
        StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

        // Message count should remain 0 after shutdown
        assertThat(transport.getInboundMessageCount()).isZero();
    }

    @Test
    void testRetryBehavior() {
        // Create a client that simulates connection failures
        HttpClientSseClientTransport failingTransport = HttpClientSseClientTransport.builder("http://non-existent-host")
                .build();

        // Verify that the transport attempts to reconnect
        StepVerifier.create(Mono.delay(Duration.ofSeconds(2))).expectNextCount(1).verifyComplete();

        // Clean up
        failingTransport.closeGracefully().block();
    }

    @Test
    void testMultipleMessageProcessing() {
        // Simulate receiving multiple messages in sequence
        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"method\": \"method1\"," +
                "\"id\": \"id1\"," +
                "\"params\": {\"key\": \"value1\"}" +
                "}");

        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"method\": \"method2\"," +
                "\"id\": \"id2\"," +
                "\"params\": {\"key\": \"value2\"}" +
                "}");

        // Create and send corresponding messages
        JSONRPCRequest message1 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method1", "id1",
                Collections.singletonMap("key", "value1"));

        JSONRPCRequest message2 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method2", "id2",
                Collections.singletonMap("key", "value2"));

        // Verify both messages are processed
        StepVerifier.create(transport.sendMessage(message1).then(transport.sendMessage(message2))).verifyComplete();

        // Verify message count
        assertThat(transport.getInboundMessageCount()).isEqualTo(2);
    }

    @Test
    void testMessageOrderPreservation() {
        // Simulate receiving messages in a specific order
        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"method\": \"first\"," +
                "\"id\": \"1\"," +
                "\"params\": {\"sequence\": 1}" +
                "}");

        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"method\": \"second\"," +
                "\"id\": \"2\"," +
                "\"params\": {\"sequence\": 2}" +
                "}");

        transport.simulateMessageEvent("{" +
                "\"jsonrpc\": \"2.0\"," +
                "\"method\": \"third\"," +
                "\"id\": \"3\"," +
                "\"params\": {\"sequence\": 3}" +
                "}");

        // Verify message count and order
        assertThat(transport.getInboundMessageCount()).isEqualTo(3);
    }

    @Test
    void testCustomizeClient() {
        // Create an atomic boolean to verify the customizer was called
        AtomicBoolean customizerCalled = new AtomicBoolean(false);

        // Create a transport with the customizer
        HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
                .customizeClient(builder -> {
                    builder.setProtocols$okhttp(Collections.singletonList(Protocol.HTTP_1_1));
                    customizerCalled.set(true);
                })
                .build();

        // Verify the customizer was called
        assertThat(customizerCalled.get()).isTrue();

        // Clean up
        customizedTransport.closeGracefully().block();
    }

    @Test
    void testCustomizeRequest() {
        // Create an atomic boolean to verify the customizer was called
        AtomicBoolean customizerCalled = new AtomicBoolean(false);

        // Create a reference to store the custom header value
        AtomicReference<String> headerName = new AtomicReference<>();
        AtomicReference<String> headerValue = new AtomicReference<>();

        // Create a transport with the customizer
        HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
                // Create a request customizer that adds a custom header
                .customizeRequest(builder -> {
                    builder.header("X-Custom-Header", "test-value");
                    customizerCalled.set(true);

                    // Create a new request to verify the header was set
                    Request request = null;
                    try {
                        request = builder.url(URI.create("http://example.com").toURL()).build();

                        headerName.set("X-Custom-Header");
                        headerValue.set(request.header("X-Custom-Header"));
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();

        // Verify the customizer was called
        assertThat(customizerCalled.get()).isTrue();

        // Verify the header was set correctly
        assertThat(headerName.get()).isEqualTo("X-Custom-Header");
        assertThat(headerValue.get()).isEqualTo("test-value");

        // Clean up
        customizedTransport.closeGracefully().block();
    }

    @Test
    void testChainedCustomizations() {
        // Create atomic booleans to verify both customizers were called
        AtomicBoolean clientCustomizerCalled = new AtomicBoolean(false);
        AtomicBoolean requestCustomizerCalled = new AtomicBoolean(false);

        // Create a transport with both customizers chained
        HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
                .customizeClient(builder -> {
                    builder.connectTimeout(Duration.ofSeconds(30));
                    clientCustomizerCalled.set(true);
                })
                .customizeRequest(builder -> {
                    builder.header("X-Api-Key", "test-api-key");
                    requestCustomizerCalled.set(true);
                })
                .build();

        // Verify both customizers were called
        assertThat(clientCustomizerCalled.get()).isTrue();
        assertThat(requestCustomizerCalled.get()).isTrue();

        // Clean up
        customizedTransport.closeGracefully().block();
    }

    @Disabled
    @Test
    @SuppressWarnings("unchecked")
    void testResolvingClientEndpoint() {
        OkHttpClient httpClient = Mockito.mock(OkHttpClient.class);
        /*
         * FIXME org.mockito.exceptions.base.MockitoException:
         * Cannot mock/spy class okhttp3.Response
         * Mockito cannot mock/spy because :
         *  - final class
         */
        Response httpResponse = Mockito.mock(Response.class);
        CompletableFuture<Response> future = new CompletableFuture<>();
        future.complete(httpResponse);

        // 异步调用
        Call mockCall = Mockito.mock(Call.class);
        doAnswer(invocation -> {
            Callback callback = invocation.getArgument(0);
            callback.onResponse(mockCall, httpResponse);
            return null;
        }).when(mockCall).enqueue(any(Callback.class));

        // 模拟 newCall 返回 mockCall
        Mockito.when(httpClient.newCall(any(Request.class))).thenReturn(mockCall);

        HttpClientSseClientTransport transport = new HttpClientSseClientTransport(httpClient, new Request.Builder(),
                "http://example.com", "http://example.com/sse", new ObjectMapper());

        transport.connect(Function.identity());

        ArgumentCaptor<Request> httpRequestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newCall(httpRequestCaptor.capture());
        assertThat(httpRequestCaptor.getValue().url().uri()).isEqualTo(URI.create("http://example.com/sse"));

        transport.closeGracefully().block();
    }
}