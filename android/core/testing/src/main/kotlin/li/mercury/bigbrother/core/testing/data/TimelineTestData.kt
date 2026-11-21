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

import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimelineEntry

fun testTimelineEntry(
    hour: Long,
    uid: Int = 10100,
    packageName: String = "com.example",
    category: Category = Category.STORAGE,
    topic: String? = null,
    filesRead: Int = 1,
): TimelineEntry = TimelineEntry(
    hourStart = hour,
    uid = uid,
    packageName = packageName,
    category = category,
    topic = topic,
    paths = 1,
    filesRead = filesRead,
    allowed = 1,
    denied = 0,
    modes = 1,
    items = 0,
    folder = null,
    firstSeen = hour,
    lastSeen = hour + 10,
)
