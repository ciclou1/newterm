package com.chatbi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal newline-delimited JSON-RPC MCP server over stdio. It intentionally
 * has no protocol SDK dependency: the MCP surface used here is initialize,
 * tools/list and tools/call, which is enough for Claude Desktop and Dify.
 */
public final class ChatBiMcpServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PrintWriter out = new PrintWriter(
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
    private final String apiBase = System.getenv().getOrDefault("CHATBI_API_URL", "http://localhost:8080");
    private final String serviceToken = System.getenv().getOrDefault("CHATBI_MCP_SERVICE_TOKEN", "");
    private final String serviceUser = System.getenv().getOrDefault("CHATBI_MCP_SERVICE_USER", "mcp-service");
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public static void main(String[] args) throws Exception {
        ChatBiMcpServer server = new ChatBiMcpServer();
        if (server.serviceToken.isBlank()) {
            server.log("FATAL: CHATBI_MCP_SERVICE_TOKEN is required. "
                    + "Set it to the same value configured on the ChatBI backend.");
            System.exit(2);
        }
        server.run();
    }

    private void run() throws Exception {
        log("ChatBI MCP server started; backend=" + apiBase + ", serviceUser=" + serviceUser);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode request = objectMapper.readTree(line);
                handle(request);
            } catch (Exception e) {
                log("Request failed: " + e.getMessage());
                writeError(null, -32700, "Parse error: " + e.getMessage());
            }
        }
        log("stdin closed, exiting");
    }

    private void handle(JsonNode request) {
        JsonNode id = request.get("id");
        String method = request.path("method").asText("");
        if (id == null || method.startsWith("notifications/")) {
            return;
        }
        try {
            switch (method) {
                case "initialize" -> writeResult(id, initialize());
                case "ping" -> writeResult(id, objectMapper.createObjectNode());
                case "tools/list" -> writeResult(id, tools());
                case "tools/call" -> writeResult(id, callTool(request.path("params")));
                default -> writeError(id, -32601, "Method not found: " + method);
            }
        } catch (IllegalArgumentException e) {
            writeError(id, -32602, e.getMessage());
        } catch (Exception e) {
            log("Method " + method + " failed: " + e.getMessage());
            writeError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private ObjectNode initialize() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "chatbi-mcp-server");
        serverInfo.put("version", "1.0.0");
        result.put("instructions",
                "ChatBI 企业数据问答。调用 ask_data 传入自然语言问题，返回 SQL、结论和表格。");
        return result;
    }

    private ObjectNode tools() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", "ask_data");
        tool.put("description", "查询企业 ERP/EAM 数据。支持销售额、采购、库存、维修、应收、设备等指标。");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("question").put("type", "string")
                .put("description", "自然语言问题，例如：今年各区域销售额");
        properties.putObject("conversationId").put("type", "string")
                .put("description", "可选，多轮追问时传上一次返回的会话 ID");
        ArrayNode required = schema.putArray("required");
        required.add("question");
        return result;
    }

    private ObjectNode callTool(JsonNode params) throws Exception {
        String name = params.path("name").asText("");
        if (!"ask_data".equals(name)) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        JsonNode arguments = params.path("arguments");
        String question = arguments.path("question").asText("").trim();
        if (question.isBlank()) {
            throw new IllegalArgumentException("argument question is required");
        }
        String conversationId = arguments.path("conversationId").asText("");
        JsonNode response = callBackend(question, conversationId);

        String status = response.path("result").path("status").asText("UNKNOWN");
        boolean error = !"OK".equals(status);
        String text = render(response);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        result.put("isError", error);
        return result;
    }

    private JsonNode callBackend(String question, String conversationId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("question", question);
        if (conversationId != null && !conversationId.isBlank()) {
            body.put("conversationId", conversationId);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/api/chat/query"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-ChatBI-Service-Token", serviceToken)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalStateException("ChatBI 服务身份被拒绝（HTTP " + response.statusCode()
                    + "），请确认 MCP 与后端使用相同的 CHATBI_MCP_SERVICE_TOKEN");
        }
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("ChatBI API " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private String render(JsonNode response) {
        JsonNode result = response.path("result");
        String status = result.path("status").asText("UNKNOWN");
        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(status).append('\n');
        String explanation = result.path("explanation").asText("");
        if (!explanation.isBlank()) {
            sb.append("口径: ").append(explanation).append('\n');
        }
        String answer = response.path("answer").asText("");
        if (!answer.isBlank()) {
            sb.append("结论: ").append(answer).append('\n');
        }
        String sql = result.path("finalSql").asText("");
        if (!sql.isBlank()) {
            sb.append("SQL: ").append(sql).append('\n');
        }
        String failure = result.path("failure").path("message").asText("");
        if (!failure.isBlank()) {
            sb.append("失败原因: ").append(failure).append('\n');
        }
        String conversationId = response.path("conversationId").asText("");
        if (!conversationId.isBlank()) {
            sb.append("conversationId: ").append(conversationId).append('\n');
        }

        JsonNode columns = response.path("table").path("columns");
        JsonNode rows = response.path("table").path("rows");
        if (columns.isArray() && !columns.isEmpty() && rows.isArray()) {
            List<String> labels = new ArrayList<>();
            columns.forEach(c -> labels.add(c.path("label").asText(c.path("key").asText(""))));
            sb.append('\n').append(String.join(" | ", labels)).append('\n');
            sb.append(labels.stream().map(l -> "---").reduce((a, b) -> a + " | " + b).orElse(""))
                    .append('\n');
            int limit = Math.min(rows.size(), 20);
            for (int i = 0; i < limit; i++) {
                JsonNode row = rows.get(i);
                List<String> values = new ArrayList<>();
                for (JsonNode column : columns) {
                    String key = column.path("key").asText("");
                    JsonNode cell = row.path(key);
                    values.add(cell.isNumber()
                            ? cell.decimalValue().stripTrailingZeros().toPlainString()
                            : cell.asText(""));
                }
                sb.append(String.join(" | ", values)).append('\n');
            }
            if (rows.size() > limit) {
                sb.append("… 共 ").append(rows.size()).append(" 行，仅展示前 ").append(limit).append(" 行\n");
            }
        }
        JsonNode followUps = response.path("followUps");
        if (followUps.isArray() && !followUps.isEmpty()) {
            List<String> values = new ArrayList<>();
            followUps.forEach(f -> values.add(f.asText()));
            sb.append("\n可继续追问: ").append(String.join("；", values)).append('\n');
        }
        return sb.toString();
    }

    private void writeResult(JsonNode id, ObjectNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        out.println(response);
    }

    private void writeError(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        out.println(response);
    }

    private void log(String message) {
        System.err.println("[chatbi-mcp] " + message);
    }
}
