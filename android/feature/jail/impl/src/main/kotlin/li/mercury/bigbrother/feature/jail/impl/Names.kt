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

package li.mercury.bigbrother.feature.jail.impl

import java.text.Collator

/**
 * In the order of the names [name] gives, as the user's language sorts
 * them: Chinese names by their pinyin, not by their code points.
 */
internal fun <T> List<T>.sortedByName(collator: Collator = Collator.getInstance(), name: (T) -> String): List<T> = sortedWith(compareBy(collator, name))
