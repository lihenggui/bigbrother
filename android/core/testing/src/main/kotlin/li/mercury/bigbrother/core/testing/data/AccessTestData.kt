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

package li.mercury.bigbrother.core.testing.data

import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category

fun testAccess(
    path: String,
    action: Action = Action.READ,
    category: Category = Category.STORAGE,
    allowed: Int = 1,
    denied: Int = 0,
    lastSeen: Long = 0,
): Access = Access(
    category = category,
    path = path,
    action = action,
    viaProvider = false,
    items = 0,
    allowed = allowed,
    denied = denied,
    lastSeen = lastSeen,
)
