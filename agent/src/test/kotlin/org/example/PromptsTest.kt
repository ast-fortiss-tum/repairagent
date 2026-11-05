package org.example

import org.example.utils.SweDatasetManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptsTest {

    private val sharedRuleFragments = listOf(
        "Do not ask the user for help",
        "Keep changes focused on the repair",
        "You must not delete any test methods",
        "You must not modify source code under test",
        "provide the complete new file contents",
        "Always look at the output of any command you run"
    )

    @Test
    fun `commit-scoped prompt carries every repair rule`() {
        for (fragment in sharedRuleFragments) {
            assertTrue(
                DockerSweSystemPrompt.contains(fragment),
                "commit-scoped prompt is missing rule: $fragment"
            )
        }
    }

    @Test
    fun `commit-scoped prompt bounds the editable set and forbids new files`() {
        for (fragment in listOf(
            "only ones you have to fix",
            "only ones you may modify",
            "Do not create new files",
            "read-only"
        )) {
            assertTrue(
                DockerSweSystemPrompt.contains(fragment),
                "commit-scoped prompt must state: $fragment"
            )
        }
    }

    @Test
    fun `source diff section is emitted when a diff exists and omitted otherwise`() {
        assertEquals("", sourceDiffSection(""))
        assertEquals("", sourceDiffSection("   \n  "))

        val rendered = sourceDiffSection("--- a/A.java\n+++ b/A.java\n-old\n+new")
        assertTrue(rendered.startsWith(SourceDiffHeader), "diff section must start with the shared header")
        assertTrue(rendered.contains("+new"), "diff body must be included verbatim")
    }

    @Test
    fun `prompt lists only test files that exist after the revert`() {
        val ds = "dataset_swe/public/gson.json"
        assumeTrue(Files.exists(Path.of(ds)), "gson dataset not available")
        val manager = SweDatasetManager(ds)

        for (i in 0 until manager.getNumberOfCases()) {
            manager.setCase(i)
            val case = manager.getCase()
            val editable = case.testPatchChangedFilePaths()

            val created = case.testPatchFilePaths() - editable.toSet()
            for (path in created) {
                assertTrue(
                    path !in editable,
                    "case $i: created file leaked into the editable set: $path"
                )
            }
            assertTrue(editable.isNotEmpty(), "case $i: no editable test file to name in the prompt")
        }
    }

    @Test
    fun `commit-scoped cases expose usable test file paths for the prompt`() {
        val ds = "dataset_swe/public/alluxio.json"
        assumeTrue(Files.exists(Path.of(ds)), "alluxio dataset not available")
        val manager = SweDatasetManager(ds)
        var checked = 0

        for (i in 0 until manager.getNumberOfCases()) {
            manager.setCase(i)
            val case = manager.getCase()
            for (path in case.testPatchChangedFilePaths()) {

                assertTrue(!path.startsWith("/"), "case $i: path must be relative, got $path")
                assertTrue(path.contains("/"), "case $i: path must be slash-separated, got $path")
                assertTrue(
                    path.endsWith(".java") || path.endsWith(".kt"),
                    "case $i: path must name a source file, got $path"
                )
                checked++
            }
            assertTrue(case.patch.isNotBlank(), "case $i: no source diff available to supply")
        }
        assertTrue(checked > 0, "expected at least one test file path across the dataset")
    }
}
