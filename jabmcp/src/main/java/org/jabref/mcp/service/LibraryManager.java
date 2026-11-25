package org.jabref.mcp.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jabref.logic.exporter.BibDatabaseWriter;
import org.jabref.logic.importer.OpenDatabase;
import org.jabref.logic.importer.ParserResult;
import org.jabref.logic.preferences.CliPreferences;
import org.jabref.logic.search.inmemory.InMemoryLibrarySearcher;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.SpecialField;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.search.SearchFlags;
import org.jabref.model.search.query.SearchQuery;
import org.jabref.model.util.DummyFileUpdateMonitor;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages loaded JabRef `.bib` libraries, search queries, entry lookup, and persistence.
@NullMarked
public class LibraryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(LibraryManager.class);

    public record LoadedLibrary(
            String id,
            Path path,
            BibDatabaseContext context,
            boolean demo
    ) {
    }

    public record EntryMatch(
            String libraryId,
            BibDatabaseContext context,
            BibEntry entry
    ) {
    }

    public record LibrarySummary(
            String id,
            String path,
            String mode,
            int entryCount,
            boolean demo
    ) {
    }

    private final CliPreferences preferences;
    private final Map<String, LoadedLibrary> libraries = new LinkedHashMap<>();

    public LibraryManager(CliPreferences preferences) {
        this.preferences = preferences;
    }

    /// Loads the given `.bib` files; if empty, loads the bundled `Chocolate.bib` demo library.
    public void initialize(List<Path> initialFiles) throws IOException {
        if (initialFiles.isEmpty()) {
            loadDemoLibrary();
            return;
        }
        for (Path file : initialFiles) {
            openLibrary(file);
        }
    }

    public synchronized LoadedLibrary openLibrary(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new IOException("Library file does not exist: " + normalized);
        }

        for (LoadedLibrary existing : libraries.values()) {
            if (existing.path().equals(normalized)) {
                return existing;
            }
        }

        ParserResult result = OpenDatabase.loadDatabase(
                normalized,
                preferences.getImportFormatPreferences(),
                new DummyFileUpdateMonitor());

        if (result.hasWarnings()) {
            LOGGER.warn("Warnings while loading {}: {}", normalized, result.warnings());
        }

        BibDatabaseContext context = result.getDatabaseContext();
        String id = allocateId(FileUtil.getBaseName(normalized));
        LoadedLibrary loaded = new LoadedLibrary(id, normalized, context, false);
        libraries.put(id, loaded);
        LOGGER.info("Loaded library '{}' from {} ({} entries)", id, normalized, context.getDatabase().getEntryCount());
        return loaded;
    }

    private synchronized void loadDemoLibrary() throws IOException {
        try (InputStream demo = BibDatabase.class.getResourceAsStream("/Chocolate.bib")) {
            if (demo == null) {
                throw new IOException("Demo library Chocolate.bib not found on classpath");
            }
            Path tempBib = Files.createTempFile("jabmcp-demo-", ".bib");
            tempBib.toFile().deleteOnExit();
            Files.copy(demo, tempBib, StandardCopyOption.REPLACE_EXISTING);

            ParserResult result = OpenDatabase.loadDatabase(
                    tempBib,
                    preferences.getImportFormatPreferences(),
                    new DummyFileUpdateMonitor());
            LoadedLibrary loaded = new LoadedLibrary("demo", tempBib, result.getDatabaseContext(), true);
            libraries.put("demo", loaded);
            LOGGER.info("Loaded demo library 'demo' ({} entries)", result.getDatabaseContext().getDatabase().getEntryCount());
        }
    }

    private String allocateId(String baseName) {
        String sanitized = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.isBlank()) {
            sanitized = "library";
        }
        String candidate = sanitized;
        int suffix = 2;
        while (libraries.containsKey(candidate)) {
            candidate = sanitized + "-" + suffix++;
        }
        return candidate;
    }

    public synchronized List<LibrarySummary> listLibraries() {
        List<LibrarySummary> result = new ArrayList<>(libraries.size());
        for (LoadedLibrary lib : libraries.values()) {
            result.add(new LibrarySummary(
                    lib.id(),
                    lib.path().toString(),
                    lib.context().getMode().getFormattedName(),
                    lib.context().getDatabase().getEntryCount(),
                    lib.demo()));
        }
        return result;
    }

    public synchronized Optional<LoadedLibrary> getLibrary(String libraryId) {
        return Optional.ofNullable(libraries.get(libraryId));
    }

    public synchronized EntryMatch findEntry(String citationKey, @Nullable String libraryId) {
        if (libraryId != null && !libraryId.isBlank()) {
            LoadedLibrary lib = libraries.get(libraryId);
            if (lib == null) {
                throw new IllegalArgumentException("Unknown library id: " + libraryId);
            }
            return findInLibrary(lib, citationKey)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Entry with citation key '" + citationKey + "' not found in library '" + libraryId + "'"));
        }

        for (LoadedLibrary lib : libraries.values()) {
            Optional<EntryMatch> match = findInLibrary(lib, citationKey);
            if (match.isPresent()) {
                return match.get();
            }
        }
        throw new IllegalArgumentException("Entry with citation key '" + citationKey + "' not found in any loaded library");
    }

    private Optional<EntryMatch> findInLibrary(LoadedLibrary lib, String citationKey) {
        List<BibEntry> matches = lib.context().getDatabase().getEntriesByCitationKey(citationKey);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EntryMatch(lib.id(), lib.context(), matches.getFirst()));
    }

    public synchronized List<EntryMatch> searchEntries(
            @Nullable String query,
            @Nullable String libraryId,
            @Nullable String readStatusFilter,
            @Nullable Boolean hasReviewFilter,
            int limit,
            int offset) {
        List<LoadedLibrary> targets = resolveTargets(libraryId);
        List<EntryMatch> allMatches = new ArrayList<>();

        for (LoadedLibrary lib : targets) {
            List<BibEntry> candidates;
            if (query == null || query.isBlank()) {
                candidates = lib.context().getDatabase().getEntries();
            } else {
                InMemoryLibrarySearcher searcher = new InMemoryLibrarySearcher(
                        lib.context(),
                        preferences.getBibEntryPreferences());
                SearchQuery searchQuery = new SearchQuery(query, java.util.EnumSet.noneOf(SearchFlags.class));
                if (searchQuery.isValid()) {
                    candidates = searcher.getMatches(searchQuery);
                } else {
                    String needle = query.toLowerCase();
                    candidates = lib.context().getDatabase().getEntries().stream()
                                    .filter(entry -> matchesPlainSubstring(entry, needle))
                                    .toList();
                }
            }

            for (BibEntry entry : candidates) {
                if (!matchesReviewFilters(entry, readStatusFilter, hasReviewFilter)) {
                    continue;
                }
                allMatches.add(new EntryMatch(lib.id(), lib.context(), entry));
            }
        }

        int safeOffset = Math.max(0, offset);
        if (safeOffset >= allMatches.size()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int toIndex = Math.min(allMatches.size(), safeOffset + safeLimit);
        return allMatches.subList(safeOffset, toIndex);
    }

    private List<LoadedLibrary> resolveTargets(@Nullable String libraryId) {
        if (libraryId != null && !libraryId.isBlank()) {
            LoadedLibrary lib = libraries.get(libraryId);
            if (lib == null) {
                throw new IllegalArgumentException("Unknown library id: " + libraryId);
            }
            return List.of(lib);
        }
        return new ArrayList<>(libraries.values());
    }

    private boolean matchesPlainSubstring(BibEntry entry, String lowerNeedle) {
        return entry.getCitationKey().map(k -> k.toLowerCase().contains(lowerNeedle)).orElse(false)
                || entry.getField(StandardField.TITLE).map(t -> t.toLowerCase().contains(lowerNeedle)).orElse(false)
                || entry.getField(StandardField.AUTHOR).map(a -> a.toLowerCase().contains(lowerNeedle)).orElse(false)
                || entry.getField(StandardField.ABSTRACT).map(a -> a.toLowerCase().contains(lowerNeedle)).orElse(false)
                || entry.getField(StandardField.KEYWORDS).map(k -> k.toLowerCase().contains(lowerNeedle)).orElse(false);
    }

    private boolean matchesReviewFilters(
            BibEntry entry,
            @Nullable String readStatusFilter,
            @Nullable Boolean hasReviewFilter) {
        if (readStatusFilter != null && !readStatusFilter.isBlank()) {
            String current = entry.getField(SpecialField.READ_STATUS).orElse("unread");
            if (!current.equalsIgnoreCase(readStatusFilter.trim())) {
                return false;
            }
        }
        if (hasReviewFilter != null) {
            boolean hasReview = entry.hasField(StandardField.REVIEW) || entry.hasField(StandardField.COMMENT);
            if (hasReview != hasReviewFilter) {
                return false;
            }
        }
        return true;
    }

    public synchronized Path saveLibrary(String libraryId) throws IOException {
        LoadedLibrary lib = libraries.get(libraryId);
        if (lib == null) {
            throw new IllegalArgumentException("Unknown library id: " + libraryId);
        }
        Charset encoding = lib.context().getMetaData().getEncoding().orElse(StandardCharsets.UTF_8);
        Path targetPath = lib.path();
        Path tempFile = Files.createTempFile(targetPath.getParent(), ".jabmcp-save-", ".bib");
        try {
            try (Writer writer = Files.newBufferedWriter(tempFile, encoding)) {
                BibDatabaseWriter databaseWriter = new BibDatabaseWriter(writer, lib.context(), preferences);
                databaseWriter.writeDatabase(lib.context());
            }
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
        LOGGER.info("Saved library '{}' to {}", libraryId, targetPath);
        return targetPath;
    }

    public CliPreferences getPreferences() {
        return preferences;
    }
}
