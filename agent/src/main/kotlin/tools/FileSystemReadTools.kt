package org.example.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

object FileSystemReadTools {
    private fun resolveInside(agentDirectory: Path, relativePath: String): Path {
        val root = agentDirectory.toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Path escapes agent directory: $relativePath" }
        return resolved
    }

    @Serializable
    data class ExistsArgs(
        @property:LLMDescription("The relative path to check for existence inside the agent directory.")
        val path: String
    )

    @Serializable
    data class ReadFileArgs(
        @property:LLMDescription("The relative path to the text file to read inside the agent directory.")
        val path: String
    )

    @Serializable
    data class ListFolderArgs(
        @property:LLMDescription("The relative path to the directory to list inside the agent directory.")
        val path: String,
        @property:LLMDescription("Maximum recursive depth. 0 lists only direct contents.")
        val depth: Int = 0
    )

    class ExistsTool(
        private val agentDirectory: Path
    ) : SimpleTool<ExistsArgs>(
        typeToken<ExistsArgs>(),
        name = "file_system_exists",
        description = """
            Checks if a file or directory exists at the specified path inside the agent directory.
            Returns 'Exists' if the file or directory exists, and 'Does not exist' otherwise.
        """.trimIndent()
    ) {
        override suspend fun execute(args: ExistsArgs): String =
            try {
                if (Files.exists(resolveInside(agentDirectory, args.path))) {
                    "Exists"
                } else {
                    "Does not exist"
                }
            } catch (e: Exception) {
                "Failed to check if file exists: ${e.message}"
            }
    }

    class ReadFileTool(
        private val agentDirectory: Path
    ) : SimpleTool<ReadFileArgs>(
        typeToken<ReadFileArgs>(),
        name = "file_system_read_file",
        description = """
            Reads the complete contents of a text file at the specified path inside the agent directory.
            If the file does not exist or cannot be accessed, returns an appropriate error message.
        """.trimIndent()
    ) {
        override suspend fun execute(args: ReadFileArgs): String =
            try {
                withContext(Dispatchers.IO) {
                    Files.readString(resolveInside(agentDirectory, args.path))
                }
            } catch (e: Exception) {
                "Failed to read file: ${e.message}"
            }
    }

    class ListFolderTool(
        private val agentDirectory: Path
    ) : SimpleTool<ListFolderArgs>(
        typeToken<ListFolderArgs>(),
        name = "file_system_list_folder",
        description = """
            Lists the contents of a directory at the specified path inside the agent directory.
            Supports recursive listing with configurable depth.
        """.trimIndent()
    ) {
        private fun listRecursively(path: Path, currentDepth: Int, maxDepth: Int): List<Path> {
            if (currentDepth > maxDepth) {
                return emptyList()
            }

            val files = Files.list(path).use { stream -> stream.toList() }
            val result = mutableListOf<Path>()
            result.addAll(files)

            if (currentDepth < maxDepth) {
                for (file in files.filter { Files.isDirectory(it) }) {
                    result.addAll(listRecursively(file, currentDepth + 1, maxDepth))
                }
            }
            return result
        }

        override suspend fun execute(args: ListFolderArgs): String =
            try {
                val rootPath = resolveInside(agentDirectory, args.path)
                if (!Files.exists(rootPath)) {
                    return "Does not exist"
                }
                if (!Files.isDirectory(rootPath)) {
                    return "Not a directory"
                }

                val files = when {
                    args.depth < 0 -> emptyList()
                    args.depth == 0 -> withContext(Dispatchers.IO) {
                        Files.list(rootPath)
                    }.use { stream -> stream.toList() }
                    else -> listRecursively(rootPath, 0, args.depth)
                }
                buildString {
                    for (file in files) {
                        appendLine(represent(file))
                    }
                }
            } catch (e: Exception) {
                "Failed to list folder: ${e.message}"
            }

        private fun represent(path: Path): String {
            val attributes = listOfNotNull(
                if (Files.isDirectory(path)) "folder" else "file",
                if (runCatching { Files.isHidden(path) }.getOrDefault(false)) "hidden" else null,
                contentLabel(path)
            )
            return "${attributes.joinToString(", ", prefix = "[", postfix = "]")} ${path.toAbsolutePath()}"
        }

        private fun contentLabel(path: Path): String? {
            if (Files.isDirectory(path)) return null

            val contentType = runCatching { Files.probeContentType(path) }.getOrNull()
            if (contentType?.startsWith("text/") == true) return "text"

            val textExtensions = setOf(
                "java", "kt", "kts", "xml", "json", "yaml", "yml", "md", "txt", "properties", "gradle"
            )
            val extension = path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "")
            return if (extension in textExtensions) "text" else "binary"
        }
    }
}
