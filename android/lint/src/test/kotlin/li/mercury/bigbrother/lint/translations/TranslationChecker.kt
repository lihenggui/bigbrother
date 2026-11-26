/*
 * Copyright (C) 2026 Mercury Li
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package li.mercury.bigbrother.lint.translations

import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.tools.lint.checks.PluralsDatabase
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal fun checkTranslations(modules: List<File>, root: File, releaseLocales: Set<String>, targetSdk: Int): List<String> {
    val requiredLocales = releaseLocales - "en"
    val pluralDatabase = PluralsDatabase.get(targetSdk)
    return buildList {
        for (module in modules) {
            val res = module.resolve("src/main/res")
            val defaultFile = res.resolve("values/strings.xml")
            val translations = res.listFiles().orEmpty().sorted().mapNotNull { directory ->
                val file = directory.resolve("strings.xml")
                val locale = FolderConfiguration.getConfigForFolder(directory.name)?.localeQualifier
                if (file.isFile && locale != null) locale to file else null
            }
            if (!defaultFile.isFile && translations.isEmpty()) continue

            val moduleName = ":" + module.relativeTo(root).invariantSeparatorsPath.replace('/', ':')
            if (!defaultFile.isFile) {
                add("$moduleName ${defaultFile.relativeTo(module)}: missing default resources")
                continue
            }

            val defaults = readResources(moduleName, module, defaultFile)
            addAll(checkPlurals(defaults, "en", pluralDatabase))
            val actualLocales = translations.map { (locale, _) -> locale.folderSegment }.toSet()
            for (missing in requiredLocales - actualLocales) {
                add("$moduleName src/main/res/values-$missing/strings.xml: missing translation for $missing in appLocales")
            }

            for ((locale, file) in translations) {
                if (locale.folderSegment !in releaseLocales) {
                    add("$moduleName ${file.relativeTo(module)}: ${locale.folderSegment} is missing from appLocales")
                }
                val translated = readResources(moduleName, module, file)
                addAll(checkPlurals(translated, locale.language ?: locale.folderSegment, pluralDatabase))
                addAll(compareResources(defaults, translated))
            }
        }
    }
}

private data class ResourceFile(val location: String, val entries: Map<String, ResourceEntry>) {
    fun at(name: String): String = "$location [$name]"
}

private data class ResourceEntry(
    val type: String,
    val translatable: Boolean,
    val formats: Map<String, Set<FormatArgument>>,
)

private data class FormatArgument(val position: String, val conversion: String) {
    override fun toString(): String = "%$position$conversion"
}

private fun readResources(moduleName: String, module: File, file: File): ResourceFile {
    val location = "$moduleName ${file.relativeTo(module)}"
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = try {
        factory.newDocumentBuilder().parse(file)
    } catch (exception: SAXException) {
        throw IllegalArgumentException("$location: ${exception.message}", exception)
    }
    val entries = buildMap {
        for (element in document.documentElement.elements().filter { it.tagName == "string" || it.tagName == "plurals" }) {
            val name = element.getAttribute("name")
            val entryLocation = "$location [$name]"
            val formats = if (element.tagName == "plurals") {
                buildMap {
                    for (item in element.elements().filter { it.tagName == "item" }) {
                        val quantity = item.getAttribute("quantity")
                        check(put(quantity, formatArguments(item.textContent, "$entryLocation quantity $quantity")) == null) {
                            "$entryLocation: duplicate quantity $quantity"
                        }
                    }
                }
            } else {
                val arguments = if (element.getAttribute("formatted") == "false") {
                    emptySet()
                } else {
                    formatArguments(element.textContent, entryLocation)
                }
                mapOf("" to arguments)
            }
            check(put(name, ResourceEntry(element.tagName, element.getAttribute("translatable") != "false", formats)) == null) {
                "$entryLocation: duplicate resource"
            }
        }
    }
    return ResourceFile(location, entries)
}

private fun Element.elements(): Sequence<Element> = (0 until childNodes.length).asSequence().mapNotNull { childNodes.item(it) as? Element }

// Include the date/time prefix in the conversion and consume literal %% in the same pass.
private val formatSpecifier = Regex("""%([1-9]\d*\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([tT][HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc]|[bBhHsScCdoxXeEfgGaA%n])""")

private fun formatArguments(value: String, location: String): Set<FormatArgument> {
    val arguments = formatSpecifier.findAll(value).filter { it.groupValues[2] != "%" && it.groupValues[2] != "n" }.toList()
    check(arguments.size <= 1 || arguments.all { it.groupValues[1].isNotEmpty() && '<' !in it.value }) {
        "$location: use an explicit position for every format argument when there is more than one"
    }
    return arguments.map { FormatArgument(it.groupValues[1], it.groupValues[2]) }.toSet()
}

private fun checkPlurals(file: ResourceFile, language: String, database: PluralsDatabase): List<String> {
    val relevant = database.getRelevant(language)
        ?: return listOf("${file.location}: unknown plural language $language")
    val required = relevant.map { it.name } + PluralsDatabase.Quantity.other.name
    return buildList {
        for ((name, entry) in file.entries) {
            if (entry.type != "plurals") continue
            for (missing in required - entry.formats.keys) {
                add("${file.at(name)}: missing plural quantity $missing for $language")
            }
        }
    }
}

private fun compareResources(defaults: ResourceFile, translated: ResourceFile): List<String> = buildList {
    for ((name, original) in defaults.entries) {
        if (original.translatable && name !in translated.entries) {
            add("${translated.at(name)}: missing translation")
        }
    }
    for ((name, translation) in translated.entries) {
        val original = defaults.entries[name]
        val location = translated.at(name)
        when {
            original == null -> add("$location: no resource with this name in ${defaults.location}")

            !original.translatable -> add("$location: default resource is marked translatable=\"false\"")

            original.type != translation.type -> add("$location: expected <${original.type}>, found <${translation.type}>")

            else -> for ((quantity, actual) in translation.formats) {
                // A locale-specific quantity absent from English uses its fallback argument contract.
                val expected = original.formats[quantity] ?: original.formats["other"]
                if (expected != null && actual != expected) {
                    val subject = if (quantity.isEmpty()) location else "$location quantity $quantity"
                    add("$subject: expected format arguments $expected, found $actual")
                }
            }
        }
    }
}
