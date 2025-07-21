/*
 * 2025-07-16 copy from
 * https://github.com/modelcontextprotocol/java-sdk  main branch
 * mcp/src/main/java/io/modelcontextprotocol/client/transport/ResponseSubscribers.java
 * and refactor use jdk8.
 *
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.spec.SseEvent;
import lombok.Value;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class providing various BodySubscriber implementations for handling
 * different types of HTTP response bodies in the context of Model Context Protocol (MCP)
 * clients.
 *
 * <p>
 * Defines subscribers for processing Server-Sent Events (SSE), aggregate responses, and
 * bodiless responses.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
class ResponseSubscribers {

	interface ResponseEvent {

		Response responseInfo();

	}

	@Value
	static class DummyEvent implements ResponseEvent {
		Response responseInfo;

		@Override
		public Response responseInfo() {
			return null;
		}
	}

	@Value
	static class SseResponseEvent implements ResponseEvent {
		Response responseInfo;
		SseEvent sseEvent;

		@Override
		public Response responseInfo() {
			return responseInfo;
		}
	}

	@Value
	static class AggregateResponseEvent implements ResponseEvent {
		Response responseInfo;
		String data;

		@Override
		public Response responseInfo() {
			return responseInfo;
		}
	}

	public static Mono<Void> sseToBodySubscriber(Response response, FluxSink<ResponseEvent> sink) {
		return Mono.create(emitter -> {
			try {
				ResponseBody body = response.body();
				if (body == null) {
					emitter.error(new IOException("Empty response body"));
					return;
				}

				SseLineSubscriber sseLineSubscriber = new SseLineSubscriber(response, sink);

				// 使用自定义方法逐行读取并通知 subscriber
				readLinesFromResponseBody(body, sseLineSubscriber);

				emitter.success();
			} catch (IOException e) {
				emitter.error(e);
			}
		});
	}

	public static Mono<Void> aggregateBodySubscriber(Response response, FluxSink<ResponseEvent> sink) {
		return Mono.create(emitter -> {
			try {
				ResponseBody body = response.body();
				if (body == null) {
					emitter.error(new IOException("Empty response body"));
					return;
				}

				AggregateSubscriber subscriber = new AggregateSubscriber(response, sink);
				readLinesFromResponseBody(body, subscriber);

				emitter.success();
			} catch (IOException e) {
				emitter.error(e);
			}
		});
	}

	public static Mono<Void> bodilessBodySubscriber(Response response, FluxSink<ResponseEvent> sink) {
		return Mono.create(emitter -> {
			try {
				ResponseBody body = response.body();
				if (body == null) {
					emitter.error(new IOException("Empty response body"));
					return;
				}

				BodilessResponseLineSubscriber subscriber = new BodilessResponseLineSubscriber(response, sink);
				readLinesFromResponseBody(body, subscriber);

				emitter.success();
			} catch (IOException e) {
				emitter.error(e);
			}
		});
	}

	public static void readLinesFromResponseBody(ResponseBody body, BaseSubscriber<String> subscriber) throws IOException {
		if (body == null) throw new IOException("Empty response body");

		try (BufferedReader reader = new BufferedReader(body.charStream())) {
			final String[] line = new String[1];
			subscriber.onSubscribe(new Subscription() {
				private boolean cancelled = false;

				@Override
				public void request(long n) {
					if (n <= 0 || cancelled) return;
					try {
						while ((line[0] = reader.readLine()) != null) {
							subscriber.onNext(line[0]);
						}
						subscriber.onComplete();
					} catch (IOException e) {
						subscriber.onError(e);
					} finally {
						cancelled = true;
					}
				}

				@Override
				public void cancel() {
					cancelled = true;
				}
			});
		}
	}

	static class SseLineSubscriber extends BaseSubscriber<String> {

		/**
		 * Pattern to extract data content from SSE "data:" lines.
		 */
		private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

		/**
		 * Pattern to extract event ID from SSE "id:" lines.
		 */
		private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

		/**
		 * Pattern to extract event type from SSE "event:" lines.
		 */
		private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

		/**
		 * The sink for emitting parsed response events.
		 */
		private final FluxSink<ResponseEvent> sink;

		/**
		 * StringBuilder for accumulating multi-line event data.
		 */
		private final StringBuilder eventBuilder;

		/**
		 * Current event's ID, if specified.
		 */
		private final AtomicReference<String> currentEventId;

		/**
		 * Current event's type, if specified.
		 */
		private final AtomicReference<String> currentEventType;

		/**
		 * The response information from the HTTP response. Send with each event to
		 * provide context.
		 */
		private Response responseInfo;

		/**
		 * Creates a new LineSubscriber that will emit parsed SSE events to the provided
		 * sink.
		 * @param sink the {@link FluxSink} to emit parsed {@link ResponseEvent} objects
		 * to
		 */
		public SseLineSubscriber(Response responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.currentEventId = new AtomicReference<>();
			this.currentEventType = new AtomicReference<>();
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {

			sink.onRequest(n -> {
				subscription.request(n);
			});

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(() -> {
				subscription.cancel();
			});
		}

		@Override
		protected void hookOnNext(String line) {
			if (line.isEmpty()) {
				// Empty line means end of event
				if (this.eventBuilder.length() > 0) {
					String eventData = this.eventBuilder.toString();
					SseEvent sseEvent = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());

					this.sink.next(new SseResponseEvent(responseInfo, sseEvent));
					this.eventBuilder.setLength(0);
				}
			}
			else {
				if (line.startsWith("data:")) {
					Matcher matcher = EVENT_DATA_PATTERN.matcher(line);
					if (matcher.find()) {
						this.eventBuilder.append(matcher.group(1).trim()).append("\n");
					}
				}
				else if (line.startsWith("id:")) {
					Matcher matcher = EVENT_ID_PATTERN.matcher(line);
					if (matcher.find()) {
						this.currentEventId.set(matcher.group(1).trim());
					}
				}
				else if (line.startsWith("event:")) {
					Matcher matcher = EVENT_TYPE_PATTERN.matcher(line);
					if (matcher.find()) {
						this.currentEventType.set(matcher.group(1).trim());
					}
				}
			}
		}

		@Override
		protected void hookOnComplete() {
			if (this.eventBuilder.length() > 0) {
				String eventData = this.eventBuilder.toString();
				SseEvent sseEvent = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
				this.sink.next(new SseResponseEvent(responseInfo, sseEvent));
			}
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

	static class AggregateSubscriber extends BaseSubscriber<String> {

		/**
		 * The sink for emitting parsed response events.
		 */
		private final FluxSink<ResponseEvent> sink;

		/**
		 * StringBuilder for accumulating multi-line event data.
		 */
		private final StringBuilder eventBuilder;

		/**
		 * The response information from the HTTP response. Send with each event to
		 * provide context.
		 */
		private Response responseInfo;

		/**
		 * Creates a new JsonLineSubscriber that will emit parsed JSON-RPC messages.
		 * @param sink the {@link FluxSink} to emit parsed {@link ResponseEvent} objects
		 * to
		 */
		public AggregateSubscriber(Response responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {
			sink.onRequest(subscription::request);

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(subscription::cancel);
		}

		@Override
		protected void hookOnNext(String line) {
			this.eventBuilder.append(line).append("\n");
		}

		@Override
		protected void hookOnComplete() {
			if (this.eventBuilder.length() > 0) {
				String data = this.eventBuilder.toString();
				this.sink.next(new AggregateResponseEvent(responseInfo, data));
			}
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

	static class BodilessResponseLineSubscriber extends BaseSubscriber<String> {

		/**
		 * The sink for emitting parsed response events.
		 */
		private final FluxSink<ResponseEvent> sink;

		private final Response responseInfo;

		public BodilessResponseLineSubscriber(Response responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {

			sink.onRequest(n -> {
				subscription.request(n);
			});

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(() -> {
				subscription.cancel();
			});
		}

		@Override
		protected void hookOnComplete() {
			// emit dummy event to be able to inspect the response info
			// this is a shortcut allowing for a more streamlined processing using
			// operator composition instead of having to deal with the CompletableFuture
			// along the Subscriber for inspecting the result
			this.sink.next(new DummyEvent(responseInfo));
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}
	}
}
