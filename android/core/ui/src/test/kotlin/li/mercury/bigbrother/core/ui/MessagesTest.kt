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

import li.mercury.bigbrother.core.model.LoadFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {
    @Test
    fun givenMissingRoot_whenMapped_thenShowsRootTitleAndExplanation() {
        assertEquals(R.string.no_root_title, failureTitleId(LoadFailure.NoRoot))
        assertEquals(R.string.no_root_body, failureBodyId(LoadFailure.NoRoot))
    }

    @Test
    fun givenTimedOutCommand_whenMapped_thenShowsSpecificExplanation() {
        assertEquals(R.string.error_title, failureTitleId(LoadFailure.Operation.TIMED_OUT))
        assertEquals(R.string.timed_out, failureBodyId(LoadFailure.Operation.TIMED_OUT))
    }
}
