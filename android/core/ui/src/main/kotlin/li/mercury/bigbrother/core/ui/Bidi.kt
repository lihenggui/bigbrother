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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/** Keeps technical syntax left-to-right without affecting the surrounding sentence. */
fun isolateLtr(value: String): String = "\u2066$value\u2069"

/** App labels can use any script. A package name or shared UID used as a label is technical syntax. */
fun appLabelArgument(label: String, packageName: String): String = if (isTechnicalLabel(label, packageName)) isolateLtr(label) else label

/** Give standalone technical text the same start edge as the surrounding layout. */
@Composable
fun TextStyle.ltrContent(enabled: Boolean = true): TextStyle {
    if (!enabled) return this
    val alignment = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    return copy(textDirection = TextDirection.Ltr, textAlign = alignment)
}

/** Only a standalone package-name label needs the technical text style. */
@Composable
fun TextStyle.appLabelStyle(label: String, packageName: String, otherUser: Int? = null): TextStyle = ltrContent(otherUser == null && isTechnicalLabel(label, packageName))

/** Expose the text before display-only isolation to accessibility and text matching. */
fun Modifier.rawTextSemantics(displayText: String): Modifier {
    val raw = displayText.replace("\u2066", "").replace("\u2069", "")
    return if (displayText == raw) this else semantics { text = AnnotatedString(raw) }
}

private fun isTechnicalLabel(label: String, packageName: String): Boolean = packageName.isNotEmpty() && (label == packageName || label == packageName.removePrefix("@"))
