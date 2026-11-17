package li.mercury.bigbrother.core.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * Shows entries marked with [bottomSheet] in a modal bottom sheet above the
 * screen they were opened from, while [enabled]. Material uses bottom sheets
 * in compact and medium windows only; in wider ones the strategy steps aside
 * and the entry is laid out like any other, typically as an extra pane.
 */
class BottomSheetSceneStrategy<T : Any>(private val enabled: Boolean) : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!enabled) return null
        val last = entries.lastOrNull() ?: return null
        if (last.metadata[BOTTOM_SHEET_KEY] != true) return null
        // A sheet needs a screen to sit on.
        val below = entries.dropLast(1).ifEmpty { return null }
        return BottomSheetScene(
            key = last.contentKey,
            previousEntries = below,
            overlaidEntries = below,
            entry = last,
            onBack = onBack,
        )
    }

    companion object {
        private const val BOTTOM_SHEET_KEY = "bottomSheet"

        /** Metadata marking an entry to be shown in a bottom sheet where the window allows. */
        fun bottomSheet(): Map<String, Any> = mapOf(BOTTOM_SHEET_KEY to true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private class BottomSheetScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val entry: NavEntry<T>,
    private val onBack: () -> Unit,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable () -> Unit = {
        // Dismissing the sheet, by swiping it away or with back, pops the entry.
        ModalBottomSheet(onDismissRequest = onBack) {
            entry.Content()
        }
    }

    // Navigation compares scenes to decide whether to animate between them.
    override fun equals(other: Any?): Boolean =
        other is BottomSheetScene<*> &&
            key == other.key &&
            entry == other.entry &&
            previousEntries == other.previousEntries &&
            overlaidEntries == other.overlaidEntries

    override fun hashCode(): Int =
        listOf(key, entry, previousEntries, overlaidEntries).hashCode()
}
