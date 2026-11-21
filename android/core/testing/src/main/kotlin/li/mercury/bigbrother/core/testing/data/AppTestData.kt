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

package li.mercury.bigbrother.core.testing.data

import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category

fun testAppSummary(
    uid: Int,
    packageName: String,
    counts: Map<Category, Int> = emptyMap(),
    lastSeen: Long = 1,
    filesRead: Int = 0,
    topics: List<String> = emptyList(),
): AppSummary = AppSummary(uid, packageName, counts, lastSeen, filesRead = filesRead, topics = topics)
