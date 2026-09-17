package com.cryptochief.processing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source and example files are plain LF text.
 *
 * A NUL byte anywhere in a file makes git and grep classify it as binary: a repository-wide search
 * then reports that the file matched without printing the line, so the file drops out of every
 * audit that greps. Write such a byte as the `\u0000` escape instead.
 */
class SourceHygieneTest {

    private val projectRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("no directory holding settings.gradle.kts above ${File("").absolutePath}")

    private val files: List<File> = buildList {
        for (name in listOf("src", "examples")) {
            val dir = File(projectRoot, name)
            if (!dir.isDirectory) continue
            addAll(
                dir.walkTopDown()
                    .onEnter { it.name != "build" && !it.name.startsWith(".") }
                    .filter { it.isFile },
            )
        }
        addAll(projectRoot.listFiles().orEmpty().filter { it.isFile && it.extension in setOf("md", "kts") })
    }

    @Test
    fun `the scan reaches the sources`() {
        assertTrue(files.size > 50, "only ${files.size} files found under $projectRoot")
    }

    @Test
    fun `no file carries a NUL byte`() {
        assertEquals(emptyList<String>(), offenders(0), "write a NUL as the \\u0000 escape")
    }

    @Test
    fun `no file carries a CR`() {
        assertEquals(emptyList<String>(), offenders('\r'.code), "line endings are LF")
    }

    /** Files holding [byte], each with the 1-based lines it appears on. */
    private fun offenders(byte: Int): List<String> = files.mapNotNull { file ->
        val target = byte.toByte()
        val newline = '\n'.code.toByte()
        var line = 1
        val hits = mutableListOf<Int>()
        for (b in file.readBytes()) {
            if (b == target) hits += line
            if (b == newline) line++
        }
        if (hits.isEmpty()) null else "${file.relativeTo(projectRoot).invariantSeparatorsPath}: lines $hits"
    }
}
