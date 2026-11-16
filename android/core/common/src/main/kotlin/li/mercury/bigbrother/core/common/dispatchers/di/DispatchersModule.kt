package li.mercury.bigbrother.core.common.dispatchers.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @Dispatcher(IO)
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(DEFAULT)
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
