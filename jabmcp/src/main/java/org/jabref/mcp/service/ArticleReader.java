package org.jabref.mcp.service;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jabref.logic.pdf.EntryAnnotationImporter;
import org.jabref.logic.pdf.InterruptiblePDFTextStripper;
import org.jabref.logic.preferences.CliPreferences;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.entry.field.SpecialField;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.pdf.FileAnnotation;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Reads article metadata, normalized PDF full text, PDF outlines, and PDF annotations.
@NullMarked
public class ArticleReader {
    private static final int DEFAULT_MAX_CHARS = 40_000;
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+\\n");

    public record LinkedFileInfo(
            String description,
            String link,
            String fileType,
            @Nullable String resolvedPath,
            boolean exists
    ) {
    }

    public record ArticleSummary(
            String libraryId,
            String citationKey,
            String entryType,
            @Nullable String title,
            @Nullable String authors,
            @Nullable String year,
            @Nullable String venue,
            @Nullable String doi,
            @Nullable String readStatus,
            @Nullable String ranking,
            boolean hasPdf,
            boolean hasReview,
            List<String> keywords,
            List<String> groups
    ) {
    }

    public record ArticleDetails(
            String libraryId,
            String citationKey,
            String entryType,
            @Nullable String title,
            @Nullable String authors,
            @Nullable String year,
            @Nullable String venue,
            @Nullable String volume,
            @Nullable String number,
            @Nullable String pages,
            @Nullable String doi,
            @Nullable String url,
            @Nullable String abstractText,
            @Nullable String readStatus,
            @Nullable String priority,
            @Nullable String ranking,
            @Nullable String comment,
            @Nullable String review,
            List<String> keywords,
            List<String> groups,
            List<LinkedFileInfo> linkedFiles
    ) {
    }

    public record ArticleTextResult(
            String citationKey,
            String pdfPath,
            int totalPages,
            int startPage,
            int endPage,
            boolean truncated,
            @Nullable Integer nextStartPage,
            String text
    ) {
    }

    public record OutlineEntry(
            String title,
            @Nullable Integer page,
            List<OutlineEntry> children
    ) {
    }

    public record ArticleOutlineResult(
            String citationKey,
            String pdfPath,
            int totalPages,
            @Nullable String pdfTitle,
            @Nullable String pdfAuthor,
            @Nullable String pdfSubject,
            List<OutlineEntry> outline
    ) {
    }

    public record AnnotationInfo(
            String pdfPath,
            int page,
            String type,
            @Nullable String author,
            @Nullable String modifiedDate,
            String content,
            @Nullable String linkedNote
    ) {
    }

    private final CliPreferences preferences;

    public ArticleReader(CliPreferences preferences) {
        this.preferences = preferences;
    }

    public ArticleSummary toSummary(String libraryId, BibDatabaseContext context, BibEntry entry) {
        Character sep = context.getKeywordSeparator(preferences.getBibEntryPreferences().getKeywordSeparator());
        List<String> keywords = entry.getKeywords(sep).stream()
                                     .map(Object::toString)
                                     .toList();
        List<String> groups = entry.getField(StandardField.GROUPS)
                                   .map(g -> List.of(g.split("\\s*,\\s*")))
                                   .orElse(List.of());
        boolean hasPdf = entry.getFiles().stream()
                              .filter(f -> !f.isOnlineLink())
                              .anyMatch(f -> f.findIn(context, preferences.getFilePreferences()).filter(Files::exists).isPresent());
        boolean hasReview = entry.hasField(StandardField.REVIEW) || entry.hasField(StandardField.COMMENT);

        return new ArticleSummary(
                libraryId,
                entry.getCitationKey().orElse(""),
                entry.getType().getDisplayName(),
                entry.getField(StandardField.TITLE).orElse(null),
                entry.getField(StandardField.AUTHOR).orElse(null),
                entry.getField(StandardField.YEAR).or(() -> entry.getField(StandardField.DATE)).orElse(null),
                resolveVenue(entry),
                entry.getField(StandardField.DOI).orElse(null),
                entry.getField(SpecialField.READ_STATUS).orElse(null),
                entry.getField(SpecialField.RANKING).orElse(null),
                hasPdf,
                hasReview,
                keywords,
                groups);
    }

    public ArticleDetails getDetails(String libraryId, BibDatabaseContext context, BibEntry entry) {
        Character sep = context.getKeywordSeparator(preferences.getBibEntryPreferences().getKeywordSeparator());
        List<String> keywords = entry.getKeywords(sep).stream()
                                     .map(Object::toString)
                                     .toList();
        List<String> groups = entry.getField(StandardField.GROUPS)
                                   .map(g -> List.of(g.split("\\s*,\\s*")))
                                   .orElse(List.of());

        List<LinkedFileInfo> files = new ArrayList<>();
        for (LinkedFile file : entry.getFiles()) {
            Optional<Path> resolved = file.isOnlineLink()
                    ? Optional.empty()
                    : file.findIn(context, preferences.getFilePreferences());
            files.add(new LinkedFileInfo(
                    file.getDescription(),
                    file.getLink(),
                    file.getFileType(),
                    resolved.map(Path::toString).orElse(null),
                    resolved.map(Files::exists).orElse(false)));
        }

        return new ArticleDetails(
                libraryId,
                entry.getCitationKey().orElse(""),
                entry.getType().getDisplayName(),
                entry.getField(StandardField.TITLE).orElse(null),
                entry.getField(StandardField.AUTHOR).orElse(null),
                entry.getField(StandardField.YEAR).or(() -> entry.getField(StandardField.DATE)).orElse(null),
                resolveVenue(entry),
                entry.getField(StandardField.VOLUME).orElse(null),
                entry.getField(StandardField.NUMBER).orElse(null),
                entry.getField(StandardField.PAGES).orElse(null),
                entry.getField(StandardField.DOI).orElse(null),
                entry.getField(StandardField.URL).orElse(null),
                entry.getField(StandardField.ABSTRACT).orElse(null),
                entry.getField(SpecialField.READ_STATUS).orElse(null),
                entry.getField(SpecialField.PRIORITY).orElse(null),
                entry.getField(SpecialField.RANKING).orElse(null),
                entry.getField(StandardField.COMMENT).orElse(null),
                entry.getField(StandardField.REVIEW).orElse(null),
                keywords,
                groups,
                files);
    }

    public ArticleTextResult readArticleText(
            BibDatabaseContext context,
            BibEntry entry,
            @Nullable Integer requestedStartPage,
            @Nullable Integer requestedEndPage,
            @Nullable Integer requestedMaxChars) throws IOException {
        Path pdfPath = resolvePrimaryPdf(context, entry);
        int maxChars = requestedMaxChars != null && requestedMaxChars > 0
                ? Math.min(requestedMaxChars, 120_000)
                : DEFAULT_MAX_CHARS;

        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            int totalPages = document.getNumberOfPages();
            if (totalPages == 0) {
                return new ArticleTextResult(
                        entry.getCitationKey().orElse(""),
                        pdfPath.toString(),
                        0,
                        0,
                        0,
                        false,
                        null,
                        "");
            }

            int startPage = requestedStartPage != null ? Math.max(1, requestedStartPage) : 1;
            int endPage = requestedEndPage != null ? Math.min(totalPages, requestedEndPage) : totalPages;
            if (startPage > totalPages) {
                throw new IllegalArgumentException(
                        "start_page (" + startPage + ") exceeds total page count (" + totalPages + ")");
            }
            if (endPage < startPage) {
                throw new IllegalArgumentException(
                        "end_page (" + endPage + ") must be >= start_page (" + startPage + ")");
            }

            InterruptiblePDFTextStripper stripper = new InterruptiblePDFTextStripper();
            StringBuilder buffer = new StringBuilder();
            boolean truncated = false;
            int lastIncludedPage = startPage - 1;

            for (int page = startPage; page <= endPage; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                StringWriter writer = new StringWriter();
                stripper.writeText(document, writer);

                String pageText = normalizePageText(writer.toString());
                String section = "=== Page " + page + " of " + totalPages + " ===\n" + pageText + "\n\n";

                if (buffer.length() > 0 && buffer.length() + section.length() > maxChars) {
                    truncated = true;
                    break;
                }
                if (buffer.isEmpty() && section.length() > maxChars) {
                    buffer.append(section, 0, maxChars);
                    lastIncludedPage = page;
                    truncated = true;
                    break;
                }
                buffer.append(section);
                lastIncludedPage = page;
            }

            Integer nextStartPage = (truncated && lastIncludedPage < totalPages) ? lastIncludedPage + 1 : null;
            return new ArticleTextResult(
                    entry.getCitationKey().orElse(""),
                    pdfPath.toString(),
                    totalPages,
                    startPage,
                    Math.max(startPage, lastIncludedPage),
                    truncated,
                    nextStartPage,
                    buffer.toString().trim());
        }
    }

    public ArticleOutlineResult getOutline(BibDatabaseContext context, BibEntry entry) throws IOException {
        Path pdfPath = resolvePrimaryPdf(context, entry);
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            int totalPages = document.getNumberOfPages();
            PDDocumentInformation info = document.getDocumentInformation();
            PDDocumentOutline docOutline = document.getDocumentCatalog().getDocumentOutline();

            List<OutlineEntry> items = docOutline != null
                    ? extractOutlineItems(document, docOutline.getFirstChild())
                    : List.of();

            return new ArticleOutlineResult(
                    entry.getCitationKey().orElse(""),
                    pdfPath.toString(),
                    totalPages,
                    info != null ? blankToNull(info.getTitle()) : null,
                    info != null ? blankToNull(info.getAuthor()) : null,
                    info != null ? blankToNull(info.getSubject()) : null,
                    items);
        }
    }

    public List<AnnotationInfo> getAnnotations(BibDatabaseContext context, BibEntry entry) {
        EntryAnnotationImporter importer = new EntryAnnotationImporter(entry);
        Map<Path, List<FileAnnotation>> byFile = importer.importAnnotationsFromFiles(
                context,
                preferences.getFilePreferences());

        List<AnnotationInfo> result = new ArrayList<>();
        for (Map.Entry<Path, List<FileAnnotation>> fileEntry : byFile.entrySet()) {
            String pathStr = fileEntry.getKey().toString();
            for (FileAnnotation ann : fileEntry.getValue()) {
                String linkedNote = ann.hasLinkedAnnotation() ? ann.getLinkedFileAnnotation().getContent() : null;
                result.add(new AnnotationInfo(
                        pathStr,
                        ann.getPage(),
                        ann.getAnnotationType().name(),
                        ann.getAuthor(),
                        ann.getTimeModified() != null ? ann.getTimeModified().toString() : null,
                        ann.getContent() != null ? ann.getContent() : "",
                        linkedNote));
            }
        }
        return result;
    }

    private Path resolvePrimaryPdf(BibDatabaseContext context, BibEntry entry) throws IOException {
        for (LinkedFile linkedFile : entry.getFiles()) {
            if (linkedFile.isOnlineLink()) {
                continue;
            }
            if (!"pdf".equalsIgnoreCase(linkedFile.getFileType())
                    && !linkedFile.getLink().toLowerCase().endsWith(".pdf")) {
                continue;
            }
            Optional<Path> resolved = linkedFile.findIn(context, preferences.getFilePreferences());
            if (resolved.isPresent() && Files.exists(resolved.get())) {
                return resolved.get();
            }
        }
        String key = entry.getCitationKey().orElse("(unknown)");
        throw new IOException("No existing local PDF file linked to entry '" + key + "'");
    }

    private List<OutlineEntry> extractOutlineItems(PDDocument document, @Nullable PDOutlineItem item) throws IOException {
        List<OutlineEntry> items = new ArrayList<>();
        PDOutlineItem current = item;
        while (current != null) {
            Integer pageNumber = null;
            PDPage destinationPage = current.findDestinationPage(document);
            if (destinationPage != null) {
                int idx = document.getPages().indexOf(destinationPage);
                if (idx >= 0) {
                    pageNumber = idx + 1;
                }
            }
            List<OutlineEntry> children = extractOutlineItems(document, current.getFirstChild());
            items.add(new OutlineEntry(current.getTitle(), pageNumber, children));
            current = current.getNextSibling();
        }
        return items;
    }

    private static String normalizePageText(String raw) {
        String normalized = raw.replace("\r\n", "\n").replace("\r", "\n");
        normalized = TRAILING_SPACES.matcher(normalized).replaceAll("\n");
        normalized = EXCESS_BLANK_LINES.matcher(normalized).replaceAll("\n\n");
        return normalized.trim();
    }

    private static @Nullable String resolveVenue(BibEntry entry) {
        return entry.getField(StandardField.JOURNAL)
                    .or(() -> entry.getField(StandardField.BOOKTITLE))
                    .or(() -> entry.getField(StandardField.PUBLISHER))
                    .orElse(null);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
