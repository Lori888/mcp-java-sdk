/*
 * 2025-07-02 copy from
 * https://github.com/ZachGerman/mcp-java-sdk  StreamableHttpServerTransportProvider branch
 * mcp/src/main/java/io/modelcontextprotocol/spec/SseEvent.java
 * and refactor use jdk8.
 */
package io.modelcontextprotocol.spec;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Value;

@Value
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class SseEvent {
    String id;
    String event;
    String data;
}