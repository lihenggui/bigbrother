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

package li.mercury.bigbrother.core.data.bbd

import kotlinx.coroutines.Dispatchers
import li.mercury.bigbrother.core.data.root.CommandResult
import li.mercury.bigbrother.core.data.root.RootShell

internal class TestBbdRootShell(private val answer: (label: String) -> CommandResult) : RootShell(Dispatchers.Unconfined) {
    constructor(output: String, error: String? = null) : this({
        if (error == null) {
            CommandResult(isSuccess = true, code = 0, output = output, error = "")
        } else {
            CommandResult(isSuccess = false, code = 2, output = "", error = error)
        }
    })

    val lines = mutableListOf<String>()
    val labels = mutableListOf<String>()

    override suspend fun run(command: String, label: String): CommandResult {
        lines += command
        labels += label
        return answer(label)
    }
}
