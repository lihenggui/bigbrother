package li.mercury.bigbrother.core.navigation

import androidx.navigation3.runtime.NavKey

/**
 * Handles navigation events (forward and back) by updating [state].
 *
 * Follows the navigation design of Now in Android: each top-level
 * destination owns a back stack, and the stacks of the top-level
 * destinations visited form the overall back stack.
 */
class Navigator(val state: NavigationState) {

    /** Go to [key], which may be a top-level destination or a screen above one. */
    fun navigate(key: NavKey) {
        when (key) {
            // Re-selecting the current top-level destination returns to its root.
            state.currentTopLevelKey -> clearSubStack()
            in state.topLevelKeys -> goToTopLevel(key)
            else -> goToKey(key)
        }
    }

    /**
     * Go back one screen. Does nothing on the start destination: a screen
     * that is animating out can still deliver a second "back".
     */
    fun goBack() {
        when (state.currentKey) {
            state.startKey -> Unit
            state.currentTopLevelKey -> state.topLevelStack.removeLastOrNull()
            else -> state.currentSubStack.removeLastOrNull()
        }
    }

    private fun goToKey(key: NavKey) {
        // Moving an already-open screen to the top avoids duplicates.
        state.currentSubStack.apply {
            remove(key)
            add(key)
        }
    }

    private fun goToTopLevel(key: NavKey) {
        state.topLevelStack.apply {
            if (key == state.startKey) clear() else remove(key)
            add(key)
        }
    }

    private fun clearSubStack() {
        state.currentSubStack.run {
            if (size > 1) subList(1, size).clear()
        }
    }
}
