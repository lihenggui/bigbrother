package li.mercury.bigbrother.feature.overview.impl

import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.readFiles
import li.mercury.bigbrother.core.ui.topicCategory

/** What the overview's first card says about user files. */
data class FilesOverview(
    /** Apps that read files, directly or through the media library, most recent first. */
    val readers: List<AppSummary>,
    /** The folders the most of them read from, most common first. */
    val folders: List<String>,
    /** Whether any of them went through the media library. */
    val mediaLibrary: Boolean,
    /** Apps that touched files without reading any: listed, probed, watched or were denied. */
    val lookedOnly: Int,
)

/** One kind of private data, other than files, and who accessed it. */
data class CategoryOverview(
    val category: DataCategory,
    /** Apps that accessed it. */
    val apps: Int,
    /** Topic ids within it, the most widely accessed first. */
    val topics: List<String>,
)

/** How many folders the first card names. */
private const val FOLDERS_NAMED = 3

/** The overview of [apps]: the files card, then the other categories in category order. */
fun overviewOf(apps: List<AppSummary>): Pair<FilesOverview, List<CategoryOverview>> {
    val readers = apps.filter { it.readFiles }.sortedByDescending { it.lastSeen }
    val folders = readers
        .flatMap { it.folders }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(FOLDERS_NAMED)
        .map { it.key }
    val files = FilesOverview(
        readers = readers,
        folders = folders,
        mediaLibrary = readers.any { it.count(Category.PROVIDER) > 0 },
        lookedOnly = apps.count { !it.readFiles && it.count(Category.STORAGE) > 0 },
    )
    val categories = apps
        .flatMap { app -> app.dataCategories().filter { it != DataCategory.FILES }.map { it to app } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (category, members) ->
            // Topics of this category, by how many of its apps went after each.
            val topics = members
                .flatMap { app -> app.topics.filter { (topicCategory(it) ?: DataCategory.DEVICE_PROFILE) == category } }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
            CategoryOverview(category, members.size, topics)
        }
    return files to categories
}
