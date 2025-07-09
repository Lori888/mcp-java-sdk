/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import lombok.Value;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * MCP server features specification that a particular server can choose to support.
 *
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 */
public class McpServerFeatures {

	/**
	 * Asynchronous server features specification.
	 */
	@Value
	static class Async {

		/** The server implementation details */
		McpSchema.Implementation serverInfo;

		/** The server capabilities */
		McpSchema.ServerCapabilities serverCapabilities;

		/** The list of tool specifications */
		List<McpServerFeatures.AsyncToolSpecification> tools;

		/** The map of resource specifications */
		Map<String, AsyncResourceSpecification> resources;

		/** The list of resource templates */
		List<McpSchema.ResourceTemplate> resourceTemplates;

		/** The map of prompt specifications */
		Map<String, McpServerFeatures.AsyncPromptSpecification> prompts;

		Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions;

		/** The list of consumers that will be notified when the roots list changes */
		List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers;

		/** The server instructions text */
		String instructions;

		List<McpServerFeatures.AsyncStreamingToolSpecification> streamTools;

		/**
		 * Create an instance and validate the arguments (backward compatible
		 * constructor).
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt specifications
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 * @param instructions The server instructions text
		 */
		public Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
					 List<McpServerFeatures.AsyncToolSpecification> tools, Map<String, AsyncResourceSpecification> resources,
					 List<McpSchema.ResourceTemplate> resourceTemplates,
					 Map<String, McpServerFeatures.AsyncPromptSpecification> prompts,
					 Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions,
					 List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers,
					 String instructions) {
			this(serverInfo, serverCapabilities, tools, resources, resourceTemplates, prompts, completions,
					rootsChangeConsumers, instructions, Collections.emptyList());
		}

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt specifications
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 * @param instructions The server instructions text
		 * @param streamTools The list of streaming tool specifications
		 */
		Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.AsyncToolSpecification> tools, Map<String, AsyncResourceSpecification> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.AsyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions,
				List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers,
				String instructions, List<McpServerFeatures.AsyncStreamingToolSpecification> streamTools) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : Collections.emptyList();
			this.resources = (resources != null) ? resources : Collections.emptyMap();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : Collections.emptyList();
			this.prompts = (prompts != null) ? prompts : Collections.emptyMap();
			this.completions = (completions != null) ? completions : Collections.emptyMap();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : Collections.emptyList();
			this.instructions = instructions;
			this.streamTools = (streamTools != null) ? streamTools : Collections.emptyList();
		}

		/**
		 * Convert a synchronous specification into an asynchronous one and provide
		 * blocking code offloading to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		static Async fromSync(Sync syncSpec) {
			List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
			for (SyncToolSpecification tool : syncSpec.getTools()) {
				tools.add(AsyncToolSpecification.fromSync(tool));
			}

			Map<String, AsyncResourceSpecification> resources = new HashMap<>();
			syncSpec.getResources().forEach((key, resource) -> {
				resources.put(key, AsyncResourceSpecification.fromSync(resource));
			});

			Map<String, AsyncPromptSpecification> prompts = new HashMap<>();
			syncSpec.getPrompts().forEach((key, prompt) -> {
				prompts.put(key, AsyncPromptSpecification.fromSync(prompt));
			});

			Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();
			syncSpec.getCompletions().forEach((key, completion) -> {
				completions.put(key, AsyncCompletionSpecification.fromSync(completion));
			});

			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootChangeConsumers = new ArrayList<>();

			for (BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> rootChangeConsumer : syncSpec.getRootsChangeConsumers()) {
				rootChangeConsumers.add((exchange, list) -> Mono
					.<Void>fromRunnable(() -> rootChangeConsumer.accept(new McpSyncServerExchange(exchange), list))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			return new Async(syncSpec.getServerInfo(), syncSpec.getServerCapabilities(), tools, resources,
					syncSpec.getResourceTemplates(), prompts, completions, rootChangeConsumers, syncSpec.getInstructions());
		}
	}

	/**
	 * Synchronous server features specification.
	 */
	@Value
	static class Sync {

		/** The server implementation details */
		McpSchema.Implementation serverInfo;

		/** The server capabilities */
		McpSchema.ServerCapabilities serverCapabilities;

		/** The list of tool specifications */
		List<McpServerFeatures.SyncToolSpecification> tools;

		/** The map of resource specifications */
		Map<String, McpServerFeatures.SyncResourceSpecification> resources;

		/** The list of resource templates */
		List<McpSchema.ResourceTemplate> resourceTemplates;

		/** The map of prompt specifications */
		Map<String, McpServerFeatures.SyncPromptSpecification> prompts;

		Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions;

		/** The list of consumers that will be notified when the roots list changes */
		List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers;

		/** The server instructions text */
		String instructions;

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt specifications
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 * @param instructions The server instructions text
		 */
		Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.SyncToolSpecification> tools,
				Map<String, McpServerFeatures.SyncResourceSpecification> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.SyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions,
				List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : new ArrayList<>();
			this.resources = (resources != null) ? resources : new HashMap<>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : new ArrayList<>();
			this.prompts = (prompts != null) ? prompts : new HashMap<>();
			this.completions = (completions != null) ? completions : new HashMap<>();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : new ArrayList<>();
			this.instructions = instructions;
		}

	}

	/**
	 * Specification of a tool with its asynchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models. Each tool
	 * represents a specific capability, such as:
	 * <ul>
	 * <li>Performing calculations
	 * <li>Accessing external APIs
	 * <li>Querying databases
	 * <li>Manipulating files
	 * <li>Executing system commands
	 * </ul>
	 *
	 * <p>
	 * Example tool specification: <pre>{@code
	 * new McpServerFeatures.AsyncToolSpecification(
	 *     new Tool(
	 *         "calculator",
	 *         "Performs mathematical calculations",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String expr = (String) args.get("expression");
	 *         return Mono.fromSupplier(() -> evaluate(expr))
	 *             .map(result -> new CallToolResult("Result: " + result));
	 *     }
	 * )
	 * }</pre>
	 */
	@Value
	public static class AsyncToolSpecification {

		/** The tool definition including name, description, and parameter schema */
		McpSchema.Tool tool;

		/** The function that implements the tool's logic, receiving arguments and
		 * returning results. The function's first argument is an
		 * {@link McpAsyncServerExchange} upon which the server can interact with the
		 * connected client. The second arguments is a map of tool arguments. */
		BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call;

		static AsyncToolSpecification fromSync(SyncToolSpecification tool) {
			// FIXME: This is temporary, proper validation should be implemented
			if (tool == null) {
				return null;
			}
			return new AsyncToolSpecification(tool.getTool(),
					(exchange, map) -> Mono
						.fromCallable(() -> tool.getCall().apply(new McpSyncServerExchange(exchange), map))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a streaming tool with its asynchronous handler function that can
	 * return either a single result (Mono) or a stream of results (Flux). This enables
	 * tools to provide real-time streaming responses for long-running operations or
	 * progressive results.
	 *
	 * <p>
	 * Example streaming tool specification: <pre>{@code
	 * new McpServerFeatures.AsyncStreamingToolSpecification(
	 *     new Tool(
	 *         "file_processor",
	 *         "Processes files with streaming progress updates",
	 *         new JsonSchemaObject()
	 *             .required("file_path")
	 *             .property("file_path", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String filePath = (String) args.get("file_path");
	 *         return Flux.interval(Duration.ofSeconds(1))
	 *             .take(10)
	 *             .map(i -> new CallToolResult("Processing step " + i + " for " + filePath));
	 *     }
	 * )
	 * }</pre>
     */
	@Value
	public static class AsyncStreamingToolSpecification {
		/**
		 * The tool definition including name, description, and parameter schema
		 */
		McpSchema.Tool tool;

		/**
		 * The function that implements the tool's streaming logic, receiving
		 * arguments and returning a Flux of results that will be streamed to the client via SSE.
		 */
		BiFunction<McpAsyncServerExchange, Map<String, Object>, Flux<McpSchema.CallToolResult>> call;
	}

	/**
	 * Specification of a resource with its asynchronous handler function. Resources
	 * provide context to AI models by exposing data such as:
	 * <ul>
	 * <li>File contents
	 * <li>Database records
	 * <li>API responses
	 * <li>System information
	 * <li>Application state
	 * </ul>
	 *
	 * <p>
	 * Example resource specification: <pre>{@code
	 * new McpServerFeatures.AsyncResourceSpecification(
	 *     new Resource("docs", "Documentation files", "text/markdown"),
	 *     (exchange, request) ->
	 *         Mono.fromSupplier(() -> readFile(request.getPath()))
	 *             .map(ReadResourceResult::new)
	 * )
	 * }</pre>
	 */
	@Value
	public static class AsyncResourceSpecification {

		/** The resource definition including name, description, and MIME type */
		McpSchema.Resource resource;

		/** The function that handles resource read requests. The function's
		 * first argument is an {@link McpAsyncServerExchange} upon which the server can
		 * interact with the connected client. The second arguments is a
		 * {@link io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest}. */
		BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		static AsyncResourceSpecification fromSync(SyncResourceSpecification resource) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceSpecification(resource.getResource(),
					(exchange, req) -> Mono
						.fromCallable(() -> resource.getReadHandler().apply(new McpSyncServerExchange(exchange), req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a prompt template with its asynchronous handler function. Prompts
	 * provide structured templates for AI model interactions, supporting:
	 * <ul>
	 * <li>Consistent message formatting
	 * <li>Parameter substitution
	 * <li>Context injection
	 * <li>Response formatting
	 * <li>Instruction templating
	 * </ul>
	 *
	 * <p>
	 * Example prompt specification: <pre>{@code
	 * new McpServerFeatures.AsyncPromptSpecification(
	 *     new Prompt("analyze", "Code analysis template"),
	 *     (exchange, request) -> {
	 *         String code = request.getArguments().get("code");
	 *         return Mono.just(new GetPromptResult(
	 *             "Analyze this code:\n\n" + code + "\n\nProvide feedback on:"
	 *         ));
	 *     }
	 * )
	 * }</pre>
	 */
	@Value
	public static class AsyncPromptSpecification {

		/** The prompt definition including name and description */
		McpSchema.Prompt prompt;

		/** The function that processes prompt requests and returns
		 * formatted templates. The function's first argument is an
		 * {@link McpAsyncServerExchange} upon which the server can interact with the
		 * connected client. The second arguments is a
		 * {@link io.modelcontextprotocol.spec.McpSchema.GetPromptRequest}. */
		BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler;

		static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt) {
			// FIXME: This is temporary, proper validation should be implemented
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptSpecification(prompt.getPrompt(),
					(exchange, req) -> Mono
						.fromCallable(() -> prompt.getPromptHandler().apply(new McpSyncServerExchange(exchange), req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a completion handler function with asynchronous execution support.
	 * Completions generate AI model outputs based on prompt or resource references and
	 * user-provided arguments. This abstraction enables:
	 * <ul>
	 * <li>Customizable response generation logic
	 * <li>Parameter-driven template expansion
	 * <li>Dynamic interaction with connected clients
	 * </ul>
	 */
	@Value
	public static class AsyncCompletionSpecification {

		/** The unique key representing the completion reference. */
		McpSchema.CompleteReference referenceKey;

		/** The asynchronous function that processes completion
		 * requests and returns results. The first argument is an
		 * {@link McpAsyncServerExchange} used to interact with the client. The second
		 * argument is a {@link io.modelcontextprotocol.spec.McpSchema.CompleteRequest}. */
		BiFunction<McpAsyncServerExchange, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler;

		/**
		 * Converts a synchronous {@link SyncCompletionSpecification} into an
		 * {@link AsyncCompletionSpecification} by wrapping the handler in a bounded
		 * elastic scheduler for safe non-blocking execution.
		 * @param completion the synchronous completion specification
		 * @return an asynchronous wrapper of the provided sync specification, or
		 * {@code null} if input is null
		 */
		static AsyncCompletionSpecification fromSync(SyncCompletionSpecification completion) {
			if (completion == null) {
				return null;
			}
			return new AsyncCompletionSpecification(completion.getReferenceKey(),
					(exchange, request) -> Mono.fromCallable(
							() -> completion.getCompletionHandler().apply(new McpSyncServerExchange(exchange), request))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a tool with its synchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models. Each tool
	 * represents a specific capability, such as:
	 * <ul>
	 * <li>Performing calculations
	 * <li>Accessing external APIs
	 * <li>Querying databases
	 * <li>Manipulating files
	 * <li>Executing system commands
	 * </ul>
	 *
	 * <p>
	 * Example tool specification: <pre>{@code
	 * new McpServerFeatures.SyncToolSpecification(
	 *     new Tool(
	 *         "calculator",
	 *         "Performs mathematical calculations",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String expr = (String) args.get("expression");
	 *         return new CallToolResult("Result: " + evaluate(expr));
	 *     }
	 * )
	 * }</pre>
	 */
	@Value
	public static class SyncToolSpecification {

		/** The tool definition including name, description, and parameter schema */
		McpSchema.Tool tool;

		/** The function that implements the tool's logic, receiving arguments and
		 * returning results. The function's first argument is an
		 * {@link McpSyncServerExchange} upon which the server can interact with the connected
		 * client. The second arguments is a map of arguments passed to the tool. */
		BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call;
	}

	/**
	 * Specification of a resource with its synchronous handler function. Resources
	 * provide context to AI models by exposing data such as:
	 * <ul>
	 * <li>File contents
	 * <li>Database records
	 * <li>API responses
	 * <li>System information
	 * <li>Application state
	 * </ul>
	 *
	 * <p>
	 * Example resource specification: <pre>{@code
	 * new McpServerFeatures.SyncResourceSpecification(
	 *     new Resource("docs", "Documentation files", "text/markdown"),
	 *     (exchange, request) -> {
	 *         String content = readFile(request.getPath());
	 *         return new ReadResourceResult(content);
	 *     }
	 * )
	 * }</pre>
	 */
	@Value
	public static class SyncResourceSpecification {

		/** The resource definition including name, description, and MIME type */
		McpSchema.Resource resource;

		/** The function that handles resource read requests. The function's
		 * first argument is an {@link McpSyncServerExchange} upon which the server can
		 * interact with the connected client. The second arguments is a
		 * {@link io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest}. */
		BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;
	}

	/**
	 * Specification of a prompt template with its synchronous handler function. Prompts
	 * provide structured templates for AI model interactions, supporting:
	 * <ul>
	 * <li>Consistent message formatting
	 * <li>Parameter substitution
	 * <li>Context injection
	 * <li>Response formatting
	 * <li>Instruction templating
	 * </ul>
	 *
	 * <p>
	 * Example prompt specification: <pre>{@code
	 * new McpServerFeatures.SyncPromptSpecification(
	 *     new Prompt("analyze", "Code analysis template"),
	 *     (exchange, request) -> {
	 *         String code = request.getArguments().get("code");
	 *         return new GetPromptResult(
	 *             "Analyze this code:\n\n" + code + "\n\nProvide feedback on:"
	 *         );
	 *     }
	 * )
	 * }</pre>
	 */
	@Value
	public static class SyncPromptSpecification {

		/** The prompt definition including name and description */
		McpSchema.Prompt prompt;

		/** The function that processes prompt requests and returns
		 * formatted templates. The function's first argument is an
		 * {@link McpSyncServerExchange} upon which the server can interact with the connected
		 * client. The second arguments is a
		 * {@link io.modelcontextprotocol.spec.McpSchema.GetPromptRequest}. */
		BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler;
	}

	/**
	 * Specification of a completion handler function with synchronous execution support.
	 */
	@Value
	public static class SyncCompletionSpecification {

		/** The unique key representing the completion reference. */
		McpSchema.CompleteReference referenceKey;

		/** The synchronous function that processes completion
		 * requests and returns results. The first argument is an
		 * {@link McpSyncServerExchange} used to interact with the client. The second argument
		 * is a {@link io.modelcontextprotocol.spec.McpSchema.CompleteRequest}. */
		BiFunction<McpSyncServerExchange, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler;
	}

}
