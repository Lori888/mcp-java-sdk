/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.spec.McpSchema.*;
import io.modelcontextprotocol.util.DeafaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
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
 * <p>
 * The server supports runtime modification of its capabilities through methods like
 * {@link #addTool}, {@link #addResource}, and {@link #addPrompt}, automatically notifying
 * connected clients of changes when configured to do so.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 * @see McpServer
 * @see McpSchema
 * @see McpClientSession
 */
public class McpAsyncServer {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncServer.class);

	private final McpServerTransportProvider mcpTransportProvider;

	private final ObjectMapper objectMapper;

	private final McpSchema.ServerCapabilities serverCapabilities;

	private final McpSchema.Implementation serverInfo;

	private final String instructions;

	private final CopyOnWriteArrayList<McpServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();

	private final CopyOnWriteArrayList<McpSchema.ResourceTemplate> resourceTemplates = new CopyOnWriteArrayList<>();

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncResourceSpecification> resources = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncPromptSpecification> prompts = new ConcurrentHashMap<>();

	// FIXME: this field is deprecated and should be remvoed together with the
	// broadcasting loggingNotification.
	private LoggingLevel minLoggingLevel = LoggingLevel.DEBUG;

	private final ConcurrentHashMap<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new ConcurrentHashMap<>();

	private List<String> protocolVersions = Collections.singletonList(McpSchema.LATEST_PROTOCOL_VERSION);

	private McpUriTemplateManagerFactory uriTemplateManagerFactory = new DeafaultMcpUriTemplateManagerFactory();

	/**
	 * Create a new McpAsyncServer with the given transport provider and capabilities.
	 * @param mcpTransportProvider The transport layer implementation for MCP
	 * communication.
	 * @param features The MCP server supported features.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 */
	McpAsyncServer(McpServerTransportProvider mcpTransportProvider, ObjectMapper objectMapper,
			McpServerFeatures.Async features, Duration requestTimeout,
			McpUriTemplateManagerFactory uriTemplateManagerFactory) {
		this.mcpTransportProvider = mcpTransportProvider;
		this.objectMapper = objectMapper;
		this.serverInfo = features.getServerInfo();
		this.serverCapabilities = features.getServerCapabilities();
		this.instructions = features.getInstructions();
		this.tools.addAll(features.getTools());
		this.resources.putAll(features.getResources());
		this.resourceTemplates.addAll(features.getResourceTemplates());
		this.prompts.putAll(features.getPrompts());
		this.completions.putAll(features.getCompletions());
		this.uriTemplateManagerFactory = uriTemplateManagerFactory;

		Map<String, McpServerSession.RequestHandler<?>> requestHandlers = new HashMap<>();

		// Initialize request handlers for standard MCP methods

		// Ping MUST respond with an empty data, but not NULL response.
		requestHandlers.put(McpSchema.METHOD_PING, (exchange, params) -> Mono.just(Collections.emptyMap()));

		// Add tools API handlers if the tool capability is enabled
		if (this.serverCapabilities.getTools() != null) {
			requestHandlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
			requestHandlers.put(McpSchema.METHOD_TOOLS_CALL, toolsCallRequestHandler());
		}

		// Add resources API handlers if provided
		if (this.serverCapabilities.getResources() != null) {
			requestHandlers.put(McpSchema.METHOD_RESOURCES_LIST, resourcesListRequestHandler());
			requestHandlers.put(McpSchema.METHOD_RESOURCES_READ, resourcesReadRequestHandler());
			requestHandlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, resourceTemplateListRequestHandler());
		}

		// Add prompts API handlers if provider exists
		if (this.serverCapabilities.getPrompts() != null) {
			requestHandlers.put(McpSchema.METHOD_PROMPT_LIST, promptsListRequestHandler());
			requestHandlers.put(McpSchema.METHOD_PROMPT_GET, promptsGetRequestHandler());
		}

		// Add logging API handlers if the logging capability is enabled
		if (this.serverCapabilities.getLogging() != null) {
			requestHandlers.put(McpSchema.METHOD_LOGGING_SET_LEVEL, setLoggerRequestHandler());
		}

		// Add completion API handlers if the completion capability is enabled
		if (this.serverCapabilities.getCompletions() != null) {
			requestHandlers.put(McpSchema.METHOD_COMPLETION_COMPLETE, completionCompleteRequestHandler());
		}

		Map<String, McpServerSession.NotificationHandler> notificationHandlers = new HashMap<>();

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty());

		List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = features
				.getRootsChangeConsumers();

		if (Utils.isEmpty(rootsChangeConsumers)) {
			rootsChangeConsumers = Collections.singletonList((exchange, roots) -> Mono.fromRunnable(() -> logger
					.warn("Roots list changed notification, but no consumers provided. Roots list changed: {}", roots)));
		}

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED,
				asyncRootsListChangedNotificationHandler(rootsChangeConsumers));

		mcpTransportProvider.setSessionFactory(
				transport -> new McpServerSession(UUID.randomUUID().toString(), requestTimeout, transport,
						this::asyncInitializeRequestHandler, Mono::empty, requestHandlers, notificationHandlers));
	}

	// ---------------------------------------
	// Lifecycle Management
	// ---------------------------------------
	private Mono<McpSchema.InitializeResult> asyncInitializeRequestHandler(
			McpSchema.InitializeRequest initializeRequest) {
		return Mono.defer(() -> {
			logger.info("Client initialize request - Protocol: {}, Capabilities: {}, Info: {}",
					initializeRequest.getProtocolVersion(), initializeRequest.getCapabilities(),
					initializeRequest.getClientInfo());

			// The server MUST respond with the highest protocol version it supports
			// if
			// it does not support the requested (e.g. Client) version.
			String serverProtocolVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

			if (this.protocolVersions.contains(initializeRequest.getProtocolVersion())) {
				// If the server supports the requested protocol version, it MUST
				// respond
				// with the same version.
				serverProtocolVersion = initializeRequest.getProtocolVersion();
			}
			else {
				logger.warn(
						"Client requested unsupported protocol version: {}, so the server will suggest the {} version instead",
						initializeRequest.getProtocolVersion(), serverProtocolVersion);
			}

			return Mono.just(new McpSchema.InitializeResult(serverProtocolVersion, this.serverCapabilities,
					this.serverInfo, this.instructions));
		});
	}

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.serverCapabilities;
	}

	/**
	 * Get the server implementation information.
	 * @return The server implementation details
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.serverInfo;
	}

	/**
	 * Gracefully closes the server, allowing any in-progress operations to complete.
	 * @return A Mono that completes when the server has been closed
	 */
	public Mono<Void> closeGracefully() {
		return this.mcpTransportProvider.closeGracefully();
	}

	/**
	 * Close the server immediately.
	 */
	public void close() {
		this.mcpTransportProvider.close();
	}

	private McpServerSession.NotificationHandler asyncRootsListChangedNotificationHandler(
			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers) {
		return (exchange, params) -> exchange.listRoots()
			.flatMap(listRootsResult -> Flux.fromIterable(rootsChangeConsumers)
				.flatMap(consumer -> consumer.apply(exchange, listRootsResult.getRoots()))
				.onErrorResume(error -> {
					logger.error("Error handling roots list change notification", error);
					return Mono.empty();
				})
				.then());
	}

	// ---------------------------------------
	// Tool Management
	// ---------------------------------------

	/**
	 * Add a new tool specification at runtime.
	 * @param toolSpecification The tool specification to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addTool(McpServerFeatures.AsyncToolSpecification toolSpecification) {
		if (toolSpecification == null) {
			return Mono.error(new McpError("Tool specification must not be null"));
		}
		if (toolSpecification.getTool() == null) {
			return Mono.error(new McpError("Tool must not be null"));
		}
		if (toolSpecification.getCall() == null) {
			return Mono.error(new McpError("Tool call handler must not be null"));
		}
		if (this.serverCapabilities.getTools() == null) {
			return Mono.error(new McpError("Server must be configured with tool capabilities"));
		}

		return Mono.defer(() -> {
			// Check for duplicate tool names
			if (this.tools.stream().anyMatch(th -> th.getTool().getName().equals(toolSpecification.getTool().getName()))) {
				return Mono
					.error(new McpError("Tool with name '" + toolSpecification.getTool().getName() + "' already exists"));
			}

			this.tools.add(toolSpecification);
			logger.debug("Added tool handler: {}", toolSpecification.getTool().getName());

			if (this.serverCapabilities.getTools().getListChanged()) {
				return notifyToolsListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * Remove a tool handler at runtime.
	 * @param toolName The name of the tool handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removeTool(String toolName) {
		if (toolName == null) {
			return Mono.error(new McpError("Tool name must not be null"));
		}
		if (this.serverCapabilities.getTools() == null) {
			return Mono.error(new McpError("Server must be configured with tool capabilities"));
		}

		return Mono.defer(() -> {
			boolean removed = this.tools
				.removeIf(toolSpecification -> toolSpecification.getTool().getName().equals(toolName));
			if (removed) {
				logger.debug("Removed tool handler: {}", toolName);
				if (this.serverCapabilities.getTools().getListChanged()) {
					return notifyToolsListChanged();
				}
				return Mono.empty();
			}
			return Mono.error(new McpError("Tool with name '" + toolName + "' not found"));
		});
	}

	/**
	 * Notifies clients that the list of available tools has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyToolsListChanged() {
		return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
	}

	private McpServerSession.RequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
		return (exchange, params) -> {
			/*
			 * 使用JDK8重构前代码为 toList()，现改为 collect(Collectors.toList())，需注意2者区别：
			 * toList()：结果不可以修改、允许空值；
			 * collect(Collectors.toList())：结果可以修改、允许空值；
			 */
			List<Tool> tools = this.tools.stream().map(McpServerFeatures.AsyncToolSpecification::getTool)
					.collect(Collectors.toList());

			return Mono.just(new McpSchema.ListToolsResult(tools, null));
		};
	}

	private McpServerSession.RequestHandler<CallToolResult> toolsCallRequestHandler() {
		return (exchange, params) -> {
			McpSchema.CallToolRequest callToolRequest = objectMapper.convertValue(params,
					new TypeReference<McpSchema.CallToolRequest>() {
					});

			Optional<McpServerFeatures.AsyncToolSpecification> toolSpecification = this.tools.stream()
				.filter(tr -> callToolRequest.getName().equals(tr.getTool().getName()))
				.findAny();

			if (toolSpecification == null) {
				return Mono.error(new McpError("Tool not found: " + callToolRequest.getName()));
			}

			return toolSpecification.map(tool -> tool.getCall().apply(exchange, callToolRequest.getArguments()))
				.orElse(Mono.error(new McpError("Tool not found: " + callToolRequest.getName())));
		};
	}

	// ---------------------------------------
	// Resource Management
	// ---------------------------------------

	/**
	 * Add a new resource handler at runtime.
	 * @param resourceSpecification The resource handler to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addResource(McpServerFeatures.AsyncResourceSpecification resourceSpecification) {
		if (resourceSpecification == null || resourceSpecification.getResource() == null) {
			return Mono.error(new McpError("Resource must not be null"));
		}

		if (this.serverCapabilities.getResources() == null) {
			return Mono.error(new McpError("Server must be configured with resource capabilities"));
		}

		return Mono.defer(() -> {
			if (this.resources.putIfAbsent(resourceSpecification.getResource().getUri(), resourceSpecification) != null) {
				return Mono.error(new McpError(
						"Resource with URI '" + resourceSpecification.getResource().getUri() + "' already exists"));
			}
			logger.debug("Added resource handler: {}", resourceSpecification.getResource().getUri());
			if (this.serverCapabilities.getResources().getListChanged()) {
				return notifyResourcesListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * Remove a resource handler at runtime.
	 * @param resourceUri The URI of the resource handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removeResource(String resourceUri) {
		if (resourceUri == null) {
			return Mono.error(new McpError("Resource URI must not be null"));
		}
		if (this.serverCapabilities.getResources() == null) {
			return Mono.error(new McpError("Server must be configured with resource capabilities"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncResourceSpecification removed = this.resources.remove(resourceUri);
			if (removed != null) {
				logger.debug("Removed resource handler: {}", resourceUri);
				if (this.serverCapabilities.getResources().getListChanged()) {
					return notifyResourcesListChanged();
				}
				return Mono.empty();
			}
			return Mono.error(new McpError("Resource with URI '" + resourceUri + "' not found"));
		});
	}

	/**
	 * Notifies clients that the list of available resources has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyResourcesListChanged() {
		return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
	}

	private McpServerSession.RequestHandler<McpSchema.ListResourcesResult> resourcesListRequestHandler() {
		return (exchange, params) -> {
			/*
			 * 使用JDK8重构前代码为 toList()，现改为 collect(Collectors.toList())，需注意2者区别：
			 * toList()：结果不可以修改、允许空值；
			 * collect(Collectors.toList())：结果可以修改、允许空值；
			 */
			List<Resource> resourceList = this.resources.values()
					.stream()
					.map(McpServerFeatures.AsyncResourceSpecification::getResource)
					.collect(Collectors.toList());
			return Mono.just(new McpSchema.ListResourcesResult(resourceList, null));
		};
	}

	private McpServerSession.RequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
		return (exchange, params) -> Mono
			.just(new McpSchema.ListResourceTemplatesResult(this.getResourceTemplates(), null));

	}

	private List<McpSchema.ResourceTemplate> getResourceTemplates() {
		ArrayList<ResourceTemplate> list = new ArrayList<>(this.resourceTemplates);
		/*
		 * 使用JDK8重构前代码为 toList()，现改为 collect(Collectors.toList())，需注意2者区别：
		 * toList()：结果不可以修改、允许空值；
		 * collect(Collectors.toList())：结果可以修改、允许空值；
		 */
		List<ResourceTemplate> resourceTemplates = this.resources.keySet()
			.stream()
			.filter(uri -> uri.contains("{"))
			.map(uri -> {
				McpSchema.Resource resource = this.resources.get(uri).getResource();
				ResourceTemplate template = new ResourceTemplate(resource.getUri(), resource.getName(), resource.getDescription(),
						resource.getMimeType(), resource.annotations());
				return template;
			})
			.collect(Collectors.toList());

		list.addAll(resourceTemplates);

		return list;
	}

	private McpServerSession.RequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
		return (exchange, params) -> {
			McpSchema.ReadResourceRequest resourceRequest = objectMapper.convertValue(params,
					new TypeReference<McpSchema.ReadResourceRequest>() {
					});
			String resourceUri = resourceRequest.getUri();

			McpServerFeatures.AsyncResourceSpecification specification = this.resources.values()
				.stream()
				.filter(resourceSpecification -> this.uriTemplateManagerFactory
					.create(resourceSpecification.getResource().getUri())
					.matches(resourceUri))
				.findFirst()
				.orElseThrow(() -> new McpError("Resource not found: " + resourceUri));

			return specification.getReadHandler().apply(exchange, resourceRequest);
		};
	}

	// ---------------------------------------
	// Prompt Management
	// ---------------------------------------

	/**
	 * Add a new prompt handler at runtime.
	 * @param promptSpecification The prompt handler to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addPrompt(McpServerFeatures.AsyncPromptSpecification promptSpecification) {
		if (promptSpecification == null) {
			return Mono.error(new McpError("Prompt specification must not be null"));
		}
		if (this.serverCapabilities.getPrompts() == null) {
			return Mono.error(new McpError("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncPromptSpecification specification = this.prompts
				.putIfAbsent(promptSpecification.getPrompt().getName(), promptSpecification);
			if (specification != null) {
				return Mono.error(
						new McpError("Prompt with name '" + promptSpecification.getPrompt().getName() + "' already exists"));
			}

			logger.debug("Added prompt handler: {}", promptSpecification.getPrompt().getName());

			// Servers that declared the listChanged capability SHOULD send a
			// notification,
			// when the list of available prompts changes
			if (this.serverCapabilities.getPrompts().getListChanged()) {
				return notifyPromptsListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * Remove a prompt handler at runtime.
	 * @param promptName The name of the prompt handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removePrompt(String promptName) {
		if (promptName == null) {
			return Mono.error(new McpError("Prompt name must not be null"));
		}
		if (this.serverCapabilities.getPrompts() == null) {
			return Mono.error(new McpError("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncPromptSpecification removed = this.prompts.remove(promptName);

			if (removed != null) {
				logger.debug("Removed prompt handler: {}", promptName);
				// Servers that declared the listChanged capability SHOULD send a
				// notification, when the list of available prompts changes
				if (this.serverCapabilities.getPrompts().getListChanged()) {
					return this.notifyPromptsListChanged();
				}
				return Mono.empty();
			}
			return Mono.error(new McpError("Prompt with name '" + promptName + "' not found"));
		});
	}

	/**
	 * Notifies clients that the list of available prompts has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyPromptsListChanged() {
		return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
	}

	private McpServerSession.RequestHandler<McpSchema.ListPromptsResult> promptsListRequestHandler() {
		return (exchange, params) -> {
			// TODO: Implement pagination
			// McpSchema.PaginatedRequest request = objectMapper.convertValue(params,
			// new TypeReference<McpSchema.PaginatedRequest>() {
			// });

			/*
			 * 使用JDK8重构前代码为 toList()，现改为 collect(Collectors.toList())，需注意2者区别：
			 * toList()：结果不可以修改、允许空值；
			 * collect(Collectors.toList())：结果可以修改、允许空值；
			 */
			List<Prompt> promptList = this.prompts.values()
					.stream()
					.map(McpServerFeatures.AsyncPromptSpecification::getPrompt)
					.collect(Collectors.toList());

			return Mono.just(new McpSchema.ListPromptsResult(promptList, null));
		};
	}

	private McpServerSession.RequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
		return (exchange, params) -> {
			McpSchema.GetPromptRequest promptRequest = objectMapper.convertValue(params,
					new TypeReference<McpSchema.GetPromptRequest>() {
					});

			// Implement prompt retrieval logic here
			McpServerFeatures.AsyncPromptSpecification specification = this.prompts.get(promptRequest.getName());
			if (specification == null) {
				return Mono.error(new McpError("Prompt not found: " + promptRequest.getName()));
			}

			return specification.getPromptHandler().apply(exchange, promptRequest);
		};
	}

	// ---------------------------------------
	// Logging Management
	// ---------------------------------------

	/**
	 * This implementation would, incorrectly, broadcast the logging message to all
	 * connected clients, using a single minLoggingLevel for all of them. Similar to the
	 * sampling and roots, the logging level should be set per client session and use the
	 * ServerExchange to send the logging message to the right client.
	 * @param loggingMessageNotification The logging message to send
	 * @return A Mono that completes when the notification has been sent
	 * @deprecated Use
	 * {@link McpAsyncServerExchange#loggingNotification(LoggingMessageNotification)}
	 * instead.
	 */
	@Deprecated
	public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {

		if (loggingMessageNotification == null) {
			return Mono.error(new McpError("Logging message must not be null"));
		}

		if (loggingMessageNotification.getLevel().level() < minLoggingLevel.level()) {
			return Mono.empty();
		}

		return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_MESSAGE,
				loggingMessageNotification);
	}

	private McpServerSession.RequestHandler<Object> setLoggerRequestHandler() {
		return (exchange, params) -> {
			return Mono.defer(() -> {

				SetLevelRequest newMinLoggingLevel = objectMapper.convertValue(params,
						new TypeReference<SetLevelRequest>() {
						});

				exchange.setMinLoggingLevel(newMinLoggingLevel.getLevel());

				// FIXME: this field is deprecated and should be removed together
				// with the broadcasting loggingNotification.
				this.minLoggingLevel = newMinLoggingLevel.getLevel();

				return Mono.just(Collections.emptyMap());
			});
		};
	}

	private McpServerSession.RequestHandler<McpSchema.CompleteResult> completionCompleteRequestHandler() {
		return (exchange, params) -> {
			McpSchema.CompleteRequest request = parseCompletionParams(params);

			if (request.getRef() == null) {
				return Mono.error(new McpError("ref must not be null"));
			}

			if (request.getRef().type() == null) {
				return Mono.error(new McpError("type must not be null"));
			}

			String type = request.getRef().type();

			String argumentName = request.getArgument().getName();

			// check if the referenced resource exists
			if (type.equals("ref/prompt") && request.getRef() instanceof McpSchema.PromptReference) {
				McpSchema.PromptReference promptReference = (McpSchema.PromptReference) request.getRef();
				McpServerFeatures.AsyncPromptSpecification promptSpec = this.prompts.get(promptReference.getName());
				if (promptSpec == null) {
					return Mono.error(new McpError("Prompt not found: " + promptReference.getName()));
				}
				if (!promptSpec.getPrompt()
						.getArguments()
						.stream()
						.filter(arg -> arg.getName().equals(argumentName))
						.findFirst()
						.isPresent()) {

					return Mono.error(new McpError("Argument not found: " + argumentName));
				}
			}

			if (type.equals("ref/resource") && request.getRef() instanceof McpSchema.ResourceReference) {
				McpSchema.ResourceReference resourceReference = (McpSchema.ResourceReference) request.getRef();
				McpServerFeatures.AsyncResourceSpecification resourceSpec = this.resources.get(resourceReference.getUri());
				if (resourceSpec == null) {
					return Mono.error(new McpError("Resource not found: " + resourceReference.getUri()));
				}
				if (!uriTemplateManagerFactory.create(resourceSpec.getResource().getUri())
					.getVariableNames()
					.contains(argumentName)) {
					return Mono.error(new McpError("Argument not found: " + argumentName));
				}

			}

			McpServerFeatures.AsyncCompletionSpecification specification = this.completions.get(request.getRef());

			if (specification == null) {
				return Mono.error(new McpError("AsyncCompletionSpecification not found: " + request.getRef()));
			}

			return specification.getCompletionHandler().apply(exchange, request);
		};
	}

	/**
	 * Parses the raw JSON-RPC request parameters into a {@link McpSchema.CompleteRequest}
	 * object.
	 * <p>
	 * This method manually extracts the `ref` and `argument` fields from the input map,
	 * determines the correct reference type (either prompt or resource), and constructs a
	 * fully-typed {@code CompleteRequest} instance.
	 * @param object the raw request parameters, expected to be a Map containing "ref" and
	 * "argument" entries.
	 * @return a {@link McpSchema.CompleteRequest} representing the structured completion
	 * request.
	 * @throws IllegalArgumentException if the "ref" type is not recognized.
	 */
	@SuppressWarnings("unchecked")
	private McpSchema.CompleteRequest parseCompletionParams(Object object) {
		Map<String, Object> params = (Map<String, Object>) object;
		Map<String, Object> refMap = (Map<String, Object>) params.get("ref");
		Map<String, Object> argMap = (Map<String, Object>) params.get("argument");

		String refType = (String) refMap.get("type");

		McpSchema.CompleteReference ref = null;
		switch (refType) {
			case "ref/prompt":
				ref = new McpSchema.PromptReference(refType, (String) refMap.get("name"));
				break;
			case "ref/resource":
				ref = new McpSchema.ResourceReference(refType, (String) refMap.get("uri"));
				break;
			default:
				throw new IllegalArgumentException("Invalid ref type: " + refType);
		}

		String argName = (String) argMap.get("name");
		String argValue = (String) argMap.get("value");
		McpSchema.CompleteRequest.CompleteArgument argument = new McpSchema.CompleteRequest.CompleteArgument(argName,
				argValue);

		return new McpSchema.CompleteRequest(ref, argument);
	}

	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions) {
		this.protocolVersions = protocolVersions;
	}

}
