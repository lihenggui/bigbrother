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

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

internal inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = List(length()) { transform(getJSONObject(it)) }

internal inline fun <T : Any> JSONArray.mapObjectsNotNull(transform: (JSONObject) -> T?): List<T> = (0 until length()).mapNotNull { transform(getJSONObject(it)) }

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

internal fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)
