package com.unyx.athena.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureBoundaryTest {
    @Test fun `ATHENA cannot import ATLAS personal code Android or UI`() {
        val root = File(System.getProperty("athena.repositoryRoot"), "athena")
        val sources = root.walkTopDown().filter {
            it.isFile && it.extension in setOf("kt", "java") && "/src/main/" in it.invariantSeparatorsPath
        }.toList()
        assertTrue("No ATHENA sources inspected", sources.isNotEmpty())
        val forbidden = Regex("\\b(android\\.|androidx\\.|com\\.unyx\\.atlas\\.|dagger\\.|javax\\.inject\\.)")
        sources.forEach { file ->
            assertTrue("Personal/platform dependency in ${file.name}", !forbidden.containsMatchIn(file.readText()))
        }
    }

    @Test fun `ATHENA build declarations only depend on ATHENA core`() {
        val root = File(System.getProperty("athena.repositoryRoot"), "athena")
        for (module in listOf("core", "runtime", "connector-weather", "connector-status")) {
            val file = File(root, "$module/build.gradle.kts")
            val projects = Regex("project\\(\"([^\"]+)\"\\)").findAll(file.readText()).map { it.groupValues[1] }.toList()
            assertTrue("Forbidden project dependency in $module: $projects", projects.all { it == ":athena:core" })
        }
    }
}
