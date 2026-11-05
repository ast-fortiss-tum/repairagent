package org.example.tools

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class ExecutionResult(
    val exitCode: Int,
    val executionMessage: String,
)

class CommandExecutor(
    private val timeoutSeconds: Long = 300,
    val javaHome: String = System.getenv("JAVA_HOME")!!
) {

    fun execute(
        workingDirectory: String,
        command: String,
        stdin: String? = null,
        timeoutSecondsOverride: Long? = null,
        progressSink: ((String) -> Unit)? = null
    ): ExecutionResult {
        val fullPath = Path.of(workingDirectory)
        return executeWithTimeout(
            command = command,
            workingDir = fullPath,
            stdin = stdin,
            timeoutSeconds = timeoutSecondsOverride ?: timeoutSeconds,
            progressSink = progressSink
        )
    }

    private fun executeWithTimeout(
        command: String,
        workingDir: Path,
        stdin: String?,
        timeoutSeconds: Long,
        progressSink: ((String) -> Unit)?
    ): ExecutionResult {
        val processBuilder = ProcessBuilder()
            .command("sh", "-c", command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
        val env = processBuilder.environment()
        env["JAVA_HOME"] = javaHome
        val process = processBuilder.start()

        val output = CompletableFuture.supplyAsync {
            val collected = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    collected.appendLine(line)
                    progressSink?.invoke(line)
                }
            }
            collected.toString()
        }
        val input = CompletableFuture.runAsync {
            process.outputStream.bufferedWriter().use { writer ->
                if (stdin != null) {
                    writer.write(stdin)
                }
            }
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            val partialOutput = runCatching { output.get(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault("")
            return ExecutionResult(
                exitCode = 124,
                executionMessage = buildString {
                    append(partialOutput)
                    if (partialOutput.isNotBlank() && !partialOutput.endsWith(System.lineSeparator())) {
                        appendLine()
                    }
                    appendLine("Command timed out after $timeoutSeconds seconds.")
                }
            )
        }
        runCatching { input.get(timeoutSeconds, TimeUnit.SECONDS) }
        return ExecutionResult(process.exitValue(), output.get())
    }
}
