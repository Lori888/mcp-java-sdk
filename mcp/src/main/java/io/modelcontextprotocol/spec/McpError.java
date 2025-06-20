/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;

import java.util.Objects;

public class McpError extends RuntimeException {

	private JSONRPCError jsonRpcError;

	public McpError(JSONRPCError jsonRpcError) {
		super(jsonRpcError.getMessage());
		this.jsonRpcError = jsonRpcError;
	}

	public McpError(Object error) {
		super(Objects.toString(error));
	}

	public JSONRPCError getJsonRpcError() {
		return jsonRpcError;
	}

}