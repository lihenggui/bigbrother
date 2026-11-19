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

package li.mercury.bigbrother.core.screenshottesting

import li.mercury.bigbrother.core.model.LoadFailure

/**
 * The failure the other screens that load records are captured with: the
 * one that shows a message of its own, the longest of them.
 */
val DemoLoadFailure: LoadFailure = LoadFailure.Other("database is locked")

/**
 * One of each reason records can't be shown. Every screen shows them with
 * the same message, so one screen, the app list, is captured with each;
 * its tests fail while a reason has no sample here.
 */
val DemoLoadFailures: List<LoadFailure> = listOf(
    LoadFailure.NoRoot,
    LoadFailure.ModuleMissing,
    LoadFailure.ModuleOutdated,
    DemoLoadFailure,
)
