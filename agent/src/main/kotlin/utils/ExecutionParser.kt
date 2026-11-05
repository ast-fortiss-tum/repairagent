package org.example.utils

import kotlin.collections.dropLast

private fun String.find(text: String): String? = if (contains(text)) text else null

class ExecutionParser(private val log: String, private val exitCode: Int) {

    enum class STATUS {
        SUCCESS, COMPILE_ERROR, TEST_FAILURE, TIMEOUT, NOT_EXECUTED, UNKNOWN
    }

    var status: STATUS = STATUS.SUCCESS
    private var message: String = ""

    init {
        if (exitCode == 0) {
            parse_success();
        } else if (exitCode == 124) {
            status = STATUS.TIMEOUT
        } else if (parse_compile_error()) {
            status = STATUS.COMPILE_ERROR
        } else {
            status = STATUS.TEST_FAILURE
            parse_test_failure()
        }
    }

    private fun parse_success() {
        val match = Regex(".*Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)")
            .find(log)

        if (match != null) {
            val (testsRunStr, failuresStr, errorsStr, skippedStr) = match.destructured

            val testsRun = testsRunStr.toIntOrNull()
            val failures = failuresStr.toIntOrNull()
            val errors = errorsStr.toIntOrNull()

            if (testsRun != null && failures != null && errors != null) {
                status = if (testsRun > 0 && failures == 0 && errors == 0) {
                    STATUS.SUCCESS
                } else {
                    STATUS.TEST_FAILURE
                }

            } else {
                status = STATUS.UNKNOWN
            }
        } else {
            status = STATUS.NOT_EXECUTED
        }
        if (status != STATUS.SUCCESS) {
            parse_test_failure()
        }
    }

    private fun parse_compile_error(): Boolean {
        log.find("COMPILATION ERROR") ?: log.find("Compilation failure:") ?: return false
        parse_test_failure();
        return true
    }

    private fun parse_test_failure() {
        val errorIndex = log.lines().indexOfFirst { it.contains("ERROR", ignoreCase = true) }
        message = log.lines().subList(errorIndex, log.lines().size).filter{!it.contains("INFO") && !it.contains("BUILD")}.dropLast(10).joinToString("\n")
    }

    fun getResult(): String = buildString {
        append("Status: ")
        append(status.name)
        append("\n")
        append("Exit code: ")
        append(exitCode)
        append("\n")
        if (status == STATUS.COMPILE_ERROR || status == STATUS.TEST_FAILURE) {
            append("Error message: ")
            append(message)
        }
    }.toString()

}
