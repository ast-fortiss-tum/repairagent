package org.example.utils
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import mu.KotlinLogging
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class Logger(runLogPath: String) {

    private val logger = KotlinLogging.logger {}
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val rootLogger = context.getLogger("ROOT")
    private val toolCallCounter = AtomicInteger(0)
    private val consoleLock = Any()

    @Volatile
    private var liveLineActive = false

    @Volatile
    private var attemptAppender: FileAppender<ILoggingEvent>? = null

    init {
        val runAppender = createFileAppender("RUN_FILE", runLogPath)
        attachAppender(runAppender)
        logger.info { "Run log initialized" }
    }

    fun log(message: String) {
        logger.info { message }
    }

    fun status(label: String, message: String) {
        val line = "[${formatLabel(label)}] $message"
        printConsoleLine(line)
        logger.info { line }
    }

    fun warn(message: String) {
        printConsoleLine("[${formatLabel("warn")}] $message")
        logger.warn { message }
    }

    fun error(message: String, throwable: Throwable? = null) {
        printConsoleLine("[${formatLabel("error")}] $message")
        if (throwable == null) {
            logger.error { message }
        } else {
            logger.error(throwable) { message }
        }
    }

    fun startAttemptProgress() {
        toolCallCounter.set(0)
        updateLiveLine("[${formatLabel("agent")}] ⏳ tools=0 waiting for tool")
    }

    fun recordToolCall(toolName: String, toolArgs: Any?) {
        val toolCallNumber = toolCallCounter.incrementAndGet()
        updateLiveLine("[${formatLabel("agent")}] ⏳ tools=$toolCallNumber running=$toolName")
        logger.info { "Tool #$toolCallNumber called: tool $toolName, args $toolArgs" }
    }

    fun recordToolCompleted(toolName: String) {
        logger.info { "Tool completed: $toolName" }
    }

    fun recordToolFailed(toolName: String, message: String? = null) {
        val suffix = message?.takeIf { it.isNotBlank() }?.let { " error=${compactMessage(it)}" } ?: ""
        warn("tool failed: $toolName$suffix")
        logger.warn { "Tool failed: $toolName${message?.let { " - $it" } ?: ""}" }
    }

    fun attemptResult(success: Boolean, caseNumber: Int, attempt: Int) {
        val emoji = if (success) "✅" else "❌"
        val result = if (success) "success" else "not fixed"
        val line = "[${formatLabel("result")}] $emoji case=$caseNumber attempt=$attempt $result tools=${toolCallCounter.get()}"
        completeLiveLine(line)
        logger.info { line }
    }

    fun attemptEndMarker(success: Boolean, caseNumber: Int, attempt: Int) {
        val status = if (success) "SUCCESS" else "FAILURE"
        logger.info {
            "ATTEMPT_END_MARKER case=$caseNumber attempt=$attempt status=$status success=$success tools=${toolCallCounter.get()}"
        }
    }

    fun switchLogFile(newPath: String) {
        synchronized(this) {
            attemptAppender?.let {
                detachAppender(it)
                it.stop()
            }

            val fileAppender = createFileAppender("ATTEMPT_FILE", newPath)
            attachAppender(fileAppender)
            attemptAppender = fileAppender

            logger.info { "Attempt log initialized: ${File(newPath).name}" }
        }
    }

    fun clearLogFile() {
        synchronized(this) {
            attemptAppender?.let {
                detachAppender(it)
                it.stop()
            }
            attemptAppender = null
        }
    }

    private fun createFileAppender(name: String, path: String): FileAppender<ILoggingEvent> {
        File(path).parentFile?.mkdirs()

        val encoder = PatternLayoutEncoder().apply {
            context = this@Logger.context
            pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
            start()
        }

        return FileAppender<ILoggingEvent>().apply {
            context = this@Logger.context
            this.name = "${name}_${System.identityHashCode(this@Logger)}"
            file = path
            isAppend = false
            this.encoder = encoder
            start()
        }
    }

    private fun attachAppender(appender: FileAppender<ILoggingEvent>) {
        targetLoggers().forEach {
            it.detachAppender(appender.name)
            it.addAppender(appender)
        }
    }

    private fun detachAppender(appender: FileAppender<ILoggingEvent>) {
        targetLoggers().forEach { it.detachAppender(appender.name) }
    }

    private fun targetLoggers() = buildList {
        add(rootLogger)
        listOf("ai", "ai.koog")
            .map { context.getLogger(it) }
            .filter { !it.isAdditive }
            .forEach { add(it) }
    }

    private fun printConsoleLine(line: String) {
        synchronized(consoleLock) {
            finishLiveLine()
            println(line)
        }
    }

    private fun updateLiveLine(line: String) {
        synchronized(consoleLock) {
            print("\r\u001B[2K$line")
            System.out.flush()
            liveLineActive = true
        }
    }

    private fun completeLiveLine(line: String) {
        synchronized(consoleLock) {
            print("\r\u001B[2K$line")
            println()
            liveLineActive = false
        }
    }

    private fun finishLiveLine() {
        if (liveLineActive) {
            println()
            liveLineActive = false
        }
    }

    private fun formatLabel(label: String): String {
        val shortened = label.take(CONSOLE_LABEL_WIDTH)
        val padding = CONSOLE_LABEL_WIDTH - shortened.length
        val leftPadding = padding / 2
        val rightPadding = padding - leftPadding
        return " ".repeat(leftPadding) + shortened + " ".repeat(rightPadding)
    }

    private companion object {
        const val CONSOLE_LABEL_WIDTH = 9
    }
}

private fun compactMessage(message: String, maxLength: Int = 80): String {
    val singleLine = message.replace(Regex("\\s+"), " ").trim()
    return if (singleLine.length <= maxLength) singleLine else "${singleLine.take(maxLength)}..."
}
