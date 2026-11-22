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

import li.mercury.bigbrother.core.model.SandboxHookState
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxPart
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemsTest {
    @Test
    fun givenDifferentHookStates_whenAnItemIsUnavailable_thenOnlyMissingScopeSuggestsLSPosed() {
        val item = SandboxItem.DEVICE_IDS
        val part = SandboxPart.ANDROID_ID_HOOK
        val unmet = setOf(part, SandboxPart.APP_OPS)
        assertEquals(R.string.jail_not_in_effect_hook, notInEffect(item, unmet, mapOf(part to SandboxHookState.NOT_LOADED)))
        assertEquals(R.string.jail_hook_incompatible, notInEffect(item, unmet, mapOf(part to SandboxHookState.INCOMPATIBLE)))
        assertEquals(R.string.jail_hook_degraded, notInEffect(item, unmet, mapOf(part to SandboxHookState.DEGRADED)))
        assertEquals(R.string.jail_hook_unavailable, notInEffect(item, unmet, emptyMap()))
    }

    @Test
    fun givenMissingAndDegradedHooks_whenAnItemNeedsBoth_thenShowsDegradation() {
        val health = mapOf(
            SandboxPart.ANDROID_ID_HOOK to SandboxHookState.NOT_LOADED,
            SandboxPart.AD_ID_HOOK to SandboxHookState.DEGRADED,
        )
        assertEquals(R.string.jail_hook_degraded, notInEffect(SandboxItem.DEVICE_IDS, health.keys, health))
    }
}
