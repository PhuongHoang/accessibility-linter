package com.github.bucherfa.accessibilitylinter.annotators

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.github.bucherfa.accessibilitylinter.misc.ConfigAxe
import com.github.bucherfa.accessibilitylinter.services.LinterService
import com.google.gson.JsonArray
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Path

class CustomAnnotation(val range: TextRange, val type: String, val message: String, val url: String)
class CollectedInformation(val input: String, val config: ConfigAxe, val linterService: LinterService)

abstract class AnnotatorBase : ExternalAnnotator<CollectedInformation, List<CustomAnnotation>>() {

    abstract val fileTypes:  List<String>
    abstract fun prepareInput(input: String): String?

    var fileStartingOffset = 0

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): CollectedInformation? {
        println("Starting...")
        val fileType = getFileExtension(file.name)
        println("File extension $fileType will be matched against list of accepted file types $fileTypes, match result: ${fileTypes.contains(fileType)}")
        if (!fileTypes.contains(fileType)) {
            return null
        }
        println("${file.name} will be checked for accessibility issues.")
        val cleanInput = prepareInput(file.text)?: return null
        println("Cleaning is done for the file ${file.name}")
        val linterService = file.project.service<LinterService>()
        return CollectedInformation(
            cleanInput,
            getConfig(file),
            linterService
        )
    }

    override fun doAnnotate(collectedInformation: CollectedInformation?): List<CustomAnnotation>? {
        if (collectedInformation == null) {
            return listOf()
        }
        val response = collectedInformation.linterService.runRequest(
            collectedInformation.input,
            collectedInformation.config
        )
        var annotations: List<CustomAnnotation> = mutableListOf()
        if (response != null) {
            val serviceAnswer = response.get()
            println("Received a response from accessibility linter: $serviceAnswer")
            val element = serviceAnswer?.element
            val result = element?.getAsJsonArray("result")
            if (result != null) {
                annotations = processResult(result)
            }
        }
        return annotations
    }

    override fun apply(file: PsiFile, annotationResult: List<CustomAnnotation>?, holder: AnnotationHolder) {
        if (!annotationResult.isNullOrEmpty()) {
            for (annotation in annotationResult) {
                val message = "Accessibility Linter: ${annotation.message} (${annotation.type})"
                holder.newAnnotation(HighlightSeverity.WARNING, message)
            }
        }
        println("... finished")
    }

    private fun getConfig(file: PsiFile): ConfigAxe {
        val configFiles = FilenameIndex.getVirtualFilesByName("axe-linter.yml", GlobalSearchScope.projectScope(file.project))
        for (configFile in configFiles) {
            if (configFile.isDirectory) {
                continue
            }
            val configFilePath = configFile.path
            val projectPath = file.project.basePath
            val configFileName = configFile.name
            if (!projectPath.isNullOrEmpty() && Path.of(projectPath, configFileName).equals(Path.of(configFilePath))) {
                val mapper = ObjectMapper(YAMLFactory())
                return try {
                    mapper.readValue(Path.of(configFile.path).toFile(), ConfigAxe::class.java)
                } catch (e: Exception) {
                    //TODO user notification https://plugins.jetbrains.com/docs/intellij/notifications.html
                    ConfigAxe()
                }
            }
        }
        return ConfigAxe()
    }

    fun processResult(result: JsonArray): List<CustomAnnotation> {
        val annotations = mutableListOf<CustomAnnotation>()
        for (violation in result) {
            val violationObject = violation.asJsonObject
            val type = violationObject.get("type").asString
            val helpString = violationObject.get("help").asString
            val helpUrl = violationObject.get("helpUrl").asString
            for (occasion in violationObject.get("occasions").asJsonArray) {
                val occasionObject = occasion.asJsonObject
                val startOffset = occasionObject.get("startOffset").asInt + fileStartingOffset
                val endOffset = occasionObject.get("endOffset").asInt + fileStartingOffset
                val range = TextRange(startOffset, endOffset)
                annotations.add(CustomAnnotation(range, type, helpString, helpUrl))
            }
        }
        return annotations
    }

    fun getFileExtension(fileName: String): String {
        return fileName.split('.').last()
    }

    fun removeElementsFromString(string: String, startIndicator: String, endIndicator: String): String {
        var result = string
        val regex = Regex("$startIndicator.*?$endIndicator")
        val occasions = regex.findAll(string)
        for (occasion in occasions) {
            val length = occasion.range.last - occasion.range.first + 1
            var replacementString = ""
            for (i in 0 until length) {
                replacementString += " "
            }
            result = result.replaceRange(occasion.range, replacementString)
        }
        return result
    }

    fun removeMultipleElementsFromString(input: String, list: List<Pair<String, String>>): String {
        var output = input
        for (commentStartEnd in list) {
            output = removeElementsFromString(
                output,
                commentStartEnd.first,
                commentStartEnd.second
            )
        }
        return output
    }
}
