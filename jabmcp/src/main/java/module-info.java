/// Model Context Protocol (MCP) server for JabRef allowing AI assistants to cleanly read and review articles.
///
/// Entry point: [org.jabref.mcp.JabMcpCli].
module org.jabref.jabmcp {
    opens org.jabref.mcp to info.picocli, com.google.gson;
    opens org.jabref.mcp.protocol to com.google.gson;
    opens org.jabref.mcp.service to com.google.gson;

    requires org.jabref.jablib;

    requires com.google.gson;
    requires info.picocli;
    requires org.apache.pdfbox;

    requires org.slf4j;
    requires jul.to.slf4j;
    requires /*runtime*/ org.tinylog.api;
    requires /*runtime*/ org.tinylog.impl;
    requires /*runtime*/ org.apache.logging.log4j.to.slf4j;
    requires /*runtime*/ org.tinylog.api.slf4j;

    requires static org.jspecify;
    requires java.logging;
}
