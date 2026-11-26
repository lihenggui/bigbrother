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

package li.mercury.bigbrother.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BidiTest {
    @Test
    fun givenUrlInArabicSentence_whenIsolated_thenOnlyTheUrlIsEnclosed() {
        val url = "https://example.com/path?q=1&x=2"
        val sentence = "حظر ${isolateLtr(url)} الآن"
        assertEquals("حظر \u2066$url\u2069 الآن", sentence)
    }

    @Test
    fun givenPathStartingWithPunctuation_whenIsolated_thenPreservesItsSyntax() {
        val path = "/storage/emulated/0/DCIM/Camera"
        assertEquals("\u2066$path\u2069", isolateLtr(path))
    }

    @Test
    fun givenAppLabels_whenFormatting_thenIsolatesOnlyTheTechnicalFallback() {
        assertEquals("\u2066com.example.app\u2069", appLabelArgument("com.example.app", "com.example.app"))
        assertEquals("\u2066com.example.shared\u2069", appLabelArgument("com.example.shared", "@com.example.shared"))
        assertEquals("\u2066@com.example.shared\u2069", appLabelArgument("@com.example.shared", "@com.example.shared"))
        assertEquals("متصفح", appLabelArgument("متصفح", "com.example.app"))
    }
}
