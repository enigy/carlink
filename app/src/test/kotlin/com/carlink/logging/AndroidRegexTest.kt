package com.carlink.logging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regex patterns that desktop Java accepts and Android rejects. Unit tests run on the JVM's regex
 * engine; the app runs on ICU's, which reads a character class opening with `[:` as a POSIX property
 * (`[:alpha:]`) and throws `PatternSyntaxException`. v16's log redactor did exactly that (`[:-]`) and
 * crashed the app as soon as drive logging was on, with every JVM test green. This scans the source
 * so the next one fails here instead of in the truck.
 */
class AndroidRegexTest {
    private val regexCall = Regex("""(Regex\(|toRegex\(|Pattern\.compile\()""")

    @Test
    fun noPatternOpensACharacterClassWithAColon() {
        val roots = listOf(File("src/main/kotlin"), File("../bridge/src/main/kotlin")).filter { it.isDirectory }
        assertTrue("source roots not found from ${File(".").absolutePath}", roots.isNotEmpty())
        val offenders =
            roots
                .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { i, line ->
                        if (regexCall.containsMatchIn(line) && "[:" in line) "${file.path}:${i + 1}: ${line.trim()}" else null
                    }
                }
        assertTrue("Android's regex engine rejects a class opening with [: — write [-:] or escape it:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun theRedactorStillMatchesDashSeparatedAddresses() {
        assertTrue(LogRedactor.line("peer 00-01-02-03-0a-0b").endsWith("..0a:0b"))
    }
}
