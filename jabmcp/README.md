# JabRef MCP Server (`jabmcp`)

A Model Context Protocol (MCP) stdio server built directly on `jablib` so AI assistants can cleanly browse JabRef libraries, read linked PDFs, inspect annotations, and persist structured article reviews back to `.bib` files.

## Features

### Tools
- `list_libraries` — List loaded `.bib` libraries, file paths, and entry counts.
- `open_library` — Open an additional `.bib` file on disk.
- `search_articles` — Search entries using JabRef search syntax or keywords, with `read_status`, `has_review`, `limit`, and `offset` filters.
- `get_article` — Retrieve full bibliographic metadata, abstract, keywords, groups, read status, existing `review`/`comment`, and linked PDF paths.
- `read_article_text` — Extract normalized PDF text with `start_page`, `end_page`, and `max_chars` pagination.
- `get_article_outline` — Read the PDF table of contents / bookmarks and page count.
- `get_article_annotations` — Extract PDF highlights, sticky notes, underlines, and comments via JabRef's `EntryAnnotationImporter`.
- `save_article_review` — Write or append Markdown reviews (`review`) or notes (`comment`), update `read_status` (`read` / `skimmed`), `ranking` (`1`-`5`), and keywords, and atomically save the `.bib` file.

### Resources & Prompts
- Resources: `jabref://libraries`, `jabref://library/{id}`, `jabref://article/{citation_key}`
- Prompts: `review_article`, `summarize_article`, `compare_articles`

## Build & Test

```bash
./gradlew :jabmcp:test
./gradlew :jabmcp:shadowJar
```

## Run over stdio

```bash
./gradlew :jabmcp:run --quiet --args="/path/to/library.bib"
```

Or using the built fat JAR:

```bash
java --enable-native-access=com.sun.jna,org.apache.lucene.core \
  -jar jabmcp/build/libs/jabmcp-100.0.0-all.jar \
  /path/to/library.bib
```

If no `.bib` file is passed, `jabmcp` loads JabRef's bundled `Chocolate.bib` demo library.
