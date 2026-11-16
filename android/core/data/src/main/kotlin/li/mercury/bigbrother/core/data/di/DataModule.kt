package li.mercury.bigbrother.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import li.mercury.bigbrother.core.data.repository.AccessRepository
import li.mercury.bigbrother.core.data.repository.AppInfoRepository
import li.mercury.bigbrother.core.data.repository.FileAccessRepository
import li.mercury.bigbrother.core.data.repository.ModuleRepository
import li.mercury.bigbrother.core.data.repository.PackageManagerAppInfoRepository
import li.mercury.bigbrother.core.data.repository.RootAccessRepository
import li.mercury.bigbrother.core.data.repository.RootFileAccessRepository
import li.mercury.bigbrother.core.data.repository.RootModuleRepository

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
    abstract fun bindsModuleRepository(repository: RootModuleRepository): ModuleRepository

    @Binds
    abstract fun bindsAppInfoRepository(
        repository: PackageManagerAppInfoRepository,
    ): AppInfoRepository
}
