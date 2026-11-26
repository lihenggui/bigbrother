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

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslationCheckerTest {
    @get:Rule
    val fixture = TemporaryFolder()

    private val targetSdk = System.getProperty("translations.targetSdk").toInt()

    @Test
    fun givenAppResources_whenCheckingTranslations_thenEveryLocaleIsComplete() {
        val root = File(System.getProperty("translations.root"))
        val modules = System.getProperty("translations.modules").split(',').map(root::resolve)
        val locales = System.getProperty("translations.locales").split(',').toSet()
        val errors = checkTranslations(modules, root, locales, targetSdk)
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun givenMissingString_whenCheckingTranslation_thenReportsItsName() {
        resources("values", "<string name=\"hello\">Hello</string>")
        resources("values-zh", "")
        assertContains(errors("zh").single(), ":feature src/main/res/values-zh/strings.xml [hello]: missing translation")
    }

    @Test
    fun givenExtraString_whenCheckingTranslation_thenReportsItsName() {
        resources("values", "")
        resources("values-zh", "<string name=\"extra\">Extra</string>")
        assertContains(errors("zh").single(), "[extra]: no resource with this name")
    }

    @Test
    fun givenDifferentConversion_whenCheckingTranslation_thenReportsBothSpecifiers() {
        resources("values", """<string name="hello">Hello %1${'$'}s</string>""")
        resources("values-zh", """<string name="hello">Hello %1${'$'}d</string>""")
        assertContains(errors("zh").single(), "[hello]: expected format arguments [%1${'$'}s], found [%1${'$'}d]")
    }

    @Test
    fun givenVietnameseTranslationWithOnlyOther_whenCheckingTranslation_thenPasses() {
        resources("values", plurals("one", "other"))
        resources("values-vi", plurals("other"))
        assertEquals(emptyList(), errors("vi"))
    }

    @Test
    fun givenVietnameseTranslationWithoutOther_whenCheckingTranslation_thenRequiresOther() {
        resources("values", plurals("one", "other"))
        resources("values-vi", plurals("one"))
        assertContains(errors("vi").single(), "[count]: missing plural quantity other for vi")
    }

    @Test
    fun givenRussianTranslationWithoutRequiredQuantities_whenCheckingTranslation_thenRequiresOneFewManyAndOther() {
        resources("values", plurals("one", "other"))
        resources("values-ru", plurals())
        val expected = setOf("one", "few", "many", "other").map { quantity ->
            ":feature src/main/res/values-ru/strings.xml [count]: missing plural quantity $quantity for ru"
        }
        assertEquals(expected.toSet(), errors("ru").toSet())
    }

    @Test
    fun givenFrenchTranslationWithoutMany_whenCheckingTranslation_thenRequiresMany() {
        resources("values", plurals("one", "other"))
        resources("values-fr", plurals("one", "other"))
        assertContains(errors("fr").single(), "[count]: missing plural quantity many for fr")
    }

    @Test
    fun givenUnknownLanguage_whenCheckingTranslation_thenReportsItsCode() {
        resources("values", plurals("one", "other"))
        resources("values-zz", plurals("other"))
        assertContains(errors("zz").single(), "values-zz/strings.xml: unknown plural language zz")
    }

    @Test
    fun givenNewPluralQuantityWithWrongConversion_whenCheckingTranslation_thenComparesWithOther() {
        resources("values", plurals("one", "other"))
        val russian = plurals("one", "few", "many", "other").replace(""""few">%1${'$'}d""", """"few">%1${'$'}s""")
        resources("values-ru", russian)
        assertContains(errors("ru").single(), "[count] quantity few: expected format arguments [%1${'$'}d], found [%1${'$'}s]")
    }

    @Test
    fun givenUnlistedLocale_whenCheckingTranslation_thenReportsMissingFilter() {
        resources("values", "")
        resources("values-zh", "")
        assertContains(errors().single(), "values-zh/strings.xml: zh is missing from appLocales")
    }

    @Test
    fun givenMissingModuleTranslation_whenCheckingFilters_thenReportsMissingFile() {
        resources("values", "")
        assertContains(errors("zh").single(), "values-zh/strings.xml: missing translation for zh")
    }

    @Test
    fun givenNoTranslationAllowed_whenCheckingTranslation_thenReportsNontranslatableEntry() {
        resources("values", """<string name="brand" translatable="false">Big Brother</string>""")
        resources("values-zh", """<string name="brand">Big Brother</string>""")
        assertContains(errors("zh").single(), "[brand]: default resource is marked translatable=\"false\"")
    }

    @Test
    fun givenReorderedArgumentsAndLiteralPercent_whenCheckingTranslation_thenPasses() {
        resources("values", """<string name="progress">%1${'$'}s %2${'$'}d%%</string>""")
        resources("values-zh", """<string name="progress">%2${'$'}d%% %1${'$'}s</string>""")
        assertEquals(emptyList(), errors("zh"))
    }

    @Test
    fun givenMultipleUnpositionedArguments_whenCheckingEitherFile_thenReportsThatFile() {
        val invalid = """<string name="progress">%s %d</string>"""
        val valid = """<string name="progress">%1${'$'}s %2${'$'}d</string>"""
        resources("values", invalid)
        resources("values-zh", valid)
        assertContains(assertFailsWith<IllegalStateException> { errors("zh") }.message.orEmpty(), "values/strings.xml [progress]")
        resources("values", valid)
        resources("values-zh", invalid)
        assertContains(assertFailsWith<IllegalStateException> { errors("zh") }.message.orEmpty(), "values-zh/strings.xml [progress]")
    }

    @Test
    fun givenRegionScriptAndLegacyQualifiers_whenCheckingFilters_thenMatchesAndroidNames() {
        resources("values", plurals("one", "other"))
        resources("values-zh-rTW", plurals("other"))
        resources("values-b+zh+Hant", plurals("other"))
        resources("values-in", plurals("other"))
        resources("values-night", "")
        assertEquals(emptyList(), errors("zh-rTW", "b+zh+Hant", "in"))
    }

    private fun resources(directory: String, body: String) {
        File(fixture.root, "feature/src/main/res/$directory/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources>$body</resources>")
        }
    }

    private fun errors(vararg translated: String) = checkTranslations(
        listOf(fixture.root.resolve("feature")),
        fixture.root,
        setOf("en", *translated),
        targetSdk,
    )

    private fun plurals(vararg quantities: String): String = quantities.joinToString(
        separator = "",
        prefix = """<plurals name="count">""",
        postfix = "</plurals>",
    ) { """<item quantity="$it">%1${'$'}d</item>""" }
}
