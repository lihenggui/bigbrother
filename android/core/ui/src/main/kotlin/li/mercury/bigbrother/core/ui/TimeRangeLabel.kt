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
