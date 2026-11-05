package org.example.utils

import org.example.config.Config
import org.example.tools.CommandExecutor
import java.io.File

class ProjectManager(
    private val projectName: String,
    private val projectLink: String,
    private val agentDir: String = "",
    private val agentParent: String = "",
    config: Config,
) {
    private val executor = CommandExecutor(
        timeoutSeconds = config.app.timeoutSeconds,
        javaHome = config.secrets.javaHome
    )
    private var pomPath: String? = null

    fun setup(repairCase: RepairCase): String {
        executor.execute(agentParent, "rm -rf $projectName")

        executor.execute(agentParent,  "git clone $projectLink")

        checkout(repairCase.bCommit)
        val testBefore = readTestFile(repairCase.testPath)
        checkout(repairCase.aCommit)
        val testAfter = readTestFile(repairCase.testPath)

        File("${agentDir}/${repairCase.testPath}").writeText(testBefore)

        executor.execute(agentDir,  "git diff \"${repairCase.bCommit}\" ':(exclude)**Test.*' > commitDiff.txt")

        pomPath = find_pom(repairCase.testPath)
        return testAfter
    }

    private fun checkout(commit: String) {
        executor.execute(agentDir, "git checkout -f $commit")
    }

    private fun readTestFile(filePath: String) : String {
        return File("$agentDir/$filePath").readText()
    }

    private fun find_pom(testPath: String) : String {
        var pathComponent =  testPath.split('/')
        while (!pathComponent.isEmpty()) {
            pathComponent = pathComponent.dropLast(1)
            if (File(agentDir + '/' +  pathComponent.joinToString("/") + "/pom.xml").exists()) {
                return pathComponent.joinToString("/") + "/pom.xml"
            }
        }
        throw IllegalStateException("No pom file exists for this project $projectLink")
    }

    fun getPomPath() : String = pomPath ?: throw IllegalStateException("No search for pom file happened")
}
