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

package li.mercury.bigbrother.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.datastore.UserPreferencesDataSource
import li.mercury.bigbrother.core.model.ThemeMode
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DataStoreUserDataRepositoryTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun givenEmptyStore_whenRead_thenReturnsDefaults() = runTest {
        val repository = repository(File(directory.root, "settings.preferences_pb"), backgroundScope)

        assertEquals(UserData(themeMode = ThemeMode.SYSTEM), repository.userData.first())
    }

    @Test
    fun givenSettingsWrites_whenStoreReopens_thenAllPreferencesPersist() = runTest {
        val file = File(directory.root, "settings.preferences_pb")
        val firstStore = Job(backgroundScope.coroutineContext[Job])
        val repository = repository(file, CoroutineScope(backgroundScope.coroutineContext + firstStore))
        repository.setTimeRange(TimeRange.ALL)
        repository.setShowSystemApps(true)
        repository.setUseDynamicColor(false)
        repository.setThemeMode(ThemeMode.DARK)
        val saved = UserData(timeRange = TimeRange.ALL, showSystemApps = true, useDynamicColor = false, themeMode = ThemeMode.DARK)
        assertEquals(saved, repository.userData.first())
        firstStore.cancelAndJoin()

        val reopened = repository(file, backgroundScope)
        assertEquals(saved, reopened.userData.first())
    }

    @Test
    fun givenCorruptPreferencesFile_whenRead_thenReturnsDefaults() = runTest {
        val file = File(directory.root, "settings.preferences_pb")
        file.writeBytes(byteArrayOf(0x0A, 0x05, 0x01))
        val repository = repository(file, backgroundScope)

        assertEquals(UserData(), repository.userData.first())
    }

    @Test
    fun givenLightTheme_whenReset_thenFollowsSystemAndKeepsOtherSettings() = runTest {
        val repository = repository(File(directory.root, "settings.preferences_pb"), backgroundScope)
        repository.setUseDynamicColor(false)
        repository.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repository.userData.first().themeMode)

        repository.setThemeMode(ThemeMode.SYSTEM)

        assertEquals(UserData(useDynamicColor = false, themeMode = ThemeMode.SYSTEM), repository.userData.first())
    }

    private fun repository(file: File, scope: CoroutineScope): UserDataRepository = DataStoreUserDataRepository(
        UserPreferencesDataSource(PreferenceDataStoreFactory.create(scope = scope) { file }),
    )
}
