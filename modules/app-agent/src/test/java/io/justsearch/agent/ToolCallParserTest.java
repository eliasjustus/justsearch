package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.agent.api.ToolCallRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCallParserTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void parseCompleteToolCall() throws Exception {
    var parser = new ToolCallParser();
    JsonNode chunk = MAPPER.readTree("""
        {
          "choices": [{
            "delta": {
              "tool_calls": [{
                "index": 0,
                "id": "call_1",
                "function": {
                  "name": "search",
                  "arguments": "{\\"query\\": \\"test\\"}"
                }
              }]
            }
          }]
        }
        """);

    parser.accumulateChunk(chunk);
    List<ToolCallRequest> calls = parser.drainCompleted();
    assertEquals(1, calls.size());
    assertEquals("call_1", calls.getFirst().id());
    assertEquals("search", calls.getFirst().toolName());
    assertEquals("{\"query\": \"test\"}", calls.getFirst().arguments());
  }

  @Test
  void accumulateIncrementalArguments() throws Exception {
    var parser = new ToolCallParser();

    // First chunk: id and name
    parser.accumulateChunk(MAPPER.readTree("""
        {
          "choices": [{
            "delta": {
              "tool_calls": [{
                "index": 0,
                "id": "call_2",
                "function": {
                  "name": "file_ops",
                  "arguments": "{\\"op\\""
                }
              }]
            }
          }]
        }
        """));

    assertTrue(parser.hasPending());

    // Second chunk: more arguments
    parser.accumulateChunk(MAPPER.readTree("""
        {
          "choices": [{
            "delta": {
              "tool_calls": [{
                "index": 0,
                "function": {
                  "arguments": ": \\"MOVE\\"}"
                }
              }]
            }
          }]
        }
        """));

    List<ToolCallRequest> calls = parser.drainCompleted();
    assertEquals(1, calls.size());
    assertEquals("{\"op\": \"MOVE\"}", calls.getFirst().arguments());
    assertFalse(parser.hasPending());
  }

  @Test
  void isToolCallFinishReason() throws Exception {
    JsonNode toolCallFinish = MAPPER.readTree("""
        {
          "choices": [{
            "finish_reason": "tool_calls",
            "delta": {}
          }]
        }
        """);
    assertTrue(ToolCallParser.isToolCallFinishReason(toolCallFinish));

    JsonNode stopFinish = MAPPER.readTree("""
        {
          "choices": [{
            "finish_reason": "stop",
            "delta": {}
          }]
        }
        """);
    assertFalse(ToolCallParser.isToolCallFinishReason(stopFinish));
  }

  @Test
  void emptyChoicesIgnored() throws Exception {
    var parser = new ToolCallParser();
    parser.accumulateChunk(MAPPER.readTree("{\"choices\": []}"));
    assertFalse(parser.hasPending());
    assertTrue(parser.drainCompleted().isEmpty());
  }

  @Test
  void missingToolCallsIgnored() throws Exception {
    var parser = new ToolCallParser();
    parser.accumulateChunk(MAPPER.readTree("""
        {
          "choices": [{
            "delta": {
              "content": "hello"
            }
          }]
        }
        """));
    assertFalse(parser.hasPending());
  }

  @Test
  void multipleToolCallsInOneChunk() throws Exception {
    var parser = new ToolCallParser();
    parser.accumulateChunk(MAPPER.readTree("""
        {
          "choices": [{
            "delta": {
              "tool_calls": [
                { "index": 0, "id": "c1", "function": { "name": "a", "arguments": "{}" } },
                { "index": 1, "id": "c2", "function": { "name": "b", "arguments": "{}" } }
              ]
            }
          }]
        }
        """));

    List<ToolCallRequest> calls = parser.drainCompleted();
    assertEquals(2, calls.size());
    assertEquals("c1", calls.get(0).id());
    assertEquals("c2", calls.get(1).id());
  }
}
