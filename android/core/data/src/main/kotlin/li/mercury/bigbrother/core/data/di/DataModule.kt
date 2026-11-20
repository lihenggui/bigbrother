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

package li.mercury.bigbrother.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.DataStoreUserDataRepository
import li.mercury.bigbrother.core.data.repository.DiagnosticsRepository
import li.mercury.bigbrother.core.data.repository.FileAccessRepository
import li.mercury.bigbrother.core.data.repository.FirstLaunchRepository
import li.mercury.bigbrother.core.data.repository.InstalledAppRepository
import li.mercury.bigbrother.core.data.repository.LocalFirstLaunchRepository
import li.mercury.bigbrother.core.data.repository.ModuleRepository
import li.mercury.bigbrother.core.data.repository.NetworkRepository
import li.mercury.bigbrother.core.data.repository.PackageManagerAppInfoRepository
import li.mercury.bigbrother.core.data.repository.PackageManagerInstalledAppRepository
import li.mercury.bigbrother.core.data.repository.RootAccessRepository
import li.mercury.bigbrother.core.data.repository.RootFileAccessRepository
import li.mercury.bigbrother.core.data.repository.RootModuleRepository
import li.mercury.bigbrother.core.data.repository.RootNetworkRepository
import li.mercury.bigbrother.core.data.repository.RootSandboxRepository
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.data.repository.ZipDiagnosticsRepository

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindsAccessRepository(repository: RootAccessRepository): AccessRepository

    @Binds
    abstract fun bindsFileAccessRepository(
        repository: RootFileAccessRepository,
    ): FileAccessRepository

    @Binds
    abstract fun bindsFirstLaunchRepository(repository: LocalFirstLaunchRepository): FirstLaunchRepository

    @Binds
    abstract fun bindsModuleRepository(repository: RootModuleRepository): ModuleRepository

    @Binds
    abstract fun bindsDiagnosticsRepository(repository: ZipDiagnosticsRepository): DiagnosticsRepository

    @Binds
    abstract fun bindsUserDataRepository(
        repository: DataStoreUserDataRepository,
    ): UserDataRepository

    @Binds
    abstract fun bindsAppInfoRepository(
        repository: PackageManagerAppInfoRepository,
    ): AppInfoRepository

    @Binds
    abstract fun bindsSandboxRepository(repository: RootSandboxRepository): SandboxRepository

    @Binds
    abstract fun bindsNetworkRepository(repository: RootNetworkRepository): NetworkRepository

    @Binds
    abstract fun bindsInstalledAppRepository(
        repository: PackageManagerInstalledAppRepository,
    ): InstalledAppRepository
}
