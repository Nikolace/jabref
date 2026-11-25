package org.jabref.mcp.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.KeywordList;
import org.jabref.model.entry.field.SpecialField;
import org.jabref.model.entry.field.SpecialFieldValue;
import org.jabref.model.entry.field.StandardField;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Writes structured AI reviews, comments, reading status, rankings, and keywords back to JabRef entries.
@NullMarked
public class ArticleReviewService {

    public record ReviewUpdateResult(
            String libraryId,
            String citationKey,
            String savedTo,
            @Nullable String readStatus,
            @Nullable String ranking,
            @Nullable String review,
            @Nullable String comment,
            List<String> keywords
    ) {
    }

    private final LibraryManager libraryManager;

    public ArticleReviewService(LibraryManager libraryManager) {
        this.libraryManager = libraryManager;
    }

    public ReviewUpdateResult saveReview(
            String citationKey,
            @Nullable String libraryId,
            @Nullable String review,
            @Nullable String comment,
            boolean append,
            @Nullable String readStatus,
            @Nullable Integer ranking,
            @Nullable List<String> addKeywords) throws IOException {
        LibraryManager.EntryMatch match = libraryManager.findEntry(citationKey, libraryId);
        BibEntry entry = match.entry();

        if (review != null && !review.isBlank()) {
            updateTextField(entry, StandardField.REVIEW, review.trim(), append);
        }
        if (comment != null && !comment.isBlank()) {
            updateTextField(entry, StandardField.COMMENT, comment.trim(), append);
        }
        if (readStatus != null && !readStatus.isBlank()) {
            applyReadStatus(entry, readStatus.trim());
        }
        if (ranking != null) {
            applyRanking(entry, ranking);
        }
        if (addKeywords != null && !addKeywords.isEmpty()) {
            Character sep = match.context().getKeywordSeparator(
                    libraryManager.getPreferences().getBibEntryPreferences().getKeywordSeparator());
            KeywordList keywordList = entry.getKeywords(sep);
            for (String kw : addKeywords) {
                if (kw != null && !kw.isBlank()) {
                    keywordList.add(kw.trim());
                }
            }
            entry.putKeywords(keywordList, sep);
        }

        Path savedPath = libraryManager.saveLibrary(match.libraryId());
        Character sep = match.context().getKeywordSeparator(
                libraryManager.getPreferences().getBibEntryPreferences().getKeywordSeparator());
        List<String> keywords = entry.getKeywords(sep).stream()
                                     .map(Object::toString)
                                     .toList();

        return new ReviewUpdateResult(
                match.libraryId(),
                entry.getCitationKey().orElse(citationKey),
                savedPath.toString(),
                entry.getField(SpecialField.READ_STATUS).orElse(null),
                entry.getField(SpecialField.RANKING).orElse(null),
                entry.getField(StandardField.REVIEW).orElse(null),
                entry.getField(StandardField.COMMENT).orElse(null),
                keywords);
    }

    private void updateTextField(BibEntry entry, StandardField field, String newText, boolean append) {
        if (append) {
            String existing = entry.getField(field).orElse("");
            if (!existing.isBlank()) {
                entry.setField(field, existing.trim() + "\n\n" + newText);
                return;
            }
        }
        entry.setField(field, newText);
    }

    private void applyReadStatus(BibEntry entry, String status) {
        switch (status.toLowerCase()) {
            case "read" -> entry.setField(SpecialField.READ_STATUS, SpecialFieldValue.READ.getFieldValue().orElse("read"));
            case "skimmed" -> entry.setField(SpecialField.READ_STATUS, SpecialFieldValue.SKIMMED.getFieldValue().orElse("skimmed"));
            case "clear", "unread" -> entry.clearField(SpecialField.READ_STATUS);
            default -> throw new IllegalArgumentException(
                    "Invalid read_status '" + status + "'. Expected one of: read, skimmed, clear");
        }
    }

    private void applyRanking(BibEntry entry, int ranking) {
        if (ranking < 0 || ranking > 5) {
            throw new IllegalArgumentException("Invalid ranking " + ranking + ". Expected 0 (clear) through 5.");
        }
        SpecialFieldValue value = SpecialFieldValue.getRating(ranking);
        if (value == SpecialFieldValue.CLEAR_RANK) {
            entry.clearField(SpecialField.RANKING);
        } else {
            value.getFieldValue().ifPresent(v -> entry.setField(SpecialField.RANKING, v));
        }
    }
}
