package org.example.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class RepairCase(val bCommit: String, val aCommit: String, val testPath: String, val testName: String)

class DatasetManager( val projectLink: String, val projectName: String) {
    private val dataset: JsonArray
    private var testId = 0

    init {
        val datasetData = File("dataset_test_repair/$projectName/dataset.json").readText()
        dataset = Json.parseToJsonElement(datasetData).jsonArray
    }

    fun getCase(): RepairCase {
        val name = dataset[testId].jsonObject["name"]!!.jsonPrimitive.content.split('.')
        return RepairCase(
            dataset[testId].jsonObject["bCommit"]!!.jsonPrimitive.content,
            dataset[testId].jsonObject["aCommit"]!!.jsonPrimitive.content,
            dataset[testId].jsonObject["bPath"]!!.jsonPrimitive.content,
            "${name[name.size - 2]}#${name.last().dropLast(2)}"
        )
    }
    fun setCase(id: Int) { testId = id }
    fun getCaseId(): Int = testId
    fun getNumberOfCases(): Int = dataset.size
}
