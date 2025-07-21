package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
public class HttpClientStreamableHttpSyncClientTests extends AbstractMcpSyncClientTests {

	// Uses the MCP Server using StreamableHttpServerTransport running on localhost
	static String host = "http://localhost:9090";

	@Override
	protected McpClientTransport createMcpTransport() {
		return HttpClientStreamableHttpTransport.builder(host).build();
	}
}
