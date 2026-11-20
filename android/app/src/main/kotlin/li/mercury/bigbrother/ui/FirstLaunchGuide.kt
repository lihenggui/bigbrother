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

package li.mercury.bigbrother.ui

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import li.mercury.bigbrother.R

internal enum class GuidePage(val title: Int, val body: Int) {
    Access(R.string.guide_access_title, R.string.guide_access_body),
    Timeline(R.string.guide_timeline_title, R.string.guide_timeline_body),
    Jail(R.string.guide_jail_title, R.string.guide_jail_body),
    Framework(R.string.guide_framework_title, R.string.guide_framework_body),
}

@Composable
internal fun FirstLaunchGuide(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    pager: PagerState = rememberPagerState { GuidePage.entries.size },
) {
    val scope = rememberCoroutineScope()
    val page = pager.settledPage
    val last = pager.pageCount - 1
    fun navigate(target: Int) {
        scope.launch {
            if (ValueAnimator.areAnimatorsEnabled()) pager.animateScrollToPage(target) else pager.scrollToPage(target)
        }
    }
    BackHandler { if (page == 0) onDismiss() else navigate(page - 1) }

    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().wrapContentWidth().widthIn(max = 1120.dp).fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    null,
                    Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentScale = ContentScale.Crop,
                )
                Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.guide_skip)) }
            }
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)) {
                val sideBySide = maxWidth >= 740.dp || (maxWidth >= 480.dp && maxHeight < 420.dp)
                var playedOpening by rememberSaveable { mutableStateOf(false) }
                val art: @Composable (Modifier) -> Unit = { area ->
                    GuideIllustration(
                        pager,
                        playedOpening,
                        { playedOpening = true },
                        area.scrollable(
                            state = pager,
                            orientation = Orientation.Horizontal,
                            reverseDirection = LocalLayoutDirection.current == LayoutDirection.Ltr,
                            flingBehavior = PagerDefaults.flingBehavior(pager),
                        ),
                    )
                }
                if (sideBySide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        art(Modifier.weight(1f).heightIn(max = 540.dp).fillMaxHeight())
                        GuideCopy(pager, Alignment.CenterVertically, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        art(Modifier.fillMaxWidth().height((this@BoxWithConstraints.maxHeight * .52f).coerceAtMost(440.dp)))
                        GuideCopy(pager, Alignment.Top, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) {
                    OutlinedIconButton(onClick = { navigate(page - 1) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.guide_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (page == last) onDismiss() else navigate(page + 1) }, modifier = Modifier.heightIn(min = 52.dp)) {
                    Text(stringResource(if (page == last) R.string.guide_start else R.string.guide_next))
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun GuideCopy(pager: PagerState, alignment: Alignment.Vertical, modifier: Modifier = Modifier) {
    HorizontalPager(
        state = pager,
        modifier = modifier,
        verticalAlignment = alignment,
    ) { index ->
        val page = GuidePage.entries[index]
        val position = stringResource(R.string.guide_progress, index + 1, pager.pageCount)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Row(Modifier.semantics { contentDescription = position }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                GuidePage.entries.forEach {
                    Box(
                        Modifier.size(if (it == page) 28.dp else 8.dp, 8.dp)
                            .background(if (it == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(page.title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(page.body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
