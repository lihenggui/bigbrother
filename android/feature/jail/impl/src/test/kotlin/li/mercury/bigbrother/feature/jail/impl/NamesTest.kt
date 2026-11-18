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

package li.mercury.bigbrother.feature.jail.impl

import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class NamesTest {
    @Test
    fun givenChineseNames_whenSortedByName_thenInPinyinOrder() {
        val names = listOf("天气", "手电筒", "购物")

        assertEquals(listOf("购物", "手电筒", "天气"), names.sortedByName(Collator.getInstance(Locale.CHINA)) { it })
    }

    @Test
    fun givenNamesInMixedCase_whenSortedByName_thenCaseIgnored() {
        val names = listOf("weather", "Notes", "arctic")

        assertEquals(listOf("arctic", "Notes", "weather"), names.sortedByName(Collator.getInstance(Locale.US)) { it })
    }
}
