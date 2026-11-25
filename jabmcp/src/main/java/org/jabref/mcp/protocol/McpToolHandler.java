package org.jabref.mcp.protocol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jabref.mcp.service.ArticleReader;
import org.jabref.mcp.service.ArticleReviewService;
import org.jabref.mcp.service.LibraryManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Defines MCP tools and dispatches `tools/call` invocations to JabRef services.
@NullMarked
public class McpToolHandler {
    private final LibraryManager libraryManager;
    private final ArticleReader articleReader;
    private final ArticleReviewService reviewService;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public McpToolHandler(
            LibraryManager libraryManager,
            ArticleReader articleReader,
            ArticleReviewService reviewService) {
        this.libraryManager = libraryManager;
        this.articleReader = articleReader;
        this.reviewService = reviewService;
    }

    public JsonArray listTools() {
        JsonArray tools = new JsonArray();

        tools.add(tool(
                "list_libraries",
                "List all currently loaded JabRef .bib libraries with their IDs, file paths, and entry counts.",
                schema(Map.of(), List.of())));

        tools.add(tool(
                "open_library",
                "Open and load an additional JabRef .bib library file from disk so its articles can be searched, read, and reviewed.",
                schema(
                        Map.of("path", prop("string", "Absolute or relative filesystem path to the .bib file.")),
                        List.of("path"))));

        tools.add(tool(
                "search_articles",
                "Search articles in loaded JabRef libraries using JabRef search syntax or keywords, with optional read-status and review filters.",
                schema(
                        Map.of(
                                "query", prop("string", "Search query (JabRef search syntax like 'author=Smith and year=2024' or plain keywords). Omit to list entries."),
                                "library_id", prop("string", "Optional library ID to restrict search."),
                                "read_status", prop("string", "Optional filter: 'read', 'skimmed', or 'unread'."),
                                "has_review", prop("boolean", "Optional filter: true for reviewed/commented entries, false for unreviewed."),
                                "limit", prop("integer", "Maximum results to return (default 25, max 200)."),
                                "offset", prop("integer", "Pagination offset (default 0).")),
                        List.of())));

        tools.add(tool(
                "get_article",
                "Get full bibliographic metadata, abstract, keywords, groups, read status, existing review/comment, and linked PDF file paths for an article.",
                schema(
                        Map.of(
                                "citation_key", prop("string", "The BibTeX citation key of the article."),
                                "library_id", prop("string", "Optional library ID if multiple libraries are loaded.")),
                        List.of("citation_key"))));

        tools.add(tool(
                "read_article_text",
                "Cleanly extract and read the full text of an article's linked PDF, with page range selection and character-limit pagination.",
                schema(
                        Map.of(
                                "citation_key", prop("string", "The BibTeX citation key of the article."),
                                "library_id", prop("string", "Optional library ID."),
                                "start_page", prop("integer", "1-based start page (default 1)."),
                                "end_page", prop("integer", "1-based inclusive end page (default last page)."),
                                "max_chars", prop("integer", "Maximum characters to return per call (default 40000, max 120000).")),
                        List.of("citation_key"))));

        tools.add(tool(
                "get_article_outline",
                "Read the PDF table of contents / section outline, page count, and PDF document metadata for an article.",
                schema(
                        Map.of(
                                "citation_key", prop("string", "The BibTeX citation key of the article."),
                                "library_id", prop("string", "Optional library ID.")),
                        List.of("citation_key"))));

        tools.add(tool(
                "get_article_annotations",
                "Extract all highlights, sticky notes, underlines, and comments from an article's linked PDF.",
                schema(
                        Map.of(
                                "citation_key", prop("string", "The BibTeX citation key of the article."),
                                "library_id", prop("string", "Optional library ID.")),
                        List.of("citation_key"))));

        tools.add(tool(
                "save_article_review",
                "Save or append an AI review or comment to a JabRef entry, optionally updating read_status, ranking (1-5), and keywords, and persist to the .bib file.",
                schema(
                        Map.of(
                                "citation_key", prop("string", "The BibTeX citation key of the article."),
                                "library_id", prop("string", "Optional library ID."),
                                "review", prop("string", "Markdown review text to store in the BibTeX 'review' field."),
                                "comment", prop("string", "Markdown notes to store in the BibTeX 'comment' field."),
                                "append", prop("boolean", "If true, append to existing review/comment instead of replacing (default false)."),
                                "read_status", prop("string", "Optional JabRef read status: 'read', 'skimmed', or 'clear'."),
                                "ranking", prop("integer", "Optional star rating 1-5 (or 0 to clear)."),
                                "add_keywords", arrayProp("string", "Optional keywords to add to the entry.")),
                        List.of("citation_key"))));

        return tools;
    }

    public JsonObject callTool(String name, @Nullable JsonObject args) {
        JsonObject safeArgs = args != null ? args : new JsonObject();
        try {
            Object result = switch (name) {
                case "list_libraries" -> libraryManager.listLibraries();
                case "open_library" -> handleOpenLibrary(safeArgs);
                case "search_articles" -> handleSearchArticles(safeArgs);
                case "get_article" -> handleGetArticle(safeArgs);
                case "read_article_text" -> handleReadArticleText(safeArgs);
                case "get_article_outline" -> handleGetArticleOutline(safeArgs);
                case "get_article_annotations" -> handleGetArticleAnnotations(safeArgs);
                case "save_article_review" -> handleSaveArticleReview(safeArgs);
                default -> throw new IllegalArgumentException("Unknown tool: " + name);
            };
            return textResult(gson.toJson(result), false);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return textResult("Error: " + message, true);
        }
    }

    private LibraryManager.LibrarySummary handleOpenLibrary(JsonObject args) throws IOException {
        String path = requireString(args, "path");
        LibraryManager.LoadedLibrary lib = libraryManager.openLibrary(Path.of(path));
        return new LibraryManager.LibrarySummary(
                lib.id(),
                lib.path().toString(),
                lib.context().getMode().getFormattedName(),
                lib.context().getDatabase().getEntryCount(),
                lib.demo());
    }

    private List<ArticleReader.ArticleSummary> handleSearchArticles(JsonObject args) {
        String query = optionalString(args, "query");
        String libraryId = optionalString(args, "library_id");
        String readStatus = optionalString(args, "read_status");
        Boolean hasReview = optionalBoolean(args, "has_review");
        Integer limit = optionalInt(args, "limit");
        Integer offset = optionalInt(args, "offset");

        List<LibraryManager.EntryMatch> matches = libraryManager.searchEntries(
                query,
                libraryId,
                readStatus,
                hasReview,
                limit != null ? limit : 25,
                offset != null ? offset : 0);

        return matches.stream()
                      .map(m -> articleReader.toSummary(m.libraryId(), m.context(), m.entry()))
                      .toList();
    }

    private ArticleReader.ArticleDetails handleGetArticle(JsonObject args) {
        String citationKey = requireString(args, "citation_key");
        String libraryId = optionalString(args, "library_id");
        LibraryManager.EntryMatch match = libraryManager.findEntry(citationKey, libraryId);
        return articleReader.getDetails(match.libraryId(), match.context(), match.entry());
    }

    private ArticleReader.ArticleTextResult handleReadArticleText(JsonObject args) throws IOException {
        String citationKey = requireString(args, "citation_key");
        String libraryId = optionalString(args, "library_id");
        Integer startPage = optionalInt(args, "start_page");
        Integer endPage = optionalInt(args, "end_page");
        Integer maxChars = optionalInt(args, "max_chars");
        LibraryManager.EntryMatch match = libraryManager.findEntry(citationKey, libraryId);
        return articleReader.readArticleText(match.context(), match.entry(), startPage, endPage, maxChars);
    }

    private ArticleReader.ArticleOutlineResult handleGetArticleOutline(JsonObject args) throws IOException {
        String citationKey = requireString(args, "citation_key");
        String libraryId = optionalString(args, "library_id");
        LibraryManager.EntryMatch match = libraryManager.findEntry(citationKey, libraryId);
        return articleReader.getOutline(match.context(), match.entry());
    }

    private List<ArticleReader.AnnotationInfo> handleGetArticleAnnotations(JsonObject args) {
        String citationKey = requireString(args, "citation_key");
        String libraryId = optionalString(args, "library_id");
        LibraryManager.EntryMatch match = libraryManager.findEntry(citationKey, libraryId);
        return articleReader.getAnnotations(match.context(), match.entry());
    }

    private ArticleReviewService.ReviewUpdateResult handleSaveArticleReview(JsonObject args) throws IOException {
        String citationKey = requireString(args, "citation_key");
        String libraryId = optionalString(args, "library_id");
        String review = optionalString(args, "review");
        String comment = optionalString(args, "comment");
        Boolean append = optionalBoolean(args, "append");
        String readStatus = optionalString(args, "read_status");
        Integer ranking = optionalInt(args, "ranking");
        List<String> addKeywords = optionalStringList(args, "add_keywords");

        return reviewService.saveReview(
                citationKey,
                libraryId,
                review,
                comment,
                Boolean.TRUE.equals(append),
                readStatus,
                ranking,
                addKeywords);
    }

    private JsonObject textResult(String text, boolean isError) {
        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("type", "text");
        contentItem.addProperty("text", text);

        JsonArray content = new JsonArray();
        content.add(contentItem);

        JsonObject response = new JsonObject();
        response.add("content", content);
        response.addProperty("isError", isError);
        return response;
    }

    private static JsonObject tool(String name, String description, JsonObject inputSchema) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("description", description);
        obj.add("inputSchema", inputSchema);
        return obj;
    }

    private static JsonObject schema(Map<String, JsonObject> properties, List<String> required) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "object");

        JsonObject props = new JsonObject();
        properties.forEach(props::add);
        obj.add("properties", props);

        JsonArray req = new JsonArray();
        required.forEach(req::add);
        obj.add("required", req);
        return obj;
    }

    private static JsonObject prop(String type, String description) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("description", description);
        return obj;
    }

    private static JsonObject arrayProp(String itemType, String description) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "array");
        obj.addProperty("description", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", itemType);
        obj.add("items", items);
        return obj;
    }

    private static String requireString(JsonObject args, String key) {
        String value = optionalString(args, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }

    private static @Nullable String optionalString(JsonObject args, String key) {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        return el.getAsString();
    }

    private static @Nullable Integer optionalInt(JsonObject args, String key) {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        return el.getAsInt();
    }

    private static @Nullable Boolean optionalBoolean(JsonObject args, String key) {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        return el.getAsBoolean();
    }

    private static @Nullable List<String> optionalStringList(JsonObject args, String key) {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonNull()) {
                list.add(item.getAsString());
            }
        }
        return list;
    }
}
