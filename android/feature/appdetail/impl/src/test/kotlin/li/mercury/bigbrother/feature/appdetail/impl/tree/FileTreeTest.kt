package li.mercury.bigbrother.feature.appdetail.impl.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category

class FileTreeTest {
    private fun access(path: String, action: Action = Action.READ) = Access(
        category = Category.STORAGE,
        path = path,
        action = action,
        viaProvider = false,
        items = 0,
        allowed = 1,
        denied = 0,
        lastSeen = 0,
    )

    private val root = "/storage/emulated/0"

    @Test
    fun chainsOfEmptyFoldersCollapseIntoOneRow() {
        val tree = buildFileTree(
            listOf(
                access("$root/DCIM/Camera/a.jpg"),
                access("$root/DCIM/Camera/b.jpg"),
                access("$root/Download/x.pdf"),
            ),
        )
        // /storage, /emulated and /0 hold nothing else, so they share a row.
        assertEquals(listOf("/storage/emulated/0"), tree.map { it.name })
        val volume = tree.single()
        assertEquals(root, volume.path)
        assertEquals(3, volume.touchedCount)
        // DCIM only contains Camera, so those collapse too; Download does not
        // collapse into its file.
        assertEquals(listOf("DCIM/Camera", "Download"), volume.children.map { it.name })
        assertEquals(listOf("a.jpg", "b.jpg"), volume.children[0].children.map { it.name })
    }

    @Test
    fun aTouchedFolderIsNotCollapsedAway() {
        val tree = buildFileTree(
            listOf(
                access("$root/DCIM", Action.LIST),
                access("$root/DCIM/Camera/a.jpg"),
            ),
        )
        val dcim = tree.single().children.single()
        // DCIM was listed, so it keeps its own row with that access on it.
        assertEquals("DCIM", dcim.name)
        assertEquals(Action.LIST, dcim.accesses.single().action)
        assertEquals("Camera", dcim.children.single().name)
        // The listing itself and the file below it.
        assertEquals(2, tree.single().touchedCount)
    }

    @Test
    fun severalActionsOnOnePathShareANode() {
        val tree = buildFileTree(
            listOf(
                access("$root/notes.txt", Action.PROBE),
                access("$root/notes.txt", Action.READ),
            ),
        )
        val file = tree.single().children.single()
        assertEquals(listOf(Action.PROBE, Action.READ), file.accesses.map { it.action })
        assertEquals(1, tree.single().touchedCount)
    }

    @Test
    fun foldersSortBeforeFilesAndIgnoreCase() {
        val tree = buildFileTree(
            listOf(
                access("$root/zeta.txt"),
                access("$root/Alpha.txt"),
                access("$root/music/a.mp3"),
                access("$root/music/b.mp3"),
            ),
        )
        assertEquals(listOf("music", "Alpha.txt", "zeta.txt"), tree.single().children.map { it.name })
    }

    @Test
    fun separateVolumesAreSeparateRoots() {
        val tree = buildFileTree(
            listOf(access("$root/a.txt"), access("$root/b.txt"), access("/storage/1A2B-3C4D/c.txt")),
        )
        assertEquals("/storage", tree.single().name)
        assertEquals(listOf("1A2B-3C4D", "emulated/0"), tree.single().children.map { it.name })
    }
}
