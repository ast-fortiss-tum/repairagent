package org.example.utils

import org.example.config.Config
import org.example.tools.CommandExecutor
import org.example.tools.ExecutionResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import kotlin.io.path.absolute

data class DockerChangedFile(
    val path: String,
    val status: String,
    val content: String?,
    val error: String? = null
)

private data class TestFileHint(
    val reference: String,
    val module: String?,
    val directPath: String?,
    val packagePath: String?,
    val simpleName: String?
)

class DockerOperationException(message: String, val output: String) : IllegalStateException(message)

private const val EXPECTED_SOLUTION_DIR = "/opt/expected-solution"

private const val XVFB_INLINE_PREFIX =
    "Xvfb :99 -screen 0 1024x768x24 -nolock >/dev/null 2>&1 & sleep 3 && "

private const val PINNED_SDKMAN_MAVEN = "3.9.16"

private val mavenInvocation = Regex("""\bmvn\b""")
private val mavenProjectSelector = Regex("""(-pl|--projects)\s+([^\s"]+)""")

private val mavenInstallGoal = Regex("""(^|\s)install(\s|$)""")

private val mavenUpstreamDependentGoals = listOf(
    "compile", "test-compile", "test", "package", "verify", "integration-test"
)

private val mavenAlsoMakeFlag = Regex("""(^|\s)(-am|--also-make)(\s|"|$)""")

private val mavenProfileFlag = Regex("""-P[^\s"]+""")
private val mavenSystemPropertyFlag = Regex("""-D[^\s"]+""")

class DockerProjectManager(
    private val agentParent: Path,
    private val buildScript: Path,
    private val runScript: Path,
    config: Config,
    private val logger: Logger
) {
    private val executor = CommandExecutor(
        timeoutSeconds = config.app.timeoutSeconds,
        javaHome = config.secrets.javaHome
    )
    private val githubToken: String? = config.secrets.githubToken
    private val buildTimeoutSeconds: Long = config.app.dockerBuildTimeoutSeconds
    private val buildMaxAttempts: Int = config.app.dockerBuildMaxAttempts.coerceAtLeast(1)
    private var currentContainerId: String? = null
    private var currentContextDir: Path? = null

    fun prepareImage(repairCase: SweRepairCase): String {
        val imageTag = imageTag(repairCase)
        val contextDir = contextDir(repairCase)
        currentContextDir = contextDir

        Files.createDirectories(contextDir)
        val dockerfileContent = repairCase.setupScripts.dockerFileContent
            .let(::rewriteDockerfileForAuth)
            .let(::pinSdkmanMaven)
            .let(::collapseRunHeredocs)
            .let(::collapseRunPrintfWrites)
            .let(::ensureMavenLocalRepoDir)
            .let(::ensureXvfbForGuiTests)
            .let(::neutralizeDiagnosticGrepLoops)
            .let(::ensureHermeticMavenBuilds)
            .let { appendTestRepairImageSteps(it, repairCase.patch, repairCase.testPatch, repairCase.testPatchFilePaths()) }
            .let(::disableMavenWarningsAsErrors)
            .let(::splitOversizedPrintfBase64Runs)
            .let(::escapeLiteralDollarsInRunArgs)
        Files.writeString(contextDir.resolve("Dockerfile"), dockerfileContent)

        buildImageWithRetry(imageTag, contextDir, repairCase.instanceId)
        return imageTag
    }

    private fun buildImageWithRetry(imageTag: String, contextDir: Path, instanceId: String) {
        var lastFailure: DockerOperationException? = null
        for (attempt in 1..buildMaxAttempts) {
            logger.status(
                "docker",
                "build image=$imageTag attempt=$attempt/$buildMaxAttempts (timeout=${buildTimeoutSeconds}s)"
            )
            val result = executor.execute(
                workingDirectory = contextDir.toString(),
                command = "bash ${shellQuote(buildScript.absolute().toString())} ${shellQuote(imageTag)}",
                timeoutSecondsOverride = buildTimeoutSeconds,
                progressSink = buildProgressSink("build")
            )
            logger.log(
                "Docker build output for $instanceId (attempt $attempt/$buildMaxAttempts):\n${result.executionMessage}"
            )
            if (result.exitCode == 0) {
                if (attempt > 1) {
                    logger.status("docker", "build image=$imageTag succeeded on attempt $attempt/$buildMaxAttempts")
                }
                return
            }

            lastFailure = DockerOperationException(
                "Docker image build failed for $instanceId (attempt $attempt/$buildMaxAttempts): " +
                        compact(result.executionMessage),
                result.executionMessage
            )

            if (isNonRetriableBuildFailure(result.executionMessage)) {
                logger.status("docker", "build image=$imageTag failed with a non-retriable Dockerfile error; not retrying")
                break
            }
            if (attempt < buildMaxAttempts) {

                val rateLimited = isRateLimited(result.executionMessage)
                val backoffMs = if (rateLimited) {
                    (60_000L shl (attempt - 1)).coerceAtMost(600_000L)
                } else {
                    (attempt * 5_000L).coerceAtMost(30_000L)
                }
                logger.status(
                    "docker",
                    "build image=$imageTag failed (attempt $attempt/$buildMaxAttempts)" +
                            (if (rateLimited) ", rate-limited by a registry (HTTP 429)" else "") +
                            ", retrying in ${backoffMs / 1000}s"
                )
                runCatching { Thread.sleep(backoffMs) }
            }
        }
        throw lastFailure
            ?: DockerOperationException("Docker image build failed for $instanceId", "")
    }

    private fun isNonRetriableBuildFailure(output: String): Boolean {
        val haystack = output.lowercase(Locale.US)
        return haystack.contains("dockerfile parse error") ||
                haystack.contains("unknown instruction") ||
                haystack.contains("unknown flag") ||
                Regex("""failed to solve: [^\n]*(?:parse|syntax) error""").containsMatchIn(haystack)
    }

    private fun isRateLimited(output: String): Boolean =
        output.contains("Too Many Requests", ignoreCase = true) ||
                Regex("""status code:?\s*429\b""").containsMatchIn(output)

    fun startAttemptContainer(imageTag: String): String {
        stopCurrentContainer()
        val contextDir = currentContextDir ?: agentParent
        val result = executor.execute(
            workingDirectory = contextDir.toString(),
            command = "bash ${shellQuote(runScript.absolute().toString())} ${shellQuote(imageTag)} tail -f /dev/null",
            timeoutSecondsOverride = buildTimeoutSeconds,
            progressSink = buildProgressSink("run")
        )
        logger.log("Docker run output:\n${result.executionMessage}")
        if (result.exitCode != 0) {
            throw DockerOperationException(
                "Docker container start failed: ${compact(result.executionMessage)}",
                result.executionMessage
            )
        }
        val containerId = extractContainerId(result.executionMessage)
            ?: throw DockerOperationException(
                "Docker container id was not found in output: ${compact(result.executionMessage)}",
                result.executionMessage
            )
        currentContainerId = containerId
        logger.status("docker", "container=${containerId.take(12)} ready")
        return containerId
    }

    fun stopCurrentContainer() {
        val containerId = currentContainerId ?: return
        executor.execute(
            workingDirectory = agentParent.toString(),
            command = "docker rm -f ${shellQuote(containerId)}"
        )
        logger.status("docker", "container=${containerId.take(12)} stopped")
        currentContainerId = null
    }

    fun executeInProject(command: String, stdin: String? = null): ExecutionResult {
        val containerId = requireContainerId()
        val interactiveFlag = if (stdin == null) "" else "-i "
        return executor.execute(
            workingDirectory = agentParent.toString(),
            command = "docker exec ${interactiveFlag}${shellQuote(containerId)} bash -lc ${shellQuote(projectShell(command))}",
            stdin = stdin
        )
    }

    fun readFile(relativePath: String): String {
        val path = safeRelativePath(relativePath)
        val result = executeInProject("cat -- ${shellQuote(path)}")
        return if (result.exitCode == 0) {
            result.executionMessage
        } else {
            "Failed to read file: ${compact(result.executionMessage)}"
        }
    }

    fun exists(relativePath: String): Boolean {
        val path = safeRelativePath(relativePath)
        val result = executeInProject("test -e ${shellQuote(path)}")
        return result.exitCode == 0
    }

    fun listFolder(relativePath: String, depth: Int): String {
        val path = safeRelativePath(relativePath.ifBlank { "." })
        val maxDepth = (depth + 1).coerceAtLeast(1)
        val command = """
            if [ ! -e ${shellQuote(path)} ]; then
              echo "Does not exist"
            elif [ ! -d ${shellQuote(path)} ]; then
              echo "Not a directory"
            else
              find ${shellQuote(path)} -maxdepth $maxDepth -mindepth 1 -printf '[%y] %p\n' | sort
            fi
        """.trimIndent()
        val result = executeInProject(command)
        return result.executionMessage.ifBlank {
            if (result.exitCode == 0) "Directory is empty" else "Failed to list folder"
        }
    }

    fun writeFile(relativePath: String, content: String) {
        val path = safeRelativePath(relativePath)
        val parent = path.substringBeforeLast('/', missingDelimiterValue = ".")
        val result = executeInProject(
            command = "mkdir -p ${shellQuote(parent)} && cat > ${shellQuote(path)}",
            stdin = withTrailingNewline(content)
        )
        require(result.exitCode == 0) {
            "Failed to write file inside Docker: ${compact(result.executionMessage)}"
        }
    }

    private fun withTrailingNewline(content: String): String =
        if (content.isEmpty() || content.endsWith("\n")) content else content + "\n"

    fun normalizeProjectPath(relativePath: String): String =
        safeRelativePath(relativePath)

    fun resolveReferencedFilePaths(
        references: List<String>,
        knownTestPaths: List<String> = emptyList()
    ): List<String> =
        references
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { resolveReferencedFile(it, knownTestPaths) }
            .distinct()
            .sorted()

    fun collectDiff(): String {
        val status = executeInProject("git status --short").executionMessage.trim()
        val diff = executeInProject(
            """
                git diff -- .
                git ls-files --others --exclude-standard | while IFS= read -r file; do
                  git diff --no-index -- /dev/null "${'$'}file" || true
                done
            """.trimIndent()
        ).executionMessage
        return buildString {
            appendLine("git status --short")
            appendLine(status.ifBlank { "(clean)" })
            appendLine()
            appendLine("patch")
            append(diff.ifBlank { "(no diff)" })
        }
    }

    fun collectReferencedFiles(
        references: List<String>,
        knownTestPaths: List<String> = emptyList()
    ): List<DockerChangedFile> {
        val uniqueReferences = references
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (uniqueReferences.isEmpty()) {
            return emptyList()
        }

        val collectedFiles = uniqueReferences.map { reference ->
            val resolvedPath = resolveReferencedFile(reference, knownTestPaths)
            if (resolvedPath == null) {
                DockerChangedFile(
                    path = unresolvedReferencePath(reference),
                    status = "MISSING",
                    content = null,
                    error = "Could not resolve test reference to a file: $reference"
                )
            } else {
                val contentResult = executeInProject("cat -- ${shellQuote(resolvedPath)}")
                if (contentResult.exitCode == 0) {
                    DockerChangedFile(
                        path = resolvedPath,
                        status = "CASE",
                        content = contentResult.executionMessage
                    )
                } else {
                    DockerChangedFile(
                        path = resolvedPath,
                        status = "ERROR",
                        content = null,
                        error = compact(contentResult.executionMessage)
                    )
                }
            }
        }

        return collectedFiles
            .distinctBy { it.path }
            .sortedBy { it.path }
    }

    fun collectExpectedSolutionFiles(
        imageTag: String,
        paths: List<String>
    ): List<DockerChangedFile> {

        val uniquePaths = paths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (uniquePaths.isEmpty()) return emptyList()
        require(currentContainerId == null) {
            "Expected solution code can only be collected when no attempt container is running"
        }
        startAttemptContainer(imageTag)
        try {
            return uniquePaths
                .map { path ->
                    val safe = safeRelativePath(path)
                    val result = executeInProject("cat -- ${shellQuote("$EXPECTED_SOLUTION_DIR/$safe")}")
                    if (result.exitCode == 0) {
                        DockerChangedFile(path = safe, status = "CASE", content = result.executionMessage)
                    } else {
                        DockerChangedFile(
                            path = safe,
                            status = "ERROR",
                            content = null,
                            error = "Failed to read expected snapshot: ${compact(result.executionMessage)}"
                        )
                    }
                }
                .distinctBy { it.path }
                .sortedBy { it.path }
        } finally {
            stopCurrentContainer()
        }
    }

    private fun resolveReferencedFile(reference: String, knownTestPaths: List<String> = emptyList()): String? {
        val hint = testFileHint(reference)
        val exactMatch = candidatePaths(hint).firstOrNull { fileExists(it) }
        if (exactMatch != null) {
            return exactMatch
        }

        findBySimpleName(hint)?.let { return it }

        return knownTestPathFor(reference, knownTestPaths)?.takeIf { fileExists(it) }
    }

    private fun knownTestPathFor(reference: String, knownTestPaths: List<String>): String? {
        val hint = testFileHint(reference)
        val packagePath = hint.packagePath ?: return null
        val extensions = listOf("java", "kt", "kts", "groovy", "scala")
        val matches = knownTestPaths
            .map { it.trim().removePrefix("./") }
            .filter { path -> extensions.any { "/$path".endsWith("/$packagePath.$it") } }
            .distinct()
        if (matches.size <= 1) return matches.firstOrNull()

        val version = hint.module?.let { Regex("\\d+").find(it)?.value } ?: return null
        return matches.singleOrNull { it.contains("-$version-") }
    }

    fun withGuiDisplay(command: String): String =
        if (command.contains("DISPLAY=:99") && !command.contains("Xvfb")) XVFB_INLINE_PREFIX + command
        else command

    private fun fileExists(relativePath: String): Boolean =
        runCatching { exists(relativePath) }.getOrDefault(false)

    private fun testFileHint(reference: String): TestFileHint {
        val trimmed = reference.trim()
        val module = trimmed
            .substringBefore(':', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() && it != trimmed }
        val body = if (module == null) trimmed else trimmed.substringAfter(':')
        val directPath = body
            .substringBefore("::")
            .substringBefore('#')
            .substringBefore('[')
            .takeIf { it.contains('/') || it.contains('\\') || looksLikeFilePath(it) }
            ?.replace('\\', '/')
            ?.removePrefix("./")

        if (directPath != null) {
            return TestFileHint(
                reference = reference,
                module = module,
                directPath = directPath,
                packagePath = null,
                simpleName = directPath.substringAfterLast('/').substringBeforeLast('.', missingDelimiterValue = directPath)
            )
        }

        val qualifiedName = body
            .substringBefore("::")
            .substringBefore('#')
            .substringBefore('(')
            .substringBefore('[')
            .trim('.')
        val parts = qualifiedName
            .split('.')
            .filter { it.isNotBlank() }
        val classIndex = parts.indexOfLast { looksLikeTestClassName(it) }.takeIf { it >= 0 }
            ?: parts.lastIndex.takeIf { it >= 0 }
        val simpleName = classIndex?.let { parts[it].substringBefore('$') }
        val packagePath = classIndex?.let { parts.take(it + 1).joinToString("/") }

        return TestFileHint(
            reference = reference,
            module = module,
            directPath = null,
            packagePath = packagePath,
            simpleName = simpleName
        )
    }

    private fun candidatePaths(hint: TestFileHint): List<String> {
        val paths = mutableListOf<String>()
        hint.directPath?.let { directPath ->
            paths += directPath
            hint.module?.let { paths += "$it/$directPath" }
        }

        val packagePath = hint.packagePath
        if (packagePath != null) {
            val roots = listOf(
                "src/test/java",
                "src/test/kotlin",
                "src/test/groovy",
                "src/test/scala",
                "src/integrationTest/java",
                "src/integrationTest/kotlin",
                "src/functionalTest/java",
                "src/functionalTest/kotlin",
                "src/jvmTest/java",
                "src/jvmTest/kotlin",
                "src/commonTest/kotlin",
                "test",
                "tests"
            )
            val extensions = listOf("java", "kt", "kts", "groovy", "scala", "py")
            for (root in roots) {
                for (extension in extensions) {
                    val relativePath = "$root/$packagePath.$extension"
                    paths += relativePath
                    hint.module?.let { paths += "$it/$relativePath" }
                }
            }
        }

        return paths.distinct()
    }

    private fun findBySimpleName(hint: TestFileHint): String? {
        val simpleName = hint.simpleName ?: return null
        val searchRoot = hint.module
            ?.takeIf { runCatching { exists(it) }.getOrDefault(false) }
            ?: "."
        val extensions = listOf("java", "kt", "kts", "groovy", "scala", "py")
        val nameExpression = extensions.joinToString(" -o ") { "-name ${shellQuote("$simpleName.$it")}" }
        val result = executeInProject(
            "find ${shellQuote(searchRoot)} -type f \\( $nameExpression \\) | sort"
        )
        if (result.exitCode != 0) {
            return null
        }

        val matches = result.executionMessage
            .lineSequence()
            .map { it.trim().removePrefix("./") }
            .filter { it.isNotBlank() }
            .toList()

        if (matches.isEmpty()) {
            return null
        }

        val packageSuffix = hint.packagePath?.let { "/$it." }
        return matches.firstOrNull { match ->
            packageSuffix != null && "/$match".contains(packageSuffix)
        } ?: matches.first()
    }

    private fun looksLikeTestClassName(value: String): Boolean =
        value.firstOrNull()?.isUpperCase() == true ||
                value.endsWith("Test") ||
                value.endsWith("Tests") ||
                value.endsWith("TestCase") ||
                value.endsWith("Spec") ||
                value.endsWith("IT")

    private fun looksLikeFilePath(value: String): Boolean =
        listOf(
            ".java",
            ".kt",
            ".kts",
            ".groovy",
            ".scala",
            ".py",
            ".xml",
            ".json",
            ".properties",
            ".gradle",
            ".md",
            ".txt",
            ".yml",
            ".yaml"
        ).any { value.endsWith(it) } || value in setOf("Dockerfile", "Makefile")

    private fun unresolvedReferencePath(reference: String): String =
        "__unresolved__/" + reference
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(180)
            .ifBlank { "reference" }

    private fun projectShell(command: String): String =
        "cd /root/agent-work && { source /root/.sdkman/bin/sdkman-init.sh >/dev/null 2>&1 || true; $command; }"

    private fun requireContainerId(): String =
        currentContainerId ?: throw IllegalStateException("Docker container has not been started")

    private fun contextDir(repairCase: SweRepairCase): Path =
        agentParent.resolve("docker-contexts").resolve(repairCase.safeId())

    private fun imageTag(repairCase: SweRepairCase): String {
        val suffix = repairCase.safeId()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_.-]+"), "_")
            .take(100)
            .trim('.', '-', '_')
            .ifBlank { "task" }
        return "swe-$suffix"
    }

    private fun safeRelativePath(relativePath: String): String {
        val trimmed = relativePath.trim().removePrefix("./")
        require(trimmed.isNotBlank()) { "Path must not be empty" }
        require(!trimmed.startsWith("/")) { "Absolute paths are not allowed: $relativePath" }
        val normalized = Path.of(trimmed).normalize().toString().replace('\\', '/')
        require(normalized != ".." && !normalized.startsWith("../")) { "Path escapes project directory: $relativePath" }
        return normalized.ifBlank { "." }
    }

    private fun extractContainerId(output: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("[a-f0-9]{12,64}")) }
            .lastOrNull()

    private fun buildProgressSink(phase: String): (String) -> Unit {
        val lastEmit = java.util.concurrent.atomic.AtomicLong(0L)
        val emitIntervalMs = 5_000L
        return sink@{ raw ->
            val line = raw.trimEnd()
            if (line.isEmpty()) return@sink
            val keep = when {

                line.startsWith("#") && (line.contains(" DONE ") || line.contains(" CACHED") || line.contains(" ERROR") || Regex("""^#\d+ \[""").containsMatchIn(line)) -> true
                line.startsWith("ERROR") || line.startsWith("Dockerfile:") -> true
                line.startsWith("> Task ") || line.contains("BUILD SUCCESSFUL") || line.contains("BUILD FAILED") -> true
                line.startsWith("fatal:") || line.startsWith("FAILURE:") -> true
                else -> false
            }
            val now = System.currentTimeMillis()
            val prev = lastEmit.get()
            if (keep || now - prev >= emitIntervalMs) {
                if (lastEmit.compareAndSet(prev, now) || keep) {
                    logger.log("[docker:$phase] ${line.take(200)}")
                }
            }
        }
    }

    private fun pinSdkmanMaven(dockerfile: String): String {
        val unversioned = Regex("""\bsdk install maven\b(?![ \t]+[0-9])""")
        val pinned = unversioned.replace(dockerfile, "sdk install maven $PINNED_SDKMAN_MAVEN")
        if (pinned != dockerfile) logger.status("docker", "pinned sdkman maven to $PINNED_SDKMAN_MAVEN")
        return pinned
    }

    private fun rewriteDockerfileForAuth(dockerfile: String): String {
        var rewritten = dockerfile
        val notes = mutableListOf<String>()

        val ghSshUrl = Regex("""git@github\.com:([^\s"'\\]+?\.git)""")
        val credentials = githubToken?.let { "x-access-token:$it@" } ?: ""
        val updated = rewritten.replace(ghSshUrl) { match ->
            "https://${credentials}github.com/${match.groupValues[1]}"
        }
        if (updated != rewritten) notes += "github=https"
        rewritten = updated

        val gradleMkdirRe = Regex("""mkdir\s+-p\s+~?/?\.?gradle""")
        val runBashDq = Regex("""RUN\s+bash\s+-c\s+"([^"\\]*(?:\\.[^"\\]*)*)"""")
        val afterMkdir = rewritten.replace(runBashDq) { match ->
            val body = match.groupValues[1]
            when {
                !body.contains("~/.gradle/gradle.properties") -> match.value
                gradleMkdirRe.containsMatchIn(body) -> match.value
                else -> "RUN bash -c \"mkdir -p ~/.gradle && $body\""
            }
        }
        if (afterMkdir != rewritten) notes += "mkdir=~/.gradle"
        rewritten = afterMkdir

        val mountSshRegex = Regex("""--mount=type=ssh(?:,[^\s]*)?\s*""")
        rewritten = rewritten.replace(mountSshRegex, "")

        if (notes.isNotEmpty()) {
            logger.status("docker", "rewrote Dockerfile auth (${notes.joinToString(",")})")
        }
        return rewritten
    }

    private fun neutralizeDiagnosticGrepLoops(dockerfile: String): String {
        val runBashDq = Regex("""^\s*RUN\s+bash\s+-c\s+".*"\s*$""")
        val forLoop = Regex("""\bfor\s+\w+\s+in\b""")

        val pipedIntoGrep = Regex("""\|\s*(?:xargs\s+(?:-\S+\s+)*)?grep\b""")
        var rewrites = 0
        val out = dockerfile.split('\n').joinToString("\n") { line ->
            if (line.trimEnd().endsWith("|| true")) return@joinToString line
            if (!runBashDq.matches(line)) return@joinToString line
            val body = line.substringAfter('"').substringBeforeLast('"')
            val isDiagnosticGrepLoop = forLoop.containsMatchIn(body) &&
                    body.contains("; do ") && body.contains("done") && body.contains("grep")

            val endsInGrep = pipedIntoGrep.containsMatchIn(body)
            if (!isDiagnosticGrepLoop && !endsInGrep) return@joinToString line
            rewrites++
            "${line.trimEnd()} || true"
        }
        if (rewrites > 0) {
            logger.status("docker", "made diagnostic grep-loop RUN(s) non-fatal=$rewrites")
        }
        return out
    }

    private fun collapseRunHeredocs(dockerfile: String): String {

        val openPattern = Regex(
            """^(\s*)RUN\s+bash\s+-c\s+"cat\s*(>>?)\s*(\S+)\s*<<\s*['"]?([A-Za-z_][A-Za-z0-9_]*)['"]?\s*$"""
        )
        val lines = dockerfile.split('\n')
        val result = mutableListOf<String>()
        var rewrites = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val match = openPattern.matchEntire(line)
            if (match == null) {
                result += line
                i++
                continue
            }
            val indent = match.groupValues[1]
            val redir = match.groupValues[2]
            val targetFile = match.groupValues[3]
            val delim = match.groupValues[4]
            val closeSameLine = Regex("""^\s*${Regex.escape(delim)}"\s*$""")
            val closeDelimOnly = Regex("""^\s*${Regex.escape(delim)}\s*$""")
            val closeQuoteOnly = Regex("""^\s*"\s*$""")
            val body = StringBuilder()
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                val current = lines[j]
                if (closeSameLine.matches(current)) {
                    closed = true
                    break
                }
                if (closeDelimOnly.matches(current) &&
                    j + 1 < lines.size &&
                    closeQuoteOnly.matches(lines[j + 1])
                ) {

                    j++
                    closed = true
                    break
                }
                if (body.isNotEmpty()) body.append('\n')
                body.append(current)
                j++
            }
            if (!closed) {
                result += line
                i++
                continue
            }

            val rawBody = body.toString()
            val payload = if (rawBody.isEmpty()) "\n" else unescapeShellDoubleQuoted(rawBody) + "\n"
            val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
            result += "${indent}RUN bash -c \"printf '%s' '$encoded' | base64 -d $redir $targetFile\""
            rewrites++
            i = j + 1
        }
        if (rewrites > 0) {
            logger.status("docker", "normalized heredoc RUN block(s)=$rewrites")
        }
        return result.joinToString("\n")
    }

    private fun collapseRunPrintfWrites(dockerfile: String): String {

        val openPattern = Regex("""^(\s*)RUN\s+bash\s+-c\s+"printf\s+'%s'\s+'(.*)$""")
        val closePattern = Regex("""^(.*)'\s*(>>?)\s*(\S+)"\s*$""")
        val lines = dockerfile.split('\n')
        val result = mutableListOf<String>()
        var rewrites = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val open = openPattern.matchEntire(line)

            if (open == null || runClosesOnSameLine(line)) {
                result += line
                i++
                continue
            }
            val indent = open.groupValues[1]
            val body = StringBuilder(open.groupValues[2])
            var j = i + 1
            var closed = false
            var redir = ">"
            var target = ""
            while (j < lines.size) {
                val close = closePattern.matchEntire(lines[j])
                if (close != null) {
                    body.append('\n').append(close.groupValues[1])
                    redir = close.groupValues[2]
                    target = close.groupValues[3]
                    closed = true
                    break
                }
                body.append('\n').append(lines[j])
                j++
            }
            if (!closed) {

                result += line
                i++
                continue
            }

            val payload = unescapeShellDoubleQuoted(body.toString())
            val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
            result += "${indent}RUN bash -c \"printf '%s' '$encoded' | base64 -d $redir $target\""
            rewrites++
            i = j + 1
        }
        if (rewrites > 0) {
            logger.status("docker", "normalized multi-line printf write(s)=$rewrites")
        }
        return result.joinToString("\n")
    }

    private fun runClosesOnSameLine(line: String): Boolean {
        val open = line.indexOf('"')
        if (open < 0) return true
        var i = open + 1
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                '"' -> return true
                else -> i++
            }
        }
        return false
    }

    private fun unescapeShellDoubleQuoted(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length && value[i + 1] in "\"\\$`") {
                out.append(value[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun disableMavenWarningsAsErrors(dockerfile: String): String {
        if (!dockerfile.contains("mvn")) return dockerfile
        val step = "RUN bash -c \"find . -name pom.xml -exec sed -i " +
                "'s|<failOnWarning>true</failOnWarning>|<failOnWarning>false</failOnWarning>|g' {} +\""
        if (dockerfile.contains(step)) return dockerfile
        logger.status("docker", "appended failOnWarning neutraliser")
        return dockerfile.trimEnd() + "\n" + step + "\n"
    }

    private fun ensureMavenLocalRepoDir(dockerfile: String): String {
        val m2MkdirRe = Regex("""mkdir\s+-p\s+\S*\.m2\b""")
        val runBashDq = Regex("""RUN\s+bash\s+-c\s+"([^"\\]*(?:\\.[^"\\]*)*)"""")
        var rewrites = 0
        val rewritten = dockerfile.replace(runBashDq) { match ->
            val body = match.groupValues[1]
            when {
                !body.contains("/.m2/") -> match.value
                m2MkdirRe.containsMatchIn(body) -> match.value
                else -> {
                    rewrites++
                    "RUN bash -c \"mkdir -p ~/.m2 && $body\""
                }
            }
        }
        if (rewrites > 0) {
            logger.status("docker", "ensured ~/.m2 exists for write(s)=$rewrites")
        }
        return rewritten
    }

    private fun ensureXvfbForGuiTests(dockerfile: String): String {
        val pattern = Regex("""^(\s*RUN\s+bash\s+-c\s+")(.*)("[^"]*)$""")
        var rewrites = 0
        val rewritten = dockerfile.split('\n').joinToString("\n") { line ->
            val match = pattern.matchEntire(line) ?: return@joinToString line
            val body = match.groupValues[2]
            val drivesGuiGradle = body.contains("DISPLAY=:99") && body.contains("gradlew")

            if (!drivesGuiGradle || body.contains("Xvfb")) return@joinToString line
            rewrites++
            match.groupValues[1] + XVFB_INLINE_PREFIX + body + match.groupValues[3]
        }
        if (rewrites > 0) {
            logger.status("docker", "inlined Xvfb start into $rewrites GUI-test RUN step(s)")
        }
        return rewritten
    }

    private fun ensureHermeticMavenBuilds(dockerfile: String): String {
        var inlineAmAdded = 0
        var installsPrepended = 0
        var alsoMakeStripped = 0
        val installedSelectors = mutableSetOf<String>()
        val out = mutableListOf<String>()

        for (line in dockerfile.split('\n')) {
            if (!isMavenModuleLine(line)) {
                out += line
                continue
            }
            when {
                mavenInstallGoal.containsMatchIn(line) -> {
                    installedSelectors += reactorSelectorKey(line)
                    if (mavenAlsoMakeFlag.containsMatchIn(line)) {
                        out += line
                    } else {
                        out += mavenProjectSelector.replace(line) { m -> "${m.groupValues[1]} ${m.groupValues[2]} -am" }
                        inlineAmAdded++
                    }
                }
                hasUpstreamDependentGoal(line) -> {
                    val key = reactorSelectorKey(line)
                    if (installedSelectors.add(key)) {
                        out += prependedInstallStep(line)
                        installsPrepended++
                    }
                    val stripped = stripAlsoMake(line)
                    if (stripped != line) alsoMakeStripped++
                    out += stripped
                }
                else -> out += line
            }
        }
        if (inlineAmAdded > 0 || installsPrepended > 0 || alsoMakeStripped > 0) {
            logger.status(
                "docker",
                "hermetic maven: +$inlineAmAdded -am on installs, +$installsPrepended prepended installs, " +
                        "-$alsoMakeStripped -am off non-install goals"
            )
        }
        return out.joinToString("\n")
    }

    private fun stripAlsoMake(line: String): String =
        line.replace(Regex("""\s(-am|--also-make)(?=\s|"|$)"""), "")

    private fun isMavenModuleLine(line: String): Boolean =
        line.trimStart().startsWith("RUN") &&
                mavenInvocation.containsMatchIn(line) &&
                mavenProjectSelector.containsMatchIn(line)

    private fun hasUpstreamDependentGoal(line: String): Boolean =
        mavenUpstreamDependentGoals.any { goal ->
            Regex("""(^|\s)${Regex.escape(goal)}(\s|$)""").containsMatchIn(line)
        }

    private fun reactorSelectorKey(line: String): String {
        val selector = mavenProjectSelector.find(line)?.groupValues?.get(2).orEmpty()
        val profiles = mavenProfileFlag.findAll(line).map { it.value }.sorted().joinToString(",")
        return "$selector|$profiles"
    }

    private fun prependedInstallStep(line: String): String {
        val indent = line.takeWhile { it.isWhitespace() }
        val selectorMatch = mavenProjectSelector.find(line)!!
        val selectorFlag = selectorMatch.groupValues[1]
        val modules = selectorMatch.groupValues[2]
        val profiles = mavenProfileFlag.findAll(line).map { it.value }.distinct()
        val systemProps = mavenSystemPropertyFlag.findAll(line).map { it.value }
            .filterNot { it.startsWith("-Dtest=") || it.startsWith("-Dit.test=") || it.startsWith("-DskipTests") }
            .distinct()

        val alreadySet = (profiles + systemProps).toSet()
        val resilience = listOf(
            "-fae", "-Dspoon.skip=true", "-Dxwiki.spoon.skip=true", "-Dcheckstyle.skip=true", "-Denforcer.skip=true"
        ).filter { it !in alreadySet }
        val command = (listOf("mvn", "install", selectorFlag, modules, "-am", "-DskipTests") + profiles + systemProps + resilience)
            .joinToString(" ")

        return "${indent}RUN bash -c \"$command\" || true"
    }

    private fun appendTestRepairImageSteps(
        dockerfile: String,
        sourcePatch: String,
        testPatch: String,
        expectedSolutionPaths: List<String>
    ): String {

        val steps = buildList {
            if (testPatch.isNotBlank() && expectedSolutionPaths.isNotEmpty()) {
                addAll(clearPathsSteps(expectedSolutionPaths))
                addAll(applyPatchSteps(testPatch, "/tmp/test.patch"))
            }
            addAll(snapshotSteps(expectedSolutionPaths))
            addAll(clearPathsSteps(expectedSolutionPaths))
            if (sourcePatch.isNotBlank()) {
                addAll(applyPatchSteps(sourcePatch, "/tmp/source.patch"))
            }
        }
        if (steps.isEmpty()) return dockerfile
        logger.status("docker", "appended test-repair flow (snapshot=${expectedSolutionPaths.size}, steps=${steps.size})")
        return dockerfile.trimEnd('\n') + "\n" + steps.joinToString("\n") + "\n"
    }

    private fun snapshotSteps(paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val copies = paths.joinToString(" ; ") { path ->
            val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            val targetDir = if (parent.isEmpty()) EXPECTED_SOLUTION_DIR else "$EXPECTED_SOLUTION_DIR/$parent"
            "if [ -e '$path' ]; then mkdir -p '$targetDir' && cp -- '$path' '$EXPECTED_SOLUTION_DIR/$path'; fi"
        }
        return listOf("RUN bash -c \"$copies\"")
    }

    private fun clearPathsSteps(paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()

        val reverts = paths.joinToString(" ; ") { path ->
            "git checkout HEAD -- '$path' 2>/dev/null || rm -f '$path'"
        }
        return listOf("RUN bash -c \"$reverts\"")
    }

    private fun applyPatchSteps(patch: String, tmpPath: String): List<String> {
        val payload = if (patch.endsWith("\n")) patch else patch + "\n"
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        return listOf(
            "RUN bash -c \"printf '%s' '$encoded' | base64 -d > $tmpPath\"",
            "RUN bash -c \"git apply --whitespace=nowarn $tmpPath && rm -f $tmpPath\""
        )
    }

    private fun escapeLiteralDollarsInRunArgs(dockerfile: String): String {

        val runBashDq = Regex("""^(\s*RUN\s+bash\s+-c\s+")(.*)("\s*)$""")
        var rewrites = 0
        val out = dockerfile.split('\n').joinToString("\n") { line ->
            val match = runBashDq.matchEntire(line) ?: return@joinToString line
            val inner = match.groupValues[2]
            if (!inner.contains('$')) return@joinToString line
            val sb = StringBuilder(inner.length)
            var inSingle = false
            var changed = false
            for ((i, c) in inner.withIndex()) {
                when {
                    c == '\'' -> { inSingle = !inSingle; sb.append(c) }
                    c == '$' && inSingle && (i == 0 || inner[i - 1] != '\\') -> {
                        sb.append("\\$"); changed = true
                    }
                    else -> sb.append(c)
                }
            }
            if (!changed) return@joinToString line
            rewrites++
            match.groupValues[1] + sb.toString() + match.groupValues[3]
        }
        if (rewrites > 0) {
            logger.status("docker", "escaped literal \$ in single-quoted RUN args (lines=$rewrites)")
        }
        return out
    }

    private fun splitOversizedPrintfBase64Runs(dockerfile: String, maxLineLength: Int = 60_000): String {

        val pattern = Regex(
            """^(\s*)RUN\s+bash\s+-c\s+"printf\s+'%s'\s+'([A-Za-z0-9+/=]+)'\s*\|\s*base64\s+-d\s*(>>?)\s*(\S+)"\s*$"""
        )
        val lines = dockerfile.split('\n')
        val result = mutableListOf<String>()
        var rewrites = 0
        for (line in lines) {
            if (line.length <= maxLineLength) {
                result += line
                continue
            }
            val match = pattern.matchEntire(line)
            if (match == null) {
                result += line
                continue
            }
            val indent = match.groupValues[1]
            val base64 = match.groupValues[2]
            val finalRedir = match.groupValues[3]
            val targetFile = match.groupValues[4]
            val tmpFile = "$targetFile.b64"
            val chunkSize = 50_000
            val chunks = base64.chunked(chunkSize)
            chunks.forEachIndexed { idx, chunk ->
                val chunkRedir = if (idx == 0) ">" else ">>"
                result += "${indent}RUN bash -c \"printf '%s' '$chunk' $chunkRedir $tmpFile\""
            }
            result += "${indent}RUN bash -c \"base64 -d $tmpFile $finalRedir $targetFile && rm -f $tmpFile\""
            rewrites++
        }
        if (rewrites > 0) {
            logger.status("docker", "split oversized base64 RUN(s)=$rewrites")
        }
        return result.joinToString("\n")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun compact(message: String, maxLength: Int = 500): String {
        val singleLine = message.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= maxLength) singleLine else "${singleLine.take(maxLength)}..."
    }
}
