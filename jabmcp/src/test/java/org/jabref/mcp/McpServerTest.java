package org.jabref.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jabref.logic.importer.OpenDatabase;
import org.jabref.logic.importer.ParserResult;
import org.jabref.logic.preferences.JabRefCliPreferences;
import org.jabref.mcp.protocol.McpServer;
import org.jabref.mcp.protocol.McpToolHandler;
import org.jabref.mcp.service.ArticleReader;
import org.jabref.mcp.service.ArticleReviewService;
import org.jabref.mcp.service.LibraryManager;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.SpecialField;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.util.DummyFileUpdateMonitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {

    @TempDir
    Path tempDir;

    private Path bibFile;
    private Path pdfFile;
    private McpServer server;
    private JabRefCliPreferences preferences;

    @BeforeEach
    void setUp() throws IOException {
        pdfFile = tempDir.resolve("smith2025.pdf");
        createSamplePdf(pdfFile);

        bibFile = tempDir.resolve("research.bib");
        String bibContent = """
                @Article{Smith2025,
                  author   = {Jane Smith and John Doe},
                  journal  = {Journal of AI Research},
                  title    = {Clean Reading of Scientific Articles},
                  year     = {2025},
                  abstract = {We study how AI assistants read and review scientific PDFs.},
                  file     = {:smith2025.pdf:PDF},
                  keywords = {ai, reading},
                }
                """;
        Files.writeString(bibFile, bibContent, StandardCharsets.UTF_8);

        preferences = JabRefCliPreferences.getInstance();
        LibraryManager libraryManager = new LibraryManager(preferences);
        libraryManager.initialize(List.of(bibFile));
        ArticleReader articleReader = new ArticleReader(preferences);
        ArticleReviewService reviewService = new ArticleReviewService(libraryManager);
        McpToolHandler toolHandler = new McpToolHandler(libraryManager, articleReader, reviewService);
        server = new McpServer(libraryManager, articleReader, toolHandler);
    }

    @Test
    void initializeAndNotificationsFollowJsonRpcSpec() {
        String initResponse = server.handleRawMessage("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
                """);
        assertNotNull(initResponse);
        JsonObject initJson = JsonParser.parseString(initResponse).getAsJsonObject();
        JsonObject result = initJson.getAsJsonObject("result");
        assertEquals("2024-11-05", result.get("protocolVersion").getAsString());
        assertEquals("jabref-mcp", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));

        String notifResponse = server.handleRawMessage("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
        assertNull(notifResponse);
    }

    @Test
    void toolsListAndSearchAndGetArticleWork() {
        JsonObject listResp = callRaw("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """);
        JsonArray tools = listResp.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(8, tools.size());

        JsonObject searchResp = callTool(3, "search_articles", """
                {"query":"Smith"}
                """);
        String searchText = extractToolText(searchResp);
        JsonArray hits = JsonParser.parseString(searchText).getAsJsonArray();
        assertEquals(1, hits.size());
        assertEquals("Smith2025", hits.get(0).getAsJsonObject().get("citationKey").getAsString());
        assertTrue(hits.get(0).getAsJsonObject().get("hasPdf").getAsBoolean());

        JsonObject getResp = callTool(4, "get_article", """
                {"citation_key":"Smith2025"}
                """);
        JsonObject details = JsonParser.parseString(extractToolText(getResp)).getAsJsonObject();
        assertEquals("Clean Reading of Scientific Articles", details.get("title").getAsString());
        assertEquals("Journal of AI Research", details.get("venue").getAsString());
        assertEquals(1, details.getAsJsonArray("linkedFiles").size());
        assertTrue(details.getAsJsonArray("linkedFiles").get(0).getAsJsonObject().get("exists").getAsBoolean());
    }

    @Test
    void readArticleTextOutlineAndAnnotationsWork() {
        JsonObject outlineResp = callTool(5, "get_article_outline", """
                {"citation_key":"Smith2025"}
                """);
        JsonObject outline = JsonParser.parseString(extractToolText(outlineResp)).getAsJsonObject();
        assertEquals(2, outline.get("totalPages").getAsInt());
        assertEquals("1. Introduction", outline.getAsJsonArray("outline").get(0).getAsJsonObject().get("title").getAsString());

        JsonObject textResp = callTool(6, "read_article_text", """
                {"citation_key":"Smith2025","start_page":1,"end_page":2}
                """);
        JsonObject textJson = JsonParser.parseString(extractToolText(textResp)).getAsJsonObject();
        assertEquals(2, textJson.get("totalPages").getAsInt());
        assertFalse(textJson.get("truncated").getAsBoolean());
        String fullText = textJson.get("text").getAsString();
        assertTrue(fullText.contains("=== Page 1 of 2 ==="));
        assertTrue(fullText.contains("Introduction to clean reading."));
        assertTrue(fullText.contains("=== Page 2 of 2 ==="));
        assertTrue(fullText.contains("Methodology and evaluation."));

        JsonObject annResp = callTool(7, "get_article_annotations", """
                {"citation_key":"Smith2025"}
                """);
        JsonArray annotations = JsonParser.parseString(extractToolText(annResp)).getAsJsonArray();
        assertEquals(1, annotations.size());
        assertEquals("Important assumption to verify.", annotations.get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void saveArticleReviewPersistsToBibFile() throws IOException {
        JsonObject saveResp = callTool(8, "save_article_review", """
                {
                  "citation_key": "Smith2025",
                  "review": "## Summary\\nSolid methodology.",
                  "read_status": "read",
                  "ranking": 5,
                  "add_keywords": ["reviewed"]
                }
                """);
        assertFalse(saveResp.getAsJsonObject("result").get("isError").getAsBoolean());

        ParserResult reloaded = OpenDatabase.loadDatabase(
                bibFile,
                preferences.getImportFormatPreferences(),
                new DummyFileUpdateMonitor());
        BibEntry entry = reloaded.getDatabase().getEntriesByCitationKey("Smith2025").getFirst();
        assertEquals("## Summary\nSolid methodology.", entry.getField(StandardField.REVIEW).orElse(""));
        assertEquals("read", entry.getField(SpecialField.READ_STATUS).orElse(""));
        assertEquals("rank5", entry.getField(SpecialField.RANKING).orElse(""));
        assertTrue(entry.getField(StandardField.KEYWORDS).orElse("").contains("reviewed"));
    }

    @Test
    void resourcesAndPromptsWork() {
        JsonObject resList = callRaw("""
                {"jsonrpc":"2.0","id":9,"method":"resources/list"}
                """);
        assertTrue(resList.getAsJsonObject("result").getAsJsonArray("resources").size() >= 2);

        JsonObject resRead = callRaw("""
                {"jsonrpc":"2.0","id":10,"method":"resources/read","params":{"uri":"jabref://article/Smith2025"}}
                """);
        String articleJson = resRead.getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(articleJson.contains("Clean Reading of Scientific Articles"));

        JsonObject promptGet = callRaw("""
                {"jsonrpc":"2.0","id":11,"method":"prompts/get","params":{"name":"review_article","arguments":{"citation_key":"Smith2025"}}}
                """);
        String promptText = promptGet.getAsJsonObject("result").getAsJsonArray("messages").get(0).getAsJsonObject()
                                     .getAsJsonObject("content").get("text").getAsString();
        assertTrue(promptText.contains("Smith2025"));
        assertTrue(promptText.contains("save_article_review"));
    }

    private JsonObject callTool(int id, String name, String argsJson) {
        return callRaw("""
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(id, name, argsJson));
    }

    private JsonObject callRaw(String json) {
        String response = server.handleRawMessage(json);
        assertNotNull(response);
        return JsonParser.parseString(response).getAsJsonObject();
    }

    private static String extractToolText(JsonObject response) {
        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean(), "Unexpected tool error: " + result);
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static void createSamplePdf(Path target) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page1 = new PDPage();
            PDPage page2 = new PDPage();
            document.addPage(page1);
            document.addPage(page2);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs1 = new PDPageContentStream(document, page1)) {
                cs1.beginText();
                cs1.setFont(font, 12);
                cs1.newLineAtOffset(72, 700);
                cs1.showText("Introduction to clean reading.");
                cs1.endText();
            }
            try (PDPageContentStream cs2 = new PDPageContentStream(document, page2)) {
                cs2.beginText();
                cs2.setFont(font, 12);
                cs2.newLineAtOffset(72, 700);
                cs2.showText("Methodology and evaluation.");
                cs2.endText();
            }

            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(72, 680, 20, 20));
            note.setContents("Important assumption to verify.");
            page1.getAnnotations().add(note);

            PDDocumentOutline outline = new PDDocumentOutline();
            PDOutlineItem item1 = new PDOutlineItem();
            item1.setTitle("1. Introduction");
            PDPageFitWidthDestination dest1 = new PDPageFitWidthDestination();
            dest1.setPage(page1);
            item1.setDestination(dest1);
            outline.addLast(item1);
            document.getDocumentCatalog().setDocumentOutline(outline);

            document.save(target.toFile());
        }
    }
}
