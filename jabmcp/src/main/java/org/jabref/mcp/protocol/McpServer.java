package org.jabref.mcp.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jabref.mcp.service.ArticleReader;
import org.jabref.mcp.service.LibraryManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// JSON-RPC 2.0 stdio transport and dispatcher for the JabRef MCP server.
@NullMarked
public class McpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpServer.class);
    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";

    private final LibraryManager libraryManager;
    private final ArticleReader articleReader;
    private final McpToolHandler toolHandler;
    private final Gson compactGson = new GsonBuilder().disableHtmlEscaping().create();
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public McpServer(
            LibraryManager libraryManager,
            ArticleReader articleReader,
            McpToolHandler toolHandler) {
        this.libraryManager = libraryManager;
        this.articleReader = articleReader;
        this.toolHandler = toolHandler;
    }

    public void run(InputStream in, PrintStream out) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = handleRawMessage(line);
                if (response != null) {
                    out.println(response);
                    out.flush();
                }
            }
        }
    }

    /// Processes a single JSON-RPC 2.0 line and returns the serialized JSON response,
    /// or `null` for notifications that do not expect a response.
    public @Nullable String handleRawMessage(String rawJson) {
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (!parsed.isJsonObject()) {
                return compactGson.toJson(errorResponse(null, -32600, "Invalid Request: expected JSON object"));
            }
            request = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return compactGson.toJson(errorResponse(null, -32700, "Parse error: " + e.getMessage()));
        }

        JsonElement id = request.get("id");
        boolean isNotification = (id == null || id.isJsonNull()) && !request.has("id");

        JsonElement methodEl = request.get("method");
        if (methodEl == null || !methodEl.isJsonPrimitive()) {
            return isNotification ? null : compactGson.toJson(errorResponse(id, -32600, "Invalid Request: missing method"));
        }
        String method = methodEl.getAsString();
        JsonObject params = (request.has("params") && request.get("params").isJsonObject())
                ? request.getAsJsonObject("params")
                : new JsonObject();

        // Notifications must not produce a JSON-RPC response
        if (method.startsWith("notifications/") || isNotification) {
            LOGGER.debug("Received notification: {}", method);
            return null;
        }

        try {
            JsonObject result = dispatch(method, params);
            return compactGson.toJson(successResponse(id, result));
        } catch (MethodNotFoundException e) {
            return compactGson.toJson(errorResponse(id, -32601, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return compactGson.toJson(errorResponse(id, -32602, e.getMessage()));
        } catch (Exception e) {
            LOGGER.error("Internal error handling method {}", method, e);
            return compactGson.toJson(errorResponse(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private JsonObject dispatch(String method, JsonObject params) {
        return switch (method) {
            case "initialize" -> handleInitialize(params);
            case "ping" -> new JsonObject();
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(params);
            case "resources/list" -> handleResourcesList();
            case "resources/read" -> handleResourcesRead(params);
            case "prompts/list" -> handlePromptsList();
            case "prompts/get" -> handlePromptsGet(params);
            default -> throw new MethodNotFoundException("Method not found: " + method);
        };
    }

    private JsonObject handleInitialize(JsonObject params) {
        String protocolVersion = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
                ? params.get("protocolVersion").getAsString()
                : DEFAULT_PROTOCOL_VERSION;

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        capabilities.add("resources", new JsonObject());
        capabilities.add("prompts", new JsonObject());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "jabref-mcp");
        serverInfo.addProperty("version", "1.0.0");

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", protocolVersion);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
                "Use search_articles and list_libraries to browse JabRef libraries, "
                        + "get_article, get_article_outline, read_article_text, and get_article_annotations to read articles, "
                        + "and save_article_review to persist structured reviews, comments, read status, and ratings.");
        return result;
    }

    private JsonObject handleToolsList() {
        JsonObject result = new JsonObject();
        result.add("tools", toolHandler.listTools());
        return result;
    }

    private JsonObject handleToolsCall(JsonObject params) {
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing tool name");
        }
        String name = params.get("name").getAsString();
        JsonObject arguments = (params.has("arguments") && params.get("arguments").isJsonObject())
                ? params.getAsJsonObject("arguments")
                : new JsonObject();
        return toolHandler.callTool(name, arguments);
    }

    private JsonObject handleResourcesList() {
        JsonArray resources = new JsonArray();

        JsonObject librariesRes = new JsonObject();
        librariesRes.addProperty("uri", "jabref://libraries");
        librariesRes.addProperty("name", "Loaded JabRef Libraries");
        librariesRes.addProperty("mimeType", "application/json");
        resources.add(librariesRes);

        for (LibraryManager.LibrarySummary lib : libraryManager.listLibraries()) {
            JsonObject res = new JsonObject();
            res.addProperty("uri", "jabref://library/" + lib.id());
            res.addProperty("name", "JabRef Library: " + lib.id());
            res.addProperty("description", lib.entryCount() + " entries (" + lib.path() + ")");
            res.addProperty("mimeType", "application/json");
            resources.add(res);
        }

        JsonObject result = new JsonObject();
        result.add("resources", resources);
        return result;
    }

    private JsonObject handleResourcesRead(JsonObject params) {
        if (!params.has("uri") || !params.get("uri").isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing resource uri");
        }
        String uri = params.get("uri").getAsString();
        String json;
        if ("jabref://libraries".equals(uri)) {
            json = prettyGson.toJson(libraryManager.listLibraries());
        } else if (uri.startsWith("jabref://library/")) {
            String libId = uri.substring("jabref://library/".length());
            List<ArticleReader.ArticleSummary> entries = libraryManager
                    .searchEntries(null, libId, null, null, 200, 0)
                    .stream()
                    .map(m -> articleReader.toSummary(m.libraryId(), m.context(), m.entry()))
                    .toList();
            json = prettyGson.toJson(entries);
        } else if (uri.startsWith("jabref://article/")) {
            String citationKey = uri.substring("jabref://article/".length());
            LibraryManager.EntryMatch match = libraryManager.findEntry(citationKey, null);
            json = prettyGson.toJson(articleReader.getDetails(match.libraryId(), match.context(), match.entry()));
        } else {
            throw new IllegalArgumentException("Unknown resource URI: " + uri);
        }

        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("uri", uri);
        contentItem.addProperty("mimeType", "application/json");
        contentItem.addProperty("text", json);

        JsonArray contents = new JsonArray();
        contents.add(contentItem);

        JsonObject result = new JsonObject();
        result.add("contents", contents);
        return result;
    }

    private JsonObject handlePromptsList() {
        JsonArray prompts = new JsonArray();

        prompts.add(promptDef(
                "review_article",
                "Perform a structured peer review of an article in JabRef and save the review.",
                List.of(promptArg("citation_key", "BibTeX citation key of the article to review", true))));

        prompts.add(promptDef(
                "summarize_article",
                "Produce a concise research summary of an article in JabRef.",
                List.of(promptArg("citation_key", "BibTeX citation key of the article to summarize", true))));

        prompts.add(promptDef(
                "compare_articles",
                "Compare methodology, findings, and limitations across multiple articles.",
                List.of(promptArg("citation_keys", "Comma-separated BibTeX citation keys", true))));

        JsonObject result = new JsonObject();
        result.add("prompts", prompts);
        return result;
    }

    private JsonObject handlePromptsGet(JsonObject params) {
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing prompt name");
        }
        String name = params.get("name").getAsString();
        JsonObject args = (params.has("arguments") && params.get("arguments").isJsonObject())
                ? params.getAsJsonObject("arguments")
                : new JsonObject();

        String promptText = switch (name) {
            case "review_article" -> {
                String key = args.has("citation_key") ? args.get("citation_key").getAsString() : "<citation_key>";
                yield "Please read and critically review article '" + key + "' from the JabRef library:\n"
                        + "1. Call `get_article` and `get_article_outline` for '" + key + "'.\n"
                        + "2. Read the PDF via `read_article_text` (paginating if needed) and check `get_article_annotations`.\n"
                        + "3. Write a structured review covering: Summary, Key Contributions, Methodology & Evidence, "
                        + "Strengths, Weaknesses/Limitations, and Overall Assessment.\n"
                        + "4. Persist the review to JabRef using `save_article_review` with `read_status: \"read\"`.";
            }
            case "summarize_article" -> {
                String key = args.has("citation_key") ? args.get("citation_key").getAsString() : "<citation_key>";
                yield "Summarize article '" + key + "' from the JabRef library using `get_article` and `read_article_text`. "
                        + "Provide: Research Question, Methods, Key Findings, Limitations, and Relevance.";
            }
            case "compare_articles" -> {
                String keys = args.has("citation_keys") ? args.get("citation_keys").getAsString() : "<key1,key2>";
                yield "Compare the following articles from the JabRef library: " + keys + ". "
                        + "Use `get_article` and `read_article_text` for each, then synthesize a structured comparison "
                        + "of research goals, methodology, datasets, results, and open gaps.";
            }
            default -> throw new IllegalArgumentException("Unknown prompt: " + name);
        };

        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", promptText);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", textContent);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject result = new JsonObject();
        result.add("messages", messages);
        return result;
    }

    private static JsonObject promptDef(String name, String description, List<JsonObject> arguments) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("description", description);
        JsonArray args = new JsonArray();
        arguments.forEach(args::add);
        obj.add("arguments", args);
        return obj;
    }

    private static JsonObject promptArg(String name, String description, boolean required) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("description", description);
        obj.addProperty("required", required);
        return obj;
    }

    private static JsonObject successResponse(@Nullable JsonElement id, JsonObject result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        if (id != null) {
            resp.add("id", id);
        }
        resp.add("result", result);
        return resp;
    }

    private static JsonObject errorResponse(@Nullable JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        if (id != null) {
            resp.add("id", id);
        } else {
            resp.add("id", com.google.gson.JsonNull.INSTANCE);
        }
        resp.add("error", error);
        return resp;
    }

    private static final class MethodNotFoundException extends RuntimeException {
        MethodNotFoundException(String message) {
            super(message);
        }
    }
}
