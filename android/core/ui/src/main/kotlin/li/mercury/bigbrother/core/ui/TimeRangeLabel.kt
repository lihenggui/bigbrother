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

import androidx.annotation.StringRes
import li.mercury.bigbrother.core.model.TimeRange

/** Name of a time range: "Last 24 hours". */
@get:StringRes
val TimeRange.label: Int
    get() = when (this) {
        TimeRange.HOUR -> R.string.range_hour
        TimeRange.DAY -> R.string.range_day
        TimeRange.WEEK -> R.string.range_week
        TimeRange.ALL -> R.string.range_all
    }
