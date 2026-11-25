plugins {
    id("org.jabref.gradle.module")
    id("application")
    id("org.jabref.gradle.feature.shadowjar")
}

group = "org.jabref.mcp"
version = providers.gradleProperty("projVersion")
    .orElse(providers.environmentVariable("VERSION"))
    .orElse("100.0.0")
    .get()

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.jabref.testsupport")
    requires("org.apache.pdfbox")
    runtimeOnly("org.tinylog.api")
    runtimeOnly("org.tinylog.impl")
    runtimeOnly("org.apache.logging.log4j.to.slf4j")
}

tasks.test {
    maxParallelForks = 1
}

application {
    mainClass = "org.jabref.mcp.JabMcpCli"

    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=com.sun.jna,org.apache.lucene.core",
        "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders",
        "-XX:+UseStringDeduplication"
    )
}

tasks.named<JavaExec>("run") {
    enableAssertions = true
    standardInput = System.`in`
    jvmArgs("--enable-native-access=com.sun.jna")
}
