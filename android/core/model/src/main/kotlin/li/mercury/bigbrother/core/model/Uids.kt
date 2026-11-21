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

package li.mercury.bigbrother.core.model

/** Android packs the user ID and app ID into one UID. */
const val PER_USER_RANGE = 100_000
const val FIRST_APPLICATION_UID = 10_000
private const val LAST_APPLICATION_UID = 19_999

fun userIdOf(uid: Int): Int = uid / PER_USER_RANGE

fun appIdOf(uid: Int): Int = uid % PER_USER_RANGE

/** Whether the UID is in Android's regular application range. */
fun isApplicationUid(uid: Int): Boolean = appIdOf(uid) in FIRST_APPLICATION_UID..LAST_APPLICATION_UID

/** The sandbox excludes core UIDs because system components may share them. */
fun isNonCoreUid(uid: Int): Boolean = appIdOf(uid) >= FIRST_APPLICATION_UID
