/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.client.transport;

import lombok.Value;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Server-Sent Events (SSE) client implementation using Java's Flow API for reactive
 * stream processing. This client establishes a connection to an SSE endpoint and
 * processes the incoming event stream, parsing SSE-formatted messages into structured
 * events.
 *
 * <p>
 * The client supports standard SSE event fields including:
 * <ul>
 * <li>event - The event type (defaults to "message" if not specified)</li>
 * <li>id - The event ID</li>
 * <li>data - The event payload data</li>
 * </ul>
 *
 * <p>
 * Events are delivered to a provided {@link SseEventHandler} which can process events and
 * handle any errors that occur during the connection.
 *
 * @author Christian Tzolov
 * @see SseEventHandler
 * @see SseEvent
 */
public class FlowSseClient {

	private final OkHttpClient httpClient;

	private final Request.Builder requestBuilder;

	/**
	 * Pattern to extract the data content from SSE data field lines. Matches lines
	 * starting with "data:" and captures the remaining content.
	 */
	private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

	/**
	 * Pattern to extract the event ID from SSE id field lines. Matches lines starting
	 * with "id:" and captures the ID value.
	 */
	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

	/**
	 * Pattern to extract the event type from SSE event field lines. Matches lines
	 * starting with "event:" and captures the event type.
	 */
	private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

	/**
	 * Record class representing a Server-Sent Event with its standard fields.
	 */
	@Value
	public static class SseEvent {
		/** the event ID (may be null) */
		String id;

		/** the event type (defaults to "message" if not specified in the stream) */
		String type;

		/** the event payload data */
		String data;
	}

	/**
	 * Interface for handling SSE events and errors. Implementations can process received
	 * events and handle any errors that occur during the SSE connection.
	 */
	public interface SseEventHandler {

		/**
		 * Called when an SSE event is received.
		 * @param event the received SSE event containing id, type, and data
		 */
		void onEvent(SseEvent event);

		/**
		 * Called when an error occurs during the SSE connection.
		 * @param error the error that occurred
		 */
		void onError(Throwable error);

		/**
		 * Called when the SSE connection is completed.
		 */
		default void onComplete() {
			// Default empty implementation
		}
	}

	/**
	 * Creates a new FlowSseClient with the specified HTTP client.
	 * @param httpClient the {@link OkHttpClient} instance to use for SSE connections
	 */
	public FlowSseClient(OkHttpClient httpClient) {
		this(httpClient, new Request.Builder());
	}

	/**
	 * Creates a new FlowSseClient with the specified HTTP client and request builder.
	 * @param httpClient the {@link OkHttpClient} instance to use for SSE connections
	 * @param requestBuilder the {@link new Request.Builder()} to use for SSE requests
	 */
	public FlowSseClient(OkHttpClient httpClient, Request.Builder requestBuilder) {
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
	}

	/**
	 * Subscribes to an SSE endpoint and processes the event stream.
	 *
	 * <p>
	 * This method establishes a connection to the specified URL and begins processing the
	 * SSE stream. Events are parsed and delivered to the provided event handler. The
	 * connection remains active until either an error occurs or the server closes the
	 * connection.
	 * @param url the SSE endpoint URL to connect to
	 * @param eventHandler the handler that will receive SSE events and error
	 * notifications
	 * @throws RuntimeException if the connection fails with a non-200 status code
	 */
	public void subscribe(String url, SseEventHandler eventHandler) {
		Request request = requestBuilder.url(url)
				.header("Accept", "text/event-stream")
				.header("Cache-Control", "no-cache")
				.get()
				.build();

		Call call = httpClient.newCall(request);
		call.enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				eventHandler.onError(e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				if (!response.isSuccessful()) {
					eventHandler.onError(new RuntimeException("Failed to connect to SSE stream. Status code: " + response.code()));
					return;
				}

				try (ResponseBody body = response.body()) {
					if (body == null) {
						eventHandler.onError(new RuntimeException("Response body is null"));
						return;
					}

					processEventStream(body.source(), eventHandler);
				} catch (Exception e) {
					eventHandler.onError(e);
				}
			}
		});
	}

	/**
	 * Processes the SSE event stream from the buffered source.
	 * @param source the buffered source containing the SSE stream
	 * @param eventHandler the event handler to deliver events to
	 * @throws IOException if an I/O error occurs while reading the stream
	 */
	private void processEventStream(BufferedSource source, SseEventHandler eventHandler) throws IOException {
		StringBuilder eventBuilder = new StringBuilder();
		AtomicReference<String> currentEventId = new AtomicReference<>();
		AtomicReference<String> currentEventType = new AtomicReference<>("message");

		try {
			String line;
			while ((line = source.readUtf8Line()) != null) {
				if (line.isEmpty()) {
					// Empty line means end of event
					if (eventBuilder.length() > 0) {
						String eventData = eventBuilder.toString();
						SseEvent event = new SseEvent(
								currentEventId.get(),
								currentEventType.get(),
								eventData.trim()
						);
						eventHandler.onEvent(event);
						eventBuilder.setLength(0);
					}
				} else {
					processEventLine(line, eventBuilder, currentEventId, currentEventType);
				}
			}

			// Handle any remaining event data
			if (eventBuilder.length() > 0) {
				String eventData = eventBuilder.toString();
				SseEvent event = new SseEvent(
						currentEventId.get(),
						currentEventType.get(),
						eventData.trim()
				);
				eventHandler.onEvent(event);
			}

			eventHandler.onComplete();
		} catch (IOException e) {
			eventHandler.onError(e);
		}
	}

	/**
	 * Processes a single line from the SSE stream.
	 * @param line the line to process
	 * @param eventBuilder the string builder for accumulating event data
	 * @param currentEventId reference to the current event ID
	 * @param currentEventType reference to the current event type
	 */
	private void processEventLine(String line, StringBuilder eventBuilder,
								  AtomicReference<String> currentEventId,
								  AtomicReference<String> currentEventType) {
		if (line.startsWith("data:")) {
			Matcher matcher = EVENT_DATA_PATTERN.matcher(line);
			if (matcher.find()) {
				eventBuilder.append(matcher.group(1).trim()).append("\n");
			}
		} else if (line.startsWith("id:")) {
			Matcher matcher = EVENT_ID_PATTERN.matcher(line);
			if (matcher.find()) {
				currentEventId.set(matcher.group(1).trim());
			}
		} else if (line.startsWith("event:")) {
			Matcher matcher = EVENT_TYPE_PATTERN.matcher(line);
			if (matcher.find()) {
				currentEventType.set(matcher.group(1).trim());
			}
		}
		// Ignore other types of lines (like comments starting with :)
	}

	/**
	 * Synchronous version of subscribe method for cases where blocking behavior is desired.
	 * @param url the SSE endpoint URL to connect to
	 * @param eventHandler the handler that will receive SSE events and error notifications
	 * @throws IOException if an I/O error occurs during the connection
	 */
	public void subscribeSync(String url, SseEventHandler eventHandler) throws IOException {
		Request request = requestBuilder
				.url(url)
				.header("Accept", "text/event-stream")
				.header("Cache-Control", "no-cache")
				.get()
				.build();

		try (Response response = httpClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new RuntimeException("Failed to connect to SSE stream. Status code: " + response.code());
			}

			ResponseBody body = response.body();
			if (body == null) {
				throw new RuntimeException("Response body is null");
			}

			processEventStream(body.source(), eventHandler);
		}
	}
}
