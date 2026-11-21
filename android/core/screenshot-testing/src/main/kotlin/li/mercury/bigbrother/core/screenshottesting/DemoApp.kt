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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.AppInfo
import java.util.Locale

/**
 * The apps of the demo device the screenshots show. All are made up: the
 * packages are under example.com, which is reserved for examples, and no
 * name or icon is a real app's. System apps and an isolated process are
 * among them, as on a real device, where the screens leave them out.
 *
 * Each has a launcher icon of its own, drawn by [DemoAppIcons]: a disc in
 * a gradient from [topColor] to [bottomColor], with [glyph] in [glyphColor].
 */
enum class DemoApp(
    val uid: Int,
    val packageName: String,
    private val label: String,
    private val chineseLabel: String,
    internal val glyph: ImageVector,
    internal val topColor: Long,
    internal val bottomColor: Long,
    internal val glyphColor: Long = WHITE,
    val isSystem: Boolean = false,
) {
    FLASHLIGHT(10231, "com.example.flashlight", "Flashlight", "手电筒", Icons.Filled.FlashlightOn, 0xFF546E7A, 0xFF263238, 0xFFFFD54F),
    WEATHER(10187, "com.example.weather", "Weather", "天气", Icons.Filled.WbSunny, 0xFF4FC3F7, 0xFF1E88E5, 0xFFFFF59D),
    SHOPPING(10204, "com.example.shopping", "Shopping", "购物", Icons.Filled.ShoppingBag, 0xFFFF8A65, 0xFFE64A19),
    NOTES(10156, "com.example.notes", "Notes", "便签", Icons.Filled.EditNote, 0xFFFFE082, 0xFFFFB300, 0xFF5D4037),
    PHOTO_EDITOR(10172, "com.example.photoeditor", "Photo Editor", "修图", Icons.Filled.AutoFixHigh, 0xFFCE93D8, 0xFF8E24AA),
    CHAT(10143, "com.example.chat", "Chat", "聊天", Icons.Filled.Forum, 0xFF81C784, 0xFF388E3C),
    STEP_COUNTER(10198, "com.example.steps", "Step Counter", "计步器", Icons.AutoMirrored.Filled.DirectionsRun, 0xFF4DB6AC, 0xFF00796B),
    KEYBOARD(10120, "com.example.keyboard", "Keyboard", "输入法", Icons.Filled.Keyboard, 0xFF90A4AE, 0xFF455A64),
    NEWS(10215, "com.example.news", "Daily News", "每日新闻", Icons.Filled.Newspaper, 0xFFE57373, 0xFFC62828),
    PUZZLE(10240, "com.example.puzzle", "Block Puzzle", "方块拼图", Icons.Filled.Extension, 0xFF7986CB, 0xFF303F9F),
    MEDIA_INDEXER(10045, "com.example.system.mediaindexer", "Media Indexer", "媒体索引", Icons.Filled.PermMedia, 0xFFA1887F, 0xFF5D4037, isSystem = true),
    BACKUP(10031, "com.example.system.backup", "Backup", "备份", Icons.Filled.Backup, 0xFF64B5F6, 0xFF1565C0, isSystem = true),
    ;

    /** The app as the package manager would describe it, labeled in [locale]'s language. */
    fun appInfo(locale: Locale): AppInfo = AppInfo(
        label = if (locale.language == Locale.CHINESE.language) chineseLabel else label,
        iconSource = AppIconSource(packageName, uid, lastUpdateTime = 0),
        isSystem = isSystem,
    )

    companion object {
        internal fun of(packageName: String): DemoApp? = entries.firstOrNull { it.packageName == packageName }
    }
}

private const val WHITE = 0xFFFFFFFF
