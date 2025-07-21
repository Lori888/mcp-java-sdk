/*
 * 2025-07-16 copy from
 * https://github.com/modelcontextprotocol/java-sdk  main branch
 * mcp/src/main/java/io/modelcontextprotocol/client/transport/HttpClientStreamableHttpTransport.java
 * and refactor use jdk8.
 *
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.ResponseEvent;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An implementation of the Streamable HTTP protocol as defined by the
 * <code>2025-03-26</code> version of the MCP specification.
 *
 * <p>
 * The transport is capable of resumability and reconnects. It reacts to transport-level
 * session invalidation and will propagate {@link McpTransportSessionNotFoundException
 * appropriate exceptions} to the higher level abstraction layer when needed in order to
 * allow proper state management. The implementation handles servers that are stateful and
 * provide session meta information, but can also communicate with stateless servers that
 * do not provide a session identifier and do not support SSE streams.
 * </p>
 * <p>
 * This implementation does not handle backwards compatibility with the <a href=
 * "https://modelcontextprotocol.io/specification/2024-11-05/basic/transports#http-with-sse">"HTTP
 * with SSE" transport</a>. In order to communicate over the phased-out
 * <code>2024-11-05</code> protocol, use {@link HttpClientSseClientTransport} or
 * {@code WebFluxSseClientTransport}.
 * </p>
 *
 * @author Christian Tzolov
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http">Streamable
 * HTTP transport specification</a>
 */
public class HttpClientStreamableHttpTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(HttpClientStreamableHttpTransport.class);

	private static final String DEFAULT_ENDPOINT = "/mcp";

	/**
	 * HTTP client for sending messages to the server. Uses HTTP POST over the message
	 * endpoint
	 */
	private final OkHttpClient httpClient;

	/** HTTP request builder for building requests to send messages to the server */
	private final Request.Builder requestBuilder;

	/**
	 * Event type for JSON-RPC messages received through the SSE connection. The server
	 * sends messages with this event type to transmit JSON-RPC protocol data.
	 */
	private static final String MESSAGE_EVENT_TYPE = "message";

	private static final String APPLICATION_JSON = "application/json";

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private static final String SESSION_ID_HEADER = "mcp-session-id";

	public static int NOT_FOUND = 404;

	public static int METHOD_NOT_ALLOWED = 405;

	public static int BAD_REQUEST = 400;

	private final ObjectMapper objectMapper;

	private final URI baseUri;

	private final String endpoint;

	private final boolean openConnectionOnStartup;

	private final boolean resumableStreams;

	private final AtomicReference<DefaultMcpTransportSession> activeSession = new AtomicReference<>();

	private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler = new AtomicReference<>();

	private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

	private HttpClientStreamableHttpTransport(ObjectMapper objectMapper, OkHttpClient httpClient,
											  Request.Builder requestBuilder, String baseUri, String endpoint, boolean resumableStreams,
                                              boolean openConnectionOnStartup) {
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
		this.baseUri = URI.create(baseUri);
		this.endpoint = endpoint;
		this.resumableStreams = resumableStreams;
		this.openConnectionOnStartup = openConnectionOnStartup;
		this.activeSession.set(createTransportSession());
	}

	public static Builder builder(String baseUri) {
		return new Builder(baseUri);
	}

	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return Mono.deferContextual(ctx -> {
			this.handler.set(handler);
			if (this.openConnectionOnStartup) {
				logger.debug("Eagerly opening connection on startup");
				return this.reconnect(null).onErrorComplete(t -> {
					logger.warn("Eager connect failed ", t);
					return true;
				}).then();
			}
			return Mono.empty();
		});
	}

	private DefaultMcpTransportSession createTransportSession() {
		Function<String, Publisher<Void>> onClose = sessionId -> {
			try {
				return sessionId == null ? Mono.empty() : createDelete(sessionId);
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		};
		return new DefaultMcpTransportSession(onClose);
	}

	private Publisher<Void> createDelete(String sessionId) throws MalformedURLException {
		Request request = requestBuilder.url(Utils.resolveUri(baseUri, endpoint).toURL())
				.header("Cache-Control", "no-cache")
				.header(SESSION_ID_HEADER, sessionId)
				.delete()
				.build();

		return sendRequest(httpClient, request).then();
	}

	private static Mono<String> sendRequest(OkHttpClient client, Request request) {
		return Mono.create(sink -> client.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(@NotNull Call call, @NotNull IOException e) {
				sink.error(e);
			}

			@Override
			public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
				if (response.isSuccessful()) {
					ResponseBody body = response.body();
					if (body != null) {
						sink.success(body.string());
					} else {
						sink.error(new IOException("Empty response body"));
					}
				} else {
					sink.error(new IOException("HTTP error code: " + response.code()));
				}
			}
		}));
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		logger.debug("Exception handler registered");
		this.exceptionHandler.set(handler);
	}

	private void handleException(Throwable t) {
		logger.debug("Handling exception for session {}", sessionIdOrPlaceholder(this.activeSession.get()), t);
		if (t instanceof McpTransportSessionNotFoundException) {
			McpTransportSession<?> invalidSession = this.activeSession.getAndSet(createTransportSession());
			logger.warn("Server does not recognize session {}. Invalidating.", invalidSession.sessionId());
			invalidSession.close();
		}
		Consumer<Throwable> handler = this.exceptionHandler.get();
		if (handler != null) {
			handler.accept(t);
		}
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			logger.debug("Graceful close triggered");
			DefaultMcpTransportSession currentSession = this.activeSession.getAndSet(createTransportSession());
			if (currentSession != null) {
				return currentSession.closeGracefully();
			}
			return Mono.empty();
		});
	}

	private Mono<Disposable> reconnect(McpTransportStream<Disposable> stream) {

		return Mono.deferContextual(ctx -> {

			if (stream != null) {
				logger.debug("Reconnecting stream {} with lastId {}", stream.streamId(), stream.lastId());
			}
			else {
				logger.debug("Reconnecting with no prior stream");
			}

			final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
			final McpTransportSession<Disposable> transportSession = this.activeSession.get();

			if (transportSession != null && transportSession.sessionId().isPresent()) {
				requestBuilder.header(SESSION_ID_HEADER, transportSession.sessionId().get());
			}

			if (stream != null && stream.lastId().isPresent()) {
				requestBuilder.header("last-event-id", stream.lastId().get());
			}

			Request request = null;
			try {
				request = requestBuilder.url(Utils.resolveUri(baseUri, endpoint).toURL())
						.header("Accept", TEXT_EVENT_STREAM)
						.header("Cache-Control", "no-cache")
						.get()
						.build();
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}

			Request finalRequest = request;
			Disposable connection = Flux.<ResponseEvent>create(
							sseSink ->
							{
								Call call = httpClient.newCall(finalRequest);
								call.enqueue(new Callback() {
									@Override
									public void onFailure(Call call, IOException e) {
										logger.error("SSE reconnect error:", e);
										sseSink.error(e);
									}

									@Override
									public void onResponse(Call call, Response response) {
										ResponseSubscribers.sseToBodySubscriber(response, sseSink).subscribe();
										logger.debug("SSE connection established successfully");
									}
								});
							})
				.map(responseEvent -> (ResponseSubscribers.SseResponseEvent) responseEvent)
				.flatMap(responseEvent -> {
					int statusCode = responseEvent.responseInfo().code();

					if (statusCode >= 200 && statusCode < 300) {

						if (MESSAGE_EVENT_TYPE.equals(responseEvent.getSseEvent().getEvent())) {
							try {
								// We don't support batching ATM and probably won't since
								// the
								// next version considers removing it.
								McpSchema.JSONRPCMessage message = McpSchema
									.deserializeJsonRpcMessage(this.objectMapper, responseEvent.getSseEvent().getData());

								Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>> idWithMessages = Tuples
									.of(Optional.ofNullable(responseEvent.getSseEvent().getId()), Collections.singletonList(message));

								McpTransportStream<Disposable> sessionStream = stream != null ? stream
										: new DefaultMcpTransportStream<>(this.resumableStreams, this::reconnect);
								logger.debug("Connected stream {}", sessionStream.streamId());

								return Flux.from(sessionStream.consumeSseStream(Flux.just(idWithMessages)));

							}
							catch (IOException ioException) {
								return Flux.<McpSchema.JSONRPCMessage>error(new McpError(
										"Error parsing JSON-RPC message: " + responseEvent.getSseEvent().getData()));
							}
						}
					}
					else if (statusCode == METHOD_NOT_ALLOWED) { // NotAllowed
						logger.debug("The server does not support SSE streams, using request-response mode.");
						return Flux.empty();
					}
					else if (statusCode == NOT_FOUND) {
						String sessionIdRepresentation = sessionIdOrPlaceholder(transportSession);
						McpTransportSessionNotFoundException exception = new McpTransportSessionNotFoundException(
								"Session not found for session ID: " + sessionIdRepresentation);
						return Flux.<McpSchema.JSONRPCMessage>error(exception);
					}
					else if (statusCode == BAD_REQUEST) {
						String sessionIdRepresentation = sessionIdOrPlaceholder(transportSession);
						McpTransportSessionNotFoundException exception = new McpTransportSessionNotFoundException(
								"Session not found for session ID: " + sessionIdRepresentation);
						return Flux.<McpSchema.JSONRPCMessage>error(exception);
					}

					return Flux.<McpSchema.JSONRPCMessage>error(
							new McpError("Received unrecognized SSE event type: " + responseEvent.getSseEvent().getEvent()));

				}).<McpSchema
						.JSONRPCMessage>flatMap(jsonrpcMessage -> this.handler.get().apply(Mono.just(jsonrpcMessage)))
				.onErrorMap(CompletionException.class, t -> t.getCause())
				.onErrorComplete(t -> {
					this.handleException(t);
					return true;
				})
				.doFinally(s -> {
					Disposable ref = disposableRef.getAndSet(null);
					if (ref != null) {
						transportSession.removeConnection(ref);
					}
				})
				.contextWrite(ctx)
				.subscribe();

			disposableRef.set(connection);
			transportSession.addConnection(connection);
			return Mono.just(connection);
		});

	}

	private Mono<Void> toSendMessageBodySubscriber(Response responseInfo, FluxSink<ResponseEvent> sink) {
		String contentType = responseInfo.headers().get("Content-Type");
		contentType = Utils.isBlank(contentType) ? "" : contentType.toLowerCase();

		if (contentType.contains(TEXT_EVENT_STREAM)) {
			// For SSE streams, use line subscriber that returns Void
			logger.debug("Received SSE stream response, using line subscriber");
			return ResponseSubscribers.sseToBodySubscriber(responseInfo, sink);
		} else if (contentType.contains(APPLICATION_JSON)) {
			// For JSON responses and others, use string subscriber
			logger.debug("Received response, using string subscriber");
			return ResponseSubscribers.aggregateBodySubscriber(responseInfo, sink);
		}

		logger.debug("Received Bodyless response, using discarding subscriber");
		return ResponseSubscribers.bodilessBodySubscriber(responseInfo, sink);
	}

	public String toString(McpSchema.JSONRPCMessage message) {
		try {
			return this.objectMapper.writeValueAsString(message);
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to serialize JSON-RPC message", e);
		}
	}

	@Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage sendMessage) {
		return Mono.create(messageSink -> {
			logger.debug("Sending message {}", sendMessage);

			final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
			final McpTransportSession<Disposable> transportSession = this.activeSession.get();

			if (transportSession != null && transportSession.sessionId().isPresent()) {
				requestBuilder.header(SESSION_ID_HEADER, transportSession.sessionId().get());
			}

			String jsonBody = this.toString(sendMessage);

			Request request = null;
			try {
				request = requestBuilder.url(Utils.resolveUri(baseUri, endpoint).toURL())
						.header("Accept", TEXT_EVENT_STREAM + ", " + APPLICATION_JSON)
						.header("Content-Type", APPLICATION_JSON)
						.header("Cache-Control", "no-cache")
						.post(RequestBody.create(jsonBody, MediaType.get(APPLICATION_JSON)))
						.build();
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}

			Request finalRequest = request;
			Disposable connection = Flux.<ResponseEvent>create(responseEventSink -> {
						// Create the async request with proper body subscriber selection
						CompletableFuture<Void> future = new CompletableFuture<>();

						Call call = httpClient.newCall(finalRequest);
						call.enqueue(new Callback() {
							@Override
							public void onFailure(@NotNull Call call, @NotNull IOException e) {
								logger.error("request error:", e);

								responseEventSink.error(e);
								future.completeExceptionally(e);
							}

							@Override
							public void onResponse(@NotNull Call call, @NotNull Response response) {
								try {
									Mono<Void> bodySubscriberProvider = toSendMessageBodySubscriber(response, responseEventSink);
									bodySubscriberProvider.subscribe();
									future.complete(null);
									logger.debug("SSE connection established successfully");
								} catch (Exception e) {
									logger.error("onResponse error:", e);

									responseEventSink.error(e);
									future.completeExceptionally(e);
								}
							}
						});
						Mono.fromFuture(future).onErrorMap(CompletionException.class, t -> t.getCause()).onErrorComplete().subscribe();
					}).flatMap(responseEvent -> {
				String mcpSessionId = null;
				if (responseEvent.responseInfo() == null) {
					// when send notifications/initialized message to server, maybe received empty response, so ignore it
					logger.warn("sendMessage: responseEvent.responseInfo() is null for session {}",
							sessionIdOrPlaceholder(this.activeSession.get()));
				} else {
					mcpSessionId = responseEvent.responseInfo().headers().get(SESSION_ID_HEADER);
				}

				if (transportSession.markInitialized(mcpSessionId)) {
					// Once we have a session, we try to open an async stream for
					// the server to send notifications and requests out-of-band.

					reconnect(null).contextWrite(messageSink.contextView()).subscribe();
				}

				String sessionRepresentation = sessionIdOrPlaceholder(transportSession);

				int statusCode = responseEvent.responseInfo() == null ? 200 : responseEvent.responseInfo().code();

				if (statusCode >= 200 && statusCode < 300) {

					String contentType = responseEvent.responseInfo() == null ?
							"" : responseEvent.responseInfo().headers().get("Content-Type");

					if (Utils.isBlank(contentType)) {
						logger.debug("No content type returned for POST in session {}", sessionRepresentation);
						// No content type means no response body, so we can just return
						// an empty stream
						messageSink.success();
						return Flux.empty();
					}
					else if (contentType.toLowerCase().contains(TEXT_EVENT_STREAM)) {
						return Flux.just(((ResponseSubscribers.SseResponseEvent) responseEvent).getSseEvent())
							.flatMap(sseEvent -> {
								try {
									// We don't support batching ATM and probably won't
									// since the
									// next version considers removing it.
									McpSchema.JSONRPCMessage message = McpSchema
										.deserializeJsonRpcMessage(this.objectMapper, sseEvent.getData());

									Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>> idWithMessages = Tuples
										.of(Optional.ofNullable(sseEvent.getId()), Collections.singletonList(message));

									McpTransportStream<Disposable> sessionStream = new DefaultMcpTransportStream<>(
											this.resumableStreams, this::reconnect);

									logger.debug("Connected stream {}", sessionStream.streamId());

									messageSink.success();

									return Flux.from(sessionStream.consumeSseStream(Flux.just(idWithMessages)));
								}
								catch (IOException ioException) {
									return Flux.<McpSchema.JSONRPCMessage>error(
											new McpError("Error parsing JSON-RPC message: " + sseEvent.getData()));
								}
							});
					}
					else if (contentType.contains(APPLICATION_JSON)) {
						messageSink.success();
						String data = ((ResponseSubscribers.AggregateResponseEvent) responseEvent).getData();
						try {
							return Mono.just(McpSchema.deserializeJsonRpcMessage(objectMapper, data));
						}
						catch (IOException e) {
							return Mono.error(e);
						}
					}
					logger.warn("Unknown media type {} returned for POST in session {}", contentType,
							sessionRepresentation);

					return Flux.<McpSchema.JSONRPCMessage>error(
							new RuntimeException("Unknown media type returned: " + contentType));
				}
				else if (statusCode == NOT_FOUND) {
					McpTransportSessionNotFoundException exception = new McpTransportSessionNotFoundException(
							"Session not found for session ID: " + sessionRepresentation);
					return Flux.<McpSchema.JSONRPCMessage>error(exception);
				}
				// Some implementations can return 400 when presented with a
				// session id that it doesn't know about, so we will
				// invalidate the session
				// https://github.com/modelcontextprotocol/typescript-sdk/issues/389
				else if (statusCode == BAD_REQUEST) {
					McpTransportSessionNotFoundException exception = new McpTransportSessionNotFoundException(
							"Session not found for session ID: " + sessionRepresentation);
					return Flux.<McpSchema.JSONRPCMessage>error(exception);
				}

				return Flux.<McpSchema.JSONRPCMessage>error(
						new RuntimeException("Failed to send message: " + responseEvent));
			})
				.flatMap(jsonRpcMessage -> this.handler.get().apply(Mono.just(jsonRpcMessage)))
				.onErrorMap(CompletionException.class, t -> t.getCause())
				.onErrorComplete(t -> {
					// handle the error first
					this.handleException(t);
					// inform the caller of sendMessage
					messageSink.error(t);
					return true;
				})
				.doFinally(s -> {
					logger.debug("SendMessage finally: {}", s);
					Disposable ref = disposableRef.getAndSet(null);
					if (ref != null) {
						transportSession.removeConnection(ref);
					}
				})
				.contextWrite(messageSink.contextView())
				.subscribe();

			disposableRef.set(connection);
			transportSession.addConnection(connection);
		});
	}

	private static String sessionIdOrPlaceholder(McpTransportSession<?> transportSession) {
		return transportSession.sessionId().orElse("[missing_session_id]");
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	/**
	 * Builder for {@link HttpClientStreamableHttpTransport}.
	 */
	public static class Builder {

		private final String baseUri;

		private ObjectMapper objectMapper;

		private OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
				.connectTimeout(Duration.ofSeconds(10));

		private String endpoint = DEFAULT_ENDPOINT;

		private boolean resumableStreams = true;

		private boolean openConnectionOnStartup = false;

		private Request.Builder requestBuilder = new Request.Builder();

		/**
		 * Creates a new builder with the specified base URI.
		 * @param baseUri the base URI of the MCP server
		 */
		private Builder(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
		}

		/**
		 * Sets the HTTP client builder.
		 * @param clientBuilder the HTTP client builder
		 * @return this builder
		 */
		public Builder clientBuilder(OkHttpClient.Builder clientBuilder) {
			Assert.notNull(clientBuilder, "clientBuilder must not be null");
			this.clientBuilder = clientBuilder;
			return this;
		}

		/**
		 * Customizes the HTTP client builder.
		 * @param clientCustomizer the consumer to customize the HTTP client builder
		 * @return this builder
		 */
		public Builder customizeClient(final Consumer<OkHttpClient.Builder> clientCustomizer) {
			Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
			clientCustomizer.accept(clientBuilder);
			return this;
		}

		/**
		 * Sets the HTTP request builder.
		 * @param requestBuilder the HTTP request builder
		 * @return this builder
		 */
		public Builder requestBuilder(Request.Builder requestBuilder) {
			Assert.notNull(requestBuilder, "requestBuilder must not be null");
			this.requestBuilder = requestBuilder;
			return this;
		}

		/**
		 * Customizes the HTTP client builder.
		 * @param requestCustomizer the consumer to customize the HTTP request builder
		 * @return this builder
		 */
		public Builder customizeRequest(final Consumer<Request.Builder> requestCustomizer) {
			Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
			requestCustomizer.accept(requestBuilder);
			return this;
		}

		/**
		 * Configure the {@link ObjectMapper} to use.
		 * @param objectMapper instance to use
		 * @return the builder instance
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Configure the endpoint to make HTTP requests against.
		 * @param endpoint endpoint to use
		 * @return the builder instance
		 */
		public Builder endpoint(String endpoint) {
			Assert.hasText(endpoint, "endpoint must be a non-empty String");
			this.endpoint = endpoint;
			return this;
		}

		/**
		 * Configure whether to use the stream resumability feature by keeping track of
		 * SSE event ids.
		 * @param resumableStreams if {@code true} event ids will be tracked and upon
		 * disconnection, the last seen id will be used upon reconnection as a header to
		 * resume consuming messages.
		 * @return the builder instance
		 */
		public Builder resumableStreams(boolean resumableStreams) {
			this.resumableStreams = resumableStreams;
			return this;
		}

		/**
		 * Configure whether the client should open an SSE connection upon startup. Not
		 * all servers support this (although it is in theory possible with the current
		 * specification), so use with caution. By default, this value is {@code false}.
		 * @param openConnectionOnStartup if {@code true} the {@link #connect(Function)}
		 * method call will try to open an SSE connection before sending any JSON-RPC
		 * request
		 * @return the builder instance
		 */
		public Builder openConnectionOnStartup(boolean openConnectionOnStartup) {
			this.openConnectionOnStartup = openConnectionOnStartup;
			return this;
		}

		/**
		 * Construct a fresh instance of {@link HttpClientStreamableHttpTransport} using
		 * the current builder configuration.
		 * @return a new instance of {@link HttpClientStreamableHttpTransport}
		 */
		public HttpClientStreamableHttpTransport build() {
			ObjectMapper objectMapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();

			return new HttpClientStreamableHttpTransport(objectMapper, clientBuilder.build(), requestBuilder, baseUri,
					endpoint, resumableStreams, openConnectionOnStartup);
		}
	}
}
