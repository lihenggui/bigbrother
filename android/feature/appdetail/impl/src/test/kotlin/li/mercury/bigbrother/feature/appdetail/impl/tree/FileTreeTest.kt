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

package li.mercury.bigbrother.feature.appdetail.impl.tree

import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.testing.data.testAccess
import kotlin.test.Test
import kotlin.test.assertEquals

class FileTreeTest {
    private val root = "/storage/emulated/0"

    @Test
    fun givenChainOfUntouchedFolders_whenBuilt_thenCollapsesIntoOneRow() {
        val tree = buildFileTree(
            listOf(
                testAccess("$root/DCIM/Camera/a.jpg"),
                testAccess("$root/DCIM/Camera/b.jpg"),
                testAccess("$root/Download/x.pdf"),
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
    fun givenTouchedFolderInChain_whenBuilt_thenKeepsItsOwnRow() {
        val tree = buildFileTree(
            listOf(
                testAccess("$root/DCIM", Action.LIST),
                testAccess("$root/DCIM/Camera/a.jpg"),
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
    fun givenSeveralActionsOnOnePath_whenBuilt_thenShareOneNode() {
        val tree = buildFileTree(
            listOf(
                testAccess("$root/notes.txt", Action.PROBE),
                testAccess("$root/notes.txt", Action.READ),
            ),
        )
        val file = tree.single().children.single()
        assertEquals(listOf(Action.PROBE, Action.READ), file.accesses.map { it.action })
        assertEquals(1, tree.single().touchedCount)
    }

    @Test
    fun givenFoldersAndFiles_whenBuilt_thenFoldersFirstAndCaseIgnored() {
        val tree = buildFileTree(
            listOf(
                testAccess("$root/zeta.txt"),
                testAccess("$root/Alpha.txt"),
                testAccess("$root/music/a.mp3"),
                testAccess("$root/music/b.mp3"),
            ),
        )
        assertEquals(listOf("music", "Alpha.txt", "zeta.txt"), tree.single().children.map { it.name })
    }

    @Test
    fun givenPathsOnTwoVolumes_whenBuilt_thenEachVolumeIsARoot() {
        val tree = buildFileTree(
            listOf(testAccess("$root/a.txt"), testAccess("$root/b.txt"), testAccess("/storage/1A2B-3C4D/c.txt")),
        )
        assertEquals("/storage", tree.single().name)
        assertEquals(listOf("1A2B-3C4D", "emulated/0"), tree.single().children.map { it.name })
    }
}
