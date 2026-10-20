package com.hotlayout.demo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin example of the README is a verbatim region of a file the demo compiles. */
class ReadmeExampleTest {
    @Test fun theReadmeExampleIsTheCompiledFile() {
        val source = File("src/main/kotlin/com/hotlayout/demo/ReadmeExample.kt").readText()
        val example = source.substringAfter("// region example\n").substringBefore("// endregion").trim()
        val readme = File("../README.md").readText()
        assertTrue("README.md must contain the region of ReadmeExample.kt verbatim", readme.contains(example))
    }
}
