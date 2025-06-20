/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class HttpServletSseServerTransportProviderIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	McpClient.SyncSpec clientBuilder;

	private Tomcat tomcat;

	private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();

	@BeforeEach
	public void before() {
		// Create and configure the transport provider
		mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		this.clientBuilder = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build());
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@Test
	@Disabled
	void testCreateMessageWithoutSamplingCapabilities() {

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.createMessage(mock(McpSchema.CreateMessageRequest.class)).block();

					return Mono.just(mock(CallToolResult.class));
				});

		McpAsyncServer server = McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").tools(tool).build();

		try (
				// Create client without sampling capabilities
				McpSyncClient client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
					.build()) {

			assertThat(client.initialize()).isNotNull();

			try {
				client.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class)
					.hasMessage("Client must be configured with sampling capabilities");
			}
		}
		server.close();
	}

	@Test
	void testCreateMessageSuccess() {

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		CallToolResult callResponse = new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

			CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(Arrays.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(Collections.emptyList())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					StepVerifier.create(exchange.createMessage(createMessageRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.getRole()).isEqualTo(Role.USER);
						assertThat(result.getContent()).isInstanceOf(McpSchema.TextContent.class);
						assertThat(((McpSchema.TextContent) result.getContent()).getText()).isEqualTo("Test message");
						assertThat(result.getModel()).isEqualTo("MockModelName");
						assertThat(result.getStopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		McpAsyncServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}
		mcpServer.close();
	}

	@Test
	void testCreateMessageWithRequestTimeoutSuccess() throws InterruptedException {

		// Client

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

			CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(Arrays.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(Collections.emptyList())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					StepVerifier.create(exchange.createMessage(createMessageRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.getRole()).isEqualTo(Role.USER);
						assertThat(result.getContent()).isInstanceOf(McpSchema.TextContent.class);
						assertThat(((McpSchema.TextContent) result.getContent()).getText()).isEqualTo("Test message");
						assertThat(result.getModel()).isEqualTo("MockModelName");
						assertThat(result.getStopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		McpAsyncServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(3))
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testCreateMessageWithRequestTimeoutFail() throws InterruptedException {

		// Client

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

			CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(Arrays.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(Collections.emptyList())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					StepVerifier.create(exchange.createMessage(createMessageRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.getRole()).isEqualTo(Role.USER);
						assertThat(result.getContent()).isInstanceOf(McpSchema.TextContent.class);
						assertThat(((McpSchema.TextContent) result.getContent()).getText()).isEqualTo("Test message");
						assertThat(result.getModel()).isEqualTo("MockModelName");
						assertThat(result.getStopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		McpAsyncServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(1))
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
			mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
		}).withMessageContaining("Timeout");

		mcpClient.close();
		mcpServer.close();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@Test
	void testRootsSuccess() {
		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});

			// Remove a root
			mcpClient.removeRoot(roots.get(0).getUri());

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(Collections.singletonList(roots.get(1)));
			});

			// Add a new root
			Root root3 = new Root("uri3://", "root3");
			mcpClient.addRoot(root3);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(Arrays.asList(roots.get(1), root3));
			});

			mcpServer.close();
		}
	}

	@Test
	void testRootsWithoutCapability() {

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				});

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider).rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			// Attempt to list roots should fail
			try {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class).hasMessage("Roots not supported");
			}
		}

		mcpServer.close();
	}

	@Test
	void testRootsNotificationWithEmptyRootsList() {
		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(Collections.emptyList()) // Empty roots list
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});
		}

		mcpServer.close();
	}

	@Test
	void testRootsWithMultipleHandlers() {
		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef1.get()).containsAll(roots);
				assertThat(rootsRef2.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	@Test
	void testRootsServerCloseWithActiveSubscription() {
		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	String emptyJsonSchema = "{" +
			"\"$schema\": \"http://json-schema.org/draft-07/schema#\"," +
			"\"type\": \"object\"," +
			"\"properties\": {}" +
			"}";

	@Test
	void testToolCallSuccess() {

		CallToolResult callResponse = new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
			// perform a blocking call to a remote service
			Request req = new Request.Builder()
					.url("https://www.baidu.com/")
					.build();

			try (Response response = HTTP_CLIENT.newCall(req).execute()) {
				assert response.body() != null;
				String body = response.body().string();
				assertThat(body).isNotBlank();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			return callResponse;
		});

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().getTools()).contains(tool1.getTool());

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}

		mcpServer.close();
	}

	@Test
	void testToolListChangeHandlingSuccess() {

		CallToolResult callResponse = new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
			// perform a blocking call to a remote service
			Request req = new Request.Builder()
					.url("https://www.baidu.com/")
					.build();

			try (Response response = HTTP_CLIENT.newCall(req).execute()) {
				assert response.body() != null;
				String body = response.body().string();
				assertThat(body).isNotBlank();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			return callResponse;
		});

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			Request req = new Request.Builder()
					.url("https://www.baidu.com/")
					.build();

			try (Response response = HTTP_CLIENT.newCall(req).execute()) {
				assert response.body() != null;
				String body = response.body().string();
				assertThat(body).isNotBlank();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			rootsRef.set(toolsUpdate);
		}).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			assertThat(mcpClient.listTools().getTools()).contains(tool1.getTool());

			mcpServer.notifyToolsListChanged();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(Collections.singletonList(tool1.getTool()));
			});

			// Remove a tool
			mcpServer.removeTool("tool1");

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});

			// Add a new tool
			McpServerFeatures.SyncToolSpecification tool2 = new McpServerFeatures.SyncToolSpecification(
					new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema),
					(exchange, request) -> callResponse);

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(Collections.singletonList(tool2.getTool()));
			});
		}

		mcpServer.close();
	}

	@Test
	void testInitialize() {
		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------
	@Test
	void testLoggingNotification() {
		// Create a list to store received logging notifications
		List<McpSchema.LoggingMessageNotification> receivedNotifications = new ArrayList<>();

		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("logging-test", "Test logging notifications", emptyJsonSchema),
				(exchange, request) -> {

					// Create and send notifications with different levels

					// This should be filtered out (DEBUG < NOTICE)
					exchange
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.DEBUG)
							.logger("test-logger")
							.data("Debug message")
							.build())
						.block();

					// This should be sent (NOTICE >= NOTICE)
					exchange
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.NOTICE)
							.logger("test-logger")
							.data("Notice message")
							.build())
						.block();

					// This should be sent (ERROR > NOTICE)
					exchange
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.ERROR)
							.logger("test-logger")
							.data("Error message")
							.build())
						.block();

					// This should be filtered out (INFO < NOTICE)
					exchange
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.INFO)
							.logger("test-logger")
							.data("Another info message")
							.build())
						.block();

					// This should be sent (ERROR >= NOTICE)
					exchange
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.ERROR)
							.logger("test-logger")
							.data("Another error message")
							.build())
						.block();

					return Mono.just(new CallToolResult("Logging test completed", false));
				});

		McpAsyncServer mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().tools(true).build())
			.tools(tool)
			.build();
		try (
				// Create client with logging notification handler
				McpSyncClient mcpClient = clientBuilder.loggingConsumer(notification -> {
					receivedNotifications.add(notification);
				}).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Set minimum logging level to NOTICE
			mcpClient.setLoggingLevel(McpSchema.LoggingLevel.NOTICE);

			// Call the tool that sends logging notifications
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("logging-test", Collections.emptyMap()));
			assertThat(result).isNotNull();
			assertThat(result.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.getContent().get(0)).getText()).isEqualTo("Logging test completed");

			// Wait for notifications to be processed
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {

				System.out.println("Received notifications: " + receivedNotifications);

				// Should have received 3 notifications (1 NOTICE and 2 ERROR)
				assertThat(receivedNotifications).hasSize(3);

				Map<String, McpSchema.LoggingMessageNotification> notificationMap = receivedNotifications.stream()
					.collect(Collectors.toMap(n -> n.getData(), n -> n));

				// First notification should be NOTICE level
				assertThat(notificationMap.get("Notice message").getLevel()).isEqualTo(McpSchema.LoggingLevel.NOTICE);
				assertThat(notificationMap.get("Notice message").getLogger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Notice message").getData()).isEqualTo("Notice message");

				// Second notification should be ERROR level
				assertThat(notificationMap.get("Error message").getLevel()).isEqualTo(McpSchema.LoggingLevel.ERROR);
				assertThat(notificationMap.get("Error message").getLogger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Error message").getData()).isEqualTo("Error message");

				// Third notification should be ERROR level
				assertThat(notificationMap.get("Another error message").getLevel())
					.isEqualTo(McpSchema.LoggingLevel.ERROR);
				assertThat(notificationMap.get("Another error message").getLogger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Another error message").getData()).isEqualTo("Another error message");
			});
		}
		mcpServer.close();
	}

}
