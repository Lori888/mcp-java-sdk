/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for the {@link McpSyncClient} with {@link HttpClientSseClientTransport}.
 * 使用已启动的sse服务替代Docker容器进行测试
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class HttpSseMcpAsyncClientTests extends AbstractMcpAsyncClientTests {

	String host = "http://localhost:8080";

	@Override
	protected McpClientTransport createMcpTransport() {
		return HttpClientSseClientTransport.builder(host).build();
	}
}
