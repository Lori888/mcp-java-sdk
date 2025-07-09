package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.util.Assert;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * jdk8 refactored version based on the official implementation
 *
 * @author luolp
 * @date 2025/5/23
 */
public final class McpSchema {

    private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

    private McpSchema() {
    }

    public static final String LATEST_PROTOCOL_VERSION = "2024-11-05";

    public static final String JSONRPC_VERSION = "2.0";

    // ---------------------------
    // Method Names
    // ---------------------------

    // Lifecycle Methods
    public static final String METHOD_INITIALIZE = "initialize";

    public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";

    public static final String METHOD_PING = "ping";

    // Tool Methods
    public static final String METHOD_TOOLS_LIST = "tools/list";

    public static final String METHOD_TOOLS_CALL = "tools/call";

    public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

    // Resources Methods
    public static final String METHOD_RESOURCES_LIST = "resources/list";

    public static final String METHOD_RESOURCES_READ = "resources/read";

    public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

    public static final String METHOD_NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/updated";

    public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";

    public static final String METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe";

    public static final String METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";

    // Prompt Methods
    public static final String METHOD_PROMPT_LIST = "prompts/list";

    public static final String METHOD_PROMPT_GET = "prompts/get";

    public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

    public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

    // Logging Methods
    public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";

    public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

    // Roots Methods
    public static final String METHOD_ROOTS_LIST = "roots/list";

    public static final String METHOD_NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed";

    // Sampling Methods
    public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ---------------------------
    // JSON-RPC Error Codes
    // ---------------------------

    /**
     * Standard error codes used in MCP JSON-RPC responses.
     */
    public static final class ErrorCodes {

        /**
         * Invalid JSON was received by the server.
         */
        public static final int PARSE_ERROR = -32700;

        /**
         * The JSON sent is not a valid Request object.
         */
        public static final int INVALID_REQUEST = -32600;

        /**
         * The method does not exist / is not available.
         */
        public static final int METHOD_NOT_FOUND = -32601;

        /**
         * Invalid method parameter(s).
         */
        public static final int INVALID_PARAMS = -32602;

        /**
         * Internal JSON-RPC error.
         */
        public static final int INTERNAL_ERROR = -32603;

    }

    public interface Request {
    }

    private static final TypeReference<HashMap<String, Object>> MAP_TYPE_REF = new TypeReference<HashMap<String, Object>>() {
    };

    /**
     * Deserializes a JSON string into a JSONRPCMessage object.
     *
     * @param objectMapper The ObjectMapper instance to use for deserialization
     * @param jsonText     The JSON string to deserialize
     * @return A JSONRPCMessage instance using either the {@link JSONRPCRequest},
     * {@link JSONRPCNotification}, or {@link JSONRPCResponse} classes.
     * @throws IOException              If there's an error during deserialization
     * @throws IllegalArgumentException If the JSON structure doesn't match any known
     *                                  message type
     */
    public static JSONRPCMessage deserializeJsonRpcMessage(ObjectMapper objectMapper, String jsonText)
            throws IOException {
        logger.debug("Received JSON message: {}", jsonText);

        HashMap<String, Object> map = objectMapper.readValue(jsonText, MAP_TYPE_REF);

        // Determine message type based on specific JSON structure
        if (map.containsKey("method") && map.containsKey("id")) {
            return (JSONRPCMessage) objectMapper.convertValue(map, JSONRPCRequest.class);
        } else if (map.containsKey("method") && !map.containsKey("id")) {
            return (JSONRPCMessage) objectMapper.convertValue(map, JSONRPCNotification.class);
        } else if (map.containsKey("result") || map.containsKey("error")) {
            return (JSONRPCMessage) objectMapper.convertValue(map, JSONRPCResponse.class);
        }

        throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
    }

    // ---------------------------
    // JSON-RPC Message Types
    // ---------------------------
    public interface JSONRPCMessage {
        String getJsonrpc();
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JSONRPCRequest implements JSONRPCMessage {
        @JsonProperty("jsonrpc")
        String jsonrpc;

        @JsonProperty("method")
        String method;

        @JsonProperty("id")
        Object id;

        @JsonProperty("params")
        Object params;

        @Override
        public String getJsonrpc() {
            return jsonrpc;
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JSONRPCNotification implements JSONRPCMessage {
        @JsonProperty("jsonrpc")
        String jsonrpc;

        @JsonProperty("method")
        String method;

        @JsonProperty("params")
        Object params;

        @Override
        public String getJsonrpc() {
            return jsonrpc;
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JSONRPCResponse implements JSONRPCMessage {
        @JsonProperty("jsonrpc")
        String jsonrpc;

        @JsonProperty("id")
        Object id;

        @JsonProperty("result")
        Object result;

        @JsonProperty("error")
        JSONRPCError error;

        @Override
        public String getJsonrpc() {
            return jsonrpc;
        }

        @Value
        @NoArgsConstructor(force = true)
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class JSONRPCError {
            @JsonProperty("code")
            int code;

            @JsonProperty("message")
            String message;

            @JsonProperty("data")
            Object data;
        }
    }

    // ---------------------------
    // Initialization
    // ---------------------------
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InitializeRequest implements Request {
        @JsonProperty("protocolVersion")
        String protocolVersion;

        @JsonProperty("capabilities")
        ClientCapabilities capabilities;

        @JsonProperty("clientInfo")
        Implementation clientInfo;
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InitializeResult {
        @JsonProperty("protocolVersion")
        String protocolVersion;

        @JsonProperty("capabilities")
        ServerCapabilities capabilities;

        @JsonProperty("serverInfo")
        Implementation serverInfo;

        @JsonProperty("instructions")
        String instructions;
    }

    /**
     * Clients can implement additional features to enrich connected MCP servers with
     * additional capabilities. These capabilities can be used to extend the functionality
     * of the server, or to provide additional information to the server about the
     * client's capabilities.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClientCapabilities {
        /**
         * WIP
         */
        @JsonProperty("experimental")
        Map<String, Object> experimental;

        /**
         * define the boundaries of where servers can operate within the filesystem,
         * allowing them to understand which directories and files they have access to.
         */
        @JsonProperty("roots")
        RootCapabilities roots;

        /**
         * Provides a standardized way for servers to request LLM
         * (“completions” or “generations”) from language models via clients.
         */
        @JsonProperty("sampling")
        Sampling sampling;

        /**
         * Roots define the boundaries of where servers can operate within the filesystem,
         * allowing them to understand which directories and files they have access to.
         * Servers can request the list of roots from supporting clients and
         * receive notifications when that list changes.
         */
        @Value
        @NoArgsConstructor(force = true)
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class RootCapabilities {
            /**
             * Whether the client would send notification about roots has changed since the last time the server checked.
             */
            @JsonProperty("listChanged")
            Boolean listChanged;
        }

        /**
         * Provides a standardized way for servers to request LLM
         * sampling ("completions" or "generations") from language
         * models via clients. This flow allows clients to maintain
         * control over model access, selection, and permissions
         * while enabling servers to leverage AI capabilities—with
         * no server API keys necessary. Servers can request text or
         * image-based interactions and optionally include context
         * from MCP servers in their prompts.
         */
        @Value
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public static class Sampling {
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Map<String, Object> experimental;
            private RootCapabilities roots;
            private Sampling sampling;

            public Builder experimental(Map<String, Object> experimental) {
                this.experimental = experimental;
                return this;
            }

            public Builder roots(Boolean listChanged) {
                this.roots = new RootCapabilities(listChanged);
                return this;
            }

            public Builder sampling() {
                this.sampling = new Sampling();
                return this;
            }

            public ClientCapabilities build() {
                return new ClientCapabilities(experimental, roots, sampling);
            }
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServerCapabilities {
        @JsonProperty("completions")
        CompletionCapabilities completions;

        @JsonProperty("experimental")
        Map<String, Object> experimental;

        @JsonProperty("logging")
        LoggingCapabilities logging;

        @JsonProperty("prompts")
        PromptCapabilities prompts;

        @JsonProperty("resources")
        ResourceCapabilities resources;

        @JsonProperty("tools")
        ToolCapabilities tools;

        @Value
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public static class CompletionCapabilities {
        }

        @Value
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public static class LoggingCapabilities {
        }

        @Value
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public static class PromptCapabilities {
            @JsonProperty("listChanged")
            Boolean listChanged;
        }

        @Value
        @NoArgsConstructor(force = true)
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public static class ResourceCapabilities {
            @JsonProperty("subscribe")
            Boolean subscribe;

            @JsonProperty("listChanged")
            Boolean listChanged;
        }

        @Value
        @NoArgsConstructor(force = true)
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public static class ToolCapabilities {
            @JsonProperty("listChanged")
            Boolean listChanged;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private CompletionCapabilities completions;
            private Map<String, Object> experimental;
            private LoggingCapabilities logging = new LoggingCapabilities();
            private PromptCapabilities prompts;
            private ResourceCapabilities resources;
            private ToolCapabilities tools;

            public Builder completions() {
                this.completions = new CompletionCapabilities();
                return this;
            }

            public Builder experimental(Map<String, Object> experimental) {
                this.experimental = experimental;
                return this;
            }

            public Builder logging() {
                this.logging = new LoggingCapabilities();
                return this;
            }

            public Builder prompts(Boolean listChanged) {
                this.prompts = new PromptCapabilities(listChanged);
                return this;
            }

            public Builder resources(Boolean subscribe, Boolean listChanged) {
                this.resources = new ResourceCapabilities(subscribe, listChanged);
                return this;
            }

            public Builder tools(Boolean listChanged) {
                this.tools = new ToolCapabilities(listChanged);
                return this;
            }

            public ServerCapabilities build() {
                return new ServerCapabilities(completions, experimental, logging, prompts, resources, tools);
            }
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Implementation {
        @JsonProperty("name")
        String name;

        @JsonProperty("version")
        String version;
    }

    // Existing Enums and Base Types (from previous implementation)
    public enum Role {// @formatter:off

        @JsonProperty("user") USER,
        @JsonProperty("assistant") ASSISTANT
    }// @formatter:on

    // ---------------------------
    // Resource Interfaces
    // ---------------------------

    /**
     * Base for objects that include optional annotations for the client. The client can
     * use annotations to inform how objects are used or displayed
     */
    public interface Annotated {

        Annotations annotations();

    }

    /**
     * Optional annotations for the client. The client can use annotations to inform how
     * objects are used or displayed.
     */
    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Annotations {
        /**
         * Describes who the intended customer of this object or data is. It
         * can include multiple entries to indicate content useful for multiple audiences
         * (e.g., `["user", "assistant"]`).
         */
        @JsonProperty("audience")
        List<Role> audience;

        /**
         * Describes how important this data is for operating the server. A
         * value of 1 means "most important," and indicates that the data is effectively
         * required, while 0 means "least important," and indicates that the data is entirely
         * optional. It is a number between 0 and 1.
         */
        @JsonProperty("priority")
        Double priority;
    }

    /**
     * A known resource that the server is capable of reading.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Resource implements Annotated {
        /**
         * the URI of the resource.
         */
        @JsonProperty("uri")
        String uri;

        /**
         * A human-readable name for this resource. This can be used by clients to
         * populate UI elements.
         */
        @JsonProperty("name")
        String name;

        /**
         * A description of what this resource represents. This can be used
         * by clients to improve the LLM's understanding of available resources. It can be
         * thought of like a "hint" to the model.
         */
        @JsonProperty("description")
        String description;

        /**
         * The MIME type of this resource, if known.
         */
        @JsonProperty("mimeType")
        String mimeType;

        /**
         * Optional annotations for the client. The client can use
         * annotations to inform how objects are used or displayed.
         */
        @JsonProperty("annotations")
        Annotations annotations;

        @Override
        public Annotations annotations() {
            return annotations;
        }
    }

    /**
     * Resource templates allow servers to expose parameterized resources using URI
     * templates.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6570">RFC 6570</a>
     */
    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceTemplate implements Annotated {
        /**
         * A URI template that can be used to generate URIs for this resource.
         */
        @JsonProperty("uriTemplate")
        String uriTemplate;

        /**
         * A human-readable name for this resource. This can be used by clients to populate UI elements.
         */
        @JsonProperty("name")
        String name;

        /**
         * A description of what this resource represents. This can be used
         * by clients to improve the LLM's understanding of available resources. It can be
         * thought of like a "hint" to the model.
         */
        @JsonProperty("description")
        String description;

        /**
         * IME type of this resource, if known.
         */
        @JsonProperty("mimeType")
        String mimeType;

        /**
         * Optional annotations for the client. The client can use
         * annotations to inform how objects are used or displayed.
         */
        @JsonProperty("annotations")
        Annotations annotations;

        @Override
        public Annotations annotations() {
            return annotations;
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListResourcesResult {
        @JsonProperty("resources")
        List<Resource> resources;

        @JsonProperty("nextCursor")
        String nextCursor;
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListResourceTemplatesResult {
        @JsonProperty("resourceTemplates")
        List<ResourceTemplate> resourceTemplates;

        @JsonProperty("nextCursor")
        String nextCursor;
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReadResourceRequest {
        @JsonProperty("uri")
        String uri;
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReadResourceResult {
        @JsonProperty("contents")
        List<ResourceContents> contents;
    }

    /**
     * Sent from the client to request resources/updated notifications from the server
     * whenever a particular resource changes.
     */
    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubscribeRequest {
        /**
         * the URI of the resource to subscribe to. The URI can use any protocol;
         * it is up to the server how to interpret it.
         */
        @JsonProperty("uri")
        String uri;
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UnsubscribeRequest {
        @JsonProperty("uri")
        String uri;
    }

    /**
     * The contents of a specific resource or sub-resource.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, include = JsonTypeInfo.As.PROPERTY)
    @JsonSubTypes({@JsonSubTypes.Type(value = TextResourceContents.class, name = "text"),
            @JsonSubTypes.Type(value = BlobResourceContents.class, name = "blob")})
    public interface ResourceContents {

        /**
         * The URI of this resource.
         *
         * @return the URI of this resource.
         */
        String uri();

        /**
         * The MIME type of this resource.
         *
         * @return the MIME type of this resource.
         */
        String mimeType();

    }

    /**
     * Text contents of a resource.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextResourceContents implements ResourceContents {
        /**
         * the URI of this resource.
         */
        @JsonProperty("uri")
        String uri;

        /**
         * the MIME type of this resource.
         */
        @JsonProperty("mimeType")
        String mimeType;

        /**
         * the text of the resource. This must only be set if the resource can
         * actually be represented as text (not binary data).
         */
        @JsonProperty("text")
        String text;

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String mimeType() {
            return mimeType;
        }
    }

    /**
     * Binary contents of a resource.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlobResourceContents implements ResourceContents {
        /**
         * the URI of this resource.
         */
        @JsonProperty("uri")
        String uri;

        /**
         * the MIME type of this resource.
         */
        @JsonProperty("mimeType")
        String mimeType;

        /**
         * a base64-encoded string representing the binary data of the resource.
         * This must only be set if the resource can actually be represented as binary data
         * (not text).
         */
        @JsonProperty("blob")
        String blob;

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String mimeType() {
            return mimeType;
        }
    }

    // ---------------------------
    // Prompt Interfaces
    // ---------------------------

    /**
     * A prompt or prompt template that the server offers.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Prompt {
        /**
         * The name of the prompt or prompt template.
         */
        @JsonProperty("name")
        String name;

        /**
         * An optional description of what this prompt provides.
         */
        @JsonProperty("description")
        String description;

        /**
         * A list of arguments to use for templating the prompt.
         */
        @JsonProperty("arguments")
        List<PromptArgument> arguments;
    }

    /**
     * Describes an argument that a prompt can accept.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptArgument {
        /**
         * The name of the argument.
         */
        @JsonProperty("name")
        String name;

        /**
         * A human-readable description of the argument.
         */
        @JsonProperty("description")
        String description;

        /**
         * Whether this argument must be provided.
         */
        @JsonProperty("required")
        Boolean required;
    }

    /**
     * Describes a message returned as part of a prompt.
     * <p>
     * This is similar to `SamplingMessage`, but also supports the embedding of resources
     * from the MCP server.
     */
    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptMessage {
        /**
         * The sender or recipient of messages and data in a conversation.
         */
        @JsonProperty("role")
        Role role;

        /**
         * The content of the message of type {@link Content}.
         */
        @JsonProperty("content")
        Content content;
    }

    /**
     * The server's response to a prompts/list request from the client.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListPromptsResult {
        /**
         * A list of prompts that the server provides.
         */
        @JsonProperty("prompts")
        List<Prompt> prompts;

        /**
         * An optional cursor for pagination. If present, indicates there
         * are more prompts available.
         */
        @JsonProperty("nextCursor")
        String nextCursor;
    }

    /**
     * Used by the client to get a prompt provided by the server.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GetPromptRequest implements Request {
        /**
         * The name of the prompt or prompt template.
         */
        @JsonProperty("name")
        String name;

        /**
         * Arguments to use for templating the prompt.
         */
        @JsonProperty("arguments")
        Map<String, Object> arguments;
    }

    /**
     * The server's response to a prompts/get request from the client.
     */
    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GetPromptResult {
        /**
         * An optional description for the prompt.
         */
        @JsonProperty("description")
        String description;

        /**
         * A list of messages to display as part of the prompt.
         */
        @JsonProperty("messages")
        List<PromptMessage> messages;
    }

    // ---------------------------
    // Tool Interfaces
    // ---------------------------

    /**
     * The server's response to a tools/list request from the client.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListToolsResult {
        /**
         * A list of tools that the server provides.
         */
        @JsonProperty("tools")
        List<Tool> tools;

        /**
         * An optional cursor for pagination. If present, indicates there
         * are more tools available.
         */
        @JsonProperty("nextCursor")
        String nextCursor;
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonSchema {
        @JsonProperty("type")
        String type;

        @JsonProperty("properties")
        Map<String, Object> properties;

        @JsonProperty("required")
        List<String> required;

        @JsonProperty("additionalProperties")
        Boolean additionalProperties;

        @JsonProperty("$defs")
        Map<String, Object> defs;

        @JsonProperty("definitions")
        Map<String, Object> definitions;
    }

    /**
     * Represents a tool that the server provides. Tools enable servers to expose
     * executable functionality to the system. Through these tools, you can interact with
     * external systems, perform computations, and take actions in the real world.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tool {
        /**
         * A unique identifier for the tool. This name is used when calling the tool.
         */
        @JsonProperty("name")
        String name;

        /**
         * A human-readable description of what the tool does. This can be
         * used by clients to improve the LLM's understanding of available tools.
         */
        @JsonProperty("description")
        String description;

        /**
         * A JSON Schema object that describes the expected structure of
         * the arguments when calling this tool. This allows clients to validate tool
         * arguments before sending them to the server.
         */
        @JsonProperty("inputSchema")
        JsonSchema inputSchema;

        public Tool(String name, String description, String schema) {
            this.name = name;
            this.description = description;
            this.inputSchema = parseSchema(schema);
        }
    }

    private static JsonSchema parseSchema(String schema) {
        try {
            return OBJECT_MAPPER.readValue(schema, JsonSchema.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid schema: " + schema, e);
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CallToolRequest implements Request {
        @JsonProperty("name")
        String name;

        @JsonProperty("arguments")
        Map<String, Object> arguments;

        public CallToolRequest(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public CallToolRequest(String name, String jsonArguments) {
            this.name = name;
            this.arguments = parseJsonArguments(jsonArguments);
        }

        private static Map<String, Object> parseJsonArguments(String jsonArguments) {
            try {
                return OBJECT_MAPPER.readValue(jsonArguments, MAP_TYPE_REF);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid arguments: " + jsonArguments, e);
            }
        }
    }

    /**
     * The server's response to a tools/call request from the client.
     */
    @Value
    @NoArgsConstructor(force = true)
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CallToolResult {
        /**
         * A list of content items representing the tool's output. Each item can be text, an image,
         * or an embedded resource.
         */
        @JsonProperty("content")
        List<Content> content;

        /**
         * If true, indicates that the tool execution failed and the content contains error information.
         * If false or absent, indicates successful execution.
         */
        @JsonProperty("isError")
        Boolean isError;

        public CallToolResult(List<Content> content, Boolean isError) {
            this.content = content;
            this.isError = isError;
        }

        /**
         * Creates a new instance of {@link CallToolResult} with a string containing the
         * tool result.
         *
         * @param content The content of the tool result. This will be mapped to a one-sized list
         *                with a {@link TextContent} element.
         * @param isError If true, indicates that the tool execution failed and the content contains error information.
         *                If false or absent, indicates successful execution.
         */
        public CallToolResult(String content, Boolean isError) {
            this.content = Collections.singletonList(new TextContent(content));
            this.isError = isError;
        }

        /**
         * Creates a builder for {@link CallToolResult}.
         *
         * @return a new builder instance
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder for {@link CallToolResult}.
         */
        public static class Builder {
            private List<Content> content = new ArrayList<>();
            private Boolean isError;

            /**
             * Sets the content list for the tool result.
             *
             * @param content the content list
             * @return this builder
             */
            public Builder content(List<Content> content) {
                Assert.notNull(content, "content must not be null");
                this.content = content;
                return this;
            }

            /**
             * Sets the text content for the tool result.
             *
             * @param textContent the text content
             * @return this builder
             */
            public Builder textContent(List<String> textContent) {
                Assert.notNull(textContent, "textContent must not be null");
                textContent.stream()
                        .map(TextContent::new)
                        .forEach(this.content::add);
                return this;
            }

            /**
             * Adds a content item to the tool result.
             *
             * @param contentItem the content item to add
             * @return this builder
             */
            public Builder addContent(Content contentItem) {
                Assert.notNull(contentItem, "contentItem must not be null");
                if (this.content == null) {
                    this.content = new ArrayList<>();
                }
                this.content.add(contentItem);
                return this;
            }

            /**
             * Adds a text content item to the tool result.
             *
             * @param text the text content
             * @return this builder
             */
            public Builder addTextContent(String text) {
                Assert.notNull(text, "text must not be null");
                return addContent(new TextContent(text));
            }

            /**
             * Sets whether the tool execution resulted in an error.
             *
             * @param isError true if the tool execution failed, false otherwise
             * @return this builder
             */
            public Builder isError(Boolean isError) {
                Assert.notNull(isError, "isError must not be null");
                this.isError = isError;
                return this;
            }

            /**
             * Builds a new {@link CallToolResult} instance.
             *
             * @return a new CallToolResult instance
             */
            public CallToolResult build() {
                return new CallToolResult(content, isError);
            }
        }

    }

    // ---------------------------
    // Sampling Interfaces
    // ---------------------------
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelPreferences {
        @JsonProperty("hints")
        List<ModelHint> hints;

        @JsonProperty("costPriority")
        Double costPriority;

        @JsonProperty("speedPriority")
        Double speedPriority;

        @JsonProperty("intelligencePriority")
        Double intelligencePriority;

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private List<ModelHint> hints;
            private Double costPriority;
            private Double speedPriority;
            private Double intelligencePriority;

            public Builder hints(List<ModelHint> hints) {
                this.hints = hints;
                return this;
            }

            public Builder addHint(String name) {
                if (this.hints == null) {
                    this.hints = new ArrayList<>();
                }
                this.hints.add(new ModelHint(name));
                return this;
            }

            public Builder costPriority(Double costPriority) {
                this.costPriority = costPriority;
                return this;
            }

            public Builder speedPriority(Double speedPriority) {
                this.speedPriority = speedPriority;
                return this;
            }

            public Builder intelligencePriority(Double intelligencePriority) {
                this.intelligencePriority = intelligencePriority;
                return this;
            }

            public ModelPreferences build() {
                return new ModelPreferences(hints, costPriority, speedPriority, intelligencePriority);
            }
        }
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelHint {

        @JsonProperty("name")
        String name;

        public static ModelHint of(String name) {
            return new ModelHint(name);
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SamplingMessage {
        @JsonProperty("role")
        Role role;

        @JsonProperty("content")
        Content content;
    }

    // Sampling and Message Creation
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateMessageRequest implements Request {
        @JsonProperty("messages")
        List<SamplingMessage> messages;

        @JsonProperty("modelPreferences")
        ModelPreferences modelPreferences;

        @JsonProperty("systemPrompt")
        String systemPrompt;

        @JsonProperty("includeContext")
        ContextInclusionStrategy includeContext;

        @JsonProperty("temperature")
        Double temperature;

        @JsonProperty("maxTokens")
        int maxTokens;

        @JsonProperty("stopSequences")
        List<String> stopSequences;

        @JsonProperty("metadata")
        Map<String, Object> metadata;

        public enum ContextInclusionStrategy {
            @JsonProperty("none") NONE,
            @JsonProperty("thisServer") THIS_SERVER,
            @JsonProperty("allServers") ALL_SERVERS
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private List<SamplingMessage> messages;
            private ModelPreferences modelPreferences;
            private String systemPrompt;
            private ContextInclusionStrategy includeContext;
            private Double temperature;
            private int maxTokens;
            private List<String> stopSequences;
            private Map<String, Object> metadata;

            public Builder messages(List<SamplingMessage> messages) {
                this.messages = messages;
                return this;
            }

            public Builder modelPreferences(ModelPreferences modelPreferences) {
                this.modelPreferences = modelPreferences;
                return this;
            }

            public Builder systemPrompt(String systemPrompt) {
                this.systemPrompt = systemPrompt;
                return this;
            }

            public Builder includeContext(ContextInclusionStrategy includeContext) {
                this.includeContext = includeContext;
                return this;
            }

            public Builder temperature(Double temperature) {
                this.temperature = temperature;
                return this;
            }

            public Builder maxTokens(int maxTokens) {
                this.maxTokens = maxTokens;
                return this;
            }

            public Builder stopSequences(List<String> stopSequences) {
                this.stopSequences = stopSequences;
                return this;
            }

            public Builder metadata(Map<String, Object> metadata) {
                this.metadata = metadata;
                return this;
            }

            public CreateMessageRequest build() {
                return new CreateMessageRequest(messages, modelPreferences, systemPrompt,
                        includeContext, temperature, maxTokens, stopSequences, metadata);
            }
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateMessageResult {
        @JsonProperty("role")
        Role role;

        @JsonProperty("content")
        Content content;

        @JsonProperty("model")
        String model;

        @JsonProperty("stopReason")
        StopReason stopReason;

        public enum StopReason {
            @JsonProperty("endTurn") END_TURN,
            @JsonProperty("stopSequence") STOP_SEQUENCE,
            @JsonProperty("maxTokens") MAX_TOKENS
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Role role = Role.ASSISTANT;
            private Content content;
            private String model;
            private StopReason stopReason = StopReason.END_TURN;

            public Builder role(Role role) {
                this.role = role;
                return this;
            }

            public Builder content(Content content) {
                this.content = content;
                return this;
            }

            public Builder model(String model) {
                this.model = model;
                return this;
            }

            public Builder stopReason(StopReason stopReason) {
                this.stopReason = stopReason;
                return this;
            }

            public Builder message(String message) {
                this.content = new TextContent(message);
                return this;
            }

            public CreateMessageResult build() {
                return new CreateMessageResult(role, content, model, stopReason);
            }
        }
    }

    // ---------------------------
    // Pagination Interfaces
    // ---------------------------
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaginatedRequest {
        @JsonProperty("cursor")
        String cursor;
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaginatedResult {
        @JsonProperty("nextCursor")
        String nextCursor;
    }

    // ---------------------------
    // Progress and Logging
    // ---------------------------
    @Value
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProgressNotification {
        @JsonProperty("progressToken")
        String progressToken;

        @JsonProperty("progress")
        double progress;

        @JsonProperty("total")
        Double total;
    }

    /**
     * The Model Context Protocol (MCP) provides a standardized way for servers to send
     * resources update message to clients.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourcesUpdatedNotification {
        /**
         * The updated resource uri.
         */
        @JsonProperty("uri")
        String uri;
    }

    /**
     * The Model Context Protocol (MCP) provides a standardized way for servers to send
     * structured log messages to clients. Clients can control logging verbosity by
     * setting minimum log levels, with servers sending notifications containing severity
     * levels, optional logger names, and arbitrary JSON-serializable data.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoggingMessageNotification {
        /**
         * The severity levels. The minimum log level is set by the client.
         */
        @JsonProperty("level")
        LoggingLevel level;

        /**
         * The logger that generated the message.
         */
        @JsonProperty("logger")
        String logger;

        /**
         * JSON-serializable logging data.
         */
        @JsonProperty("data")
        String data;

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private LoggingLevel level = LoggingLevel.INFO;
            private String logger = "server";
            private String data;

            public Builder level(LoggingLevel level) {
                this.level = level;
                return this;
            }

            public Builder logger(String logger) {
                this.logger = logger;
                return this;
            }

            public Builder data(String data) {
                this.data = data;
                return this;
            }

            public LoggingMessageNotification build() {
                return new LoggingMessageNotification(level, logger, data);
            }
        }
    }

    public enum LoggingLevel {// @formatter:off
        @JsonProperty("debug") DEBUG(0),
        @JsonProperty("info") INFO(1),
        @JsonProperty("notice") NOTICE(2),
        @JsonProperty("warning") WARNING(3),
        @JsonProperty("error") ERROR(4),
        @JsonProperty("critical") CRITICAL(5),
        @JsonProperty("alert") ALERT(6),
        @JsonProperty("emergency") EMERGENCY(7);

        private final int level;

        LoggingLevel(int level) {
            this.level = level;
        }

        public int level() {
            return level;
        }

    } // @formatter:on

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SetLevelRequest {
        @JsonProperty("level")
        LoggingLevel level;
    }

    // ---------------------------
    // Autocomplete
    // ---------------------------
    public interface CompleteReference {

        String type();

        String identifier();

    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptReference implements McpSchema.CompleteReference {
        @JsonProperty("type")
        String type;

        @JsonProperty("name")
        String name;

        public PromptReference(String name) {
            this.type = "ref/prompt";
            this.name = name;
        }

        public PromptReference(String type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public String identifier() {
            return name;
        }
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceReference implements McpSchema.CompleteReference {
        @JsonProperty("type")
        String type;

        @JsonProperty("uri")
        String uri;

        public ResourceReference(String uri) {
            this.type = "ref/resource";
            this.uri = uri;
        }

        public ResourceReference(String type, String uri) {
            this.type = type;
            this.uri = uri;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public String identifier() {
            return uri;
        }
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompleteRequest implements Request {
        @JsonProperty("ref")
        McpSchema.CompleteReference ref;

        @JsonProperty("argument")
        CompleteArgument argument;

        @Value
        public static class CompleteArgument {
            @JsonProperty("name")
            String name;

            @JsonProperty("value")
            String value;
        }
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompleteResult {
        @JsonProperty("completion")
        CompleteCompletion completion;

        @Value
        public static class CompleteCompletion {
            @JsonProperty("values")
            List<String> values;

            @JsonProperty("total")
            Integer total;

            @JsonProperty("hasMore")
            Boolean hasMore;
        }
    }

    // ---------------------------
    // Content Types
    // ---------------------------
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({@JsonSubTypes.Type(value = TextContent.class, name = "text"),
            @JsonSubTypes.Type(value = ImageContent.class, name = "image"),
            @JsonSubTypes.Type(value = EmbeddedResource.class, name = "resource")})
    public interface Content {

        default String type() {
            if (this instanceof TextContent) {
                return "text";
            } else if (this instanceof ImageContent) {
                return "image";
            } else if (this instanceof EmbeddedResource) {
                return "resource";
            }
            throw new IllegalArgumentException("Unknown content type: " + this);
        }

    }

    @Value
    @NoArgsConstructor(force = true)
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextContent implements Content {
        @JsonProperty("audience")
        List<Role> audience;

        @JsonProperty("priority")
        Double priority;

        @JsonProperty("text")
        String text;

        public TextContent(String content) {
            this.audience = null;
            this.priority = null;
            this.text = content;
        }
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageContent implements Content {
        @JsonProperty("audience")
        List<Role> audience;

        @JsonProperty("priority")
        Double priority;

        @JsonProperty("data")
        String data;

        @JsonProperty("mimeType")
        String mimeType;
    }

    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddedResource implements Content {
        @JsonProperty("audience")
        List<Role> audience;

        @JsonProperty("priority")
        Double priority;

        @JsonProperty("resource")
        ResourceContents resource;
    }

    // ---------------------------
    // Roots
    // ---------------------------

    /**
     * Represents a root directory or file that the server can operate on.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        /**
         * The URI identifying the root. This *must* start with file:// for now.
         * This restriction may be relaxed in future versions of the protocol to allow other
         * URI schemes.
         */
        @JsonProperty("uri")
        String uri;

        /**
         * An optional name for the root. This can be used to provide a
         * human-readable identifier for the root, which may be useful for display purposes or
         * for referencing the root in other parts of the application.
         */
        @JsonProperty("name")
        String name;
    }

    /**
     * The client's response to a roots/list request from the server. This result contains
     * an array of Root objects, each representing a root directory or file that the
     * server can operate on.
     */
    @Value
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListRootsResult {
        /**
         * An array of Root objects, each representing a root directory or file
         * that the server can operate on.
         */
        @JsonProperty("roots")
        List<Root> roots;
    }
}