package li.mercury.bigbrother.core.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.ui.graphics.vector.ImageVector
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category

/**
 * The kinds of private data the records are grouped into: user files first,
 * then the rest from most to least sensitive. Anything that identifies the
 * device, describes how it is used, or helps build a profile of its owner
 * counts as private: device identifiers and hardware fingerprints as much as
 * messages and contacts.
 *
 * The names follow the data categories of Google Play's data safety section
 * where one fits.
 */
enum class DataCategory(@StringRes val title: Int, val icon: ImageVector) {
    /** User files on shared storage: what this app was first built to watch, so it leads. */
    FILES(R.string.category_files, Icons.Outlined.Folder),
    MESSAGES(R.string.category_messages, Icons.Outlined.Sms),
    CONTACTS(R.string.category_contacts, Icons.Outlined.Contacts),
    LOCATION(R.string.category_location, Icons.Outlined.LocationOn),
    SENSORS(R.string.category_sensors, Icons.Outlined.Sensors),
    CALENDAR(R.string.category_calendar, Icons.Outlined.CalendarMonth),
    PERSONAL_INFO(R.string.category_personal_info, Icons.Outlined.Person),
    APP_ACTIVITY(R.string.category_app_activity, Icons.Outlined.Apps),
    DEVICE_IDS(R.string.category_device_ids, Icons.Outlined.Fingerprint),
    NETWORK(R.string.category_network, Icons.Outlined.Lan),
    DEVICE_PROFILE(R.string.category_device_profile, Icons.Outlined.Memory),
    ENVIRONMENT(R.string.category_environment, Icons.Outlined.Shield),
}

/**
 * The category of a record: its topic's; for a topic id this build does
 * not know, the device fingerprint; for a record without a topic (made before
 * topics existed), the closest match for the kind of record. Unknown data is
 * still private by definition, so it is never dropped.
 */
fun dataCategoryOf(topic: String?, category: Category): DataCategory = when {
    // The same fallback as dataCategories(), so a category chip on the list
    // always has a matching tab on the app's page.
    topic != null -> topicCategory(topic) ?: DataCategory.DEVICE_PROFILE
    else -> when (category) {
        Category.STORAGE, Category.PROVIDER -> DataCategory.FILES
        Category.DEVICE -> DataCategory.DEVICE_IDS
        Category.SYSTEM -> DataCategory.DEVICE_PROFILE
        Category.PROCESS, Category.SERVICE -> DataCategory.APP_ACTIVITY
        Category.NETWORK -> DataCategory.NETWORK
        Category.COMMAND -> DataCategory.ENVIRONMENT
        Category.PERSONAL, Category.PERMISSION -> DataCategory.PERSONAL_INFO
    }
}

/** The categories of private data an app accessed, in category order. */
fun AppSummary.dataCategories(): List<DataCategory> {
    // Summaries carry topic ids only; one this build does not know is
    // counted with the device fingerprint.
    val found = topics.mapTo(HashSet()) { topicCategory(it) ?: DataCategory.DEVICE_PROFILE }
    if (count(Category.STORAGE) > 0 || count(Category.PROVIDER) > 0) found += DataCategory.FILES
    return found.sorted()
}
