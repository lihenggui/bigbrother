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

package li.mercury.bigbrother.hook

import java.io.FileDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class HookInstallationsTest {
    @Test
    fun givenACloseFailure_whenClosingInstallationDescriptors_thenEachDescriptorStillGetsClosed() {
        val descriptors = List(4) { FileDescriptor() }
        val attempted = mutableListOf<FileDescriptor>()

        closeInstallationDescriptors(descriptors) { fd ->
            attempted += fd
            if (fd === descriptors[1]) throw AssertionError("close failed")
        }

        assertEquals(descriptors, attempted)
    }
}
