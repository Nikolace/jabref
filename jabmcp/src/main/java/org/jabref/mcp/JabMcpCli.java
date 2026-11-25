package org.jabref.mcp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.jabref.architecture.AllowedToUseStandardStreams;
import org.jabref.logic.preferences.JabRefCliPreferences;
import org.jabref.mcp.protocol.McpServer;
import org.jabref.mcp.protocol.McpToolHandler;
import org.jabref.mcp.service.ArticleReader;
import org.jabref.mcp.service.ArticleReviewService;
import org.jabref.mcp.service.LibraryManager;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import picocli.CommandLine;

/// CLI entry point for the JabRef Model Context Protocol (MCP) stdio server.
@AllowedToUseStandardStreams("MCP stdio server communicates via System.in and System.out")
@CommandLine.Command(
        name = "jabmcp",
        mixinStandardHelpOptions = true,
        description = "JabRef MCP Server - exposes JabRef libraries and PDFs over MCP stdio for AI reading and reviewing")
@NullMarked
public class JabMcpCli implements Callable<Integer> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JabMcpCli.class);

    @CommandLine.Parameters(
            arity = "0..*",
            paramLabel = "FILE",
            description = "JabRef .bib library files to load on startup")
    @Nullable
    List<Path> files;

    @CommandLine.Option(
            names = {"--demo"},
            description = "Load the bundled Chocolate.bib demo library when no files are provided")
    private boolean demo;

    private final PrintStream mcpOut;

    public JabMcpCli(PrintStream mcpOut) {
        this.mcpOut = mcpOut;
    }

    public static void main(String[] args) {
        // Preserve original stdout exclusively for JSON-RPC frames; redirect any stray stdout writes to stderr.
        PrintStream mcpOut = System.out;
        System.setOut(System.err);

        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        int exitCode = new CommandLine(new JabMcpCli(mcpOut)).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws IOException {
        JabRefCliPreferences preferences = JabRefCliPreferences.getInstance();
        LibraryManager libraryManager = new LibraryManager(preferences);

        List<Path> initialFiles = new ArrayList<>();
        if (files != null) {
            for (Path file : files) {
                if (Files.exists(file)) {
                    initialFiles.add(file);
                } else {
                    LOGGER.warn("Ignoring non-existent library path: {}", file);
                }
            }
        }
        if (initialFiles.isEmpty() && demo) {
            libraryManager.initialize(List.of());
        } else if (!initialFiles.isEmpty()) {
            libraryManager.initialize(initialFiles);
        } else {
            // Fall back to demo library if no files were passed so the server is immediately usable
            libraryManager.initialize(List.of());
        }

        ArticleReader articleReader = new ArticleReader(preferences);
        ArticleReviewService reviewService = new ArticleReviewService(libraryManager);
        McpToolHandler toolHandler = new McpToolHandler(libraryManager, articleReader, reviewService);
        McpServer server = new McpServer(libraryManager, articleReader, toolHandler);

        LOGGER.info("JabRef MCP server ready on stdio");
        server.run(System.in, mcpOut);
        return 0;
    }
}
