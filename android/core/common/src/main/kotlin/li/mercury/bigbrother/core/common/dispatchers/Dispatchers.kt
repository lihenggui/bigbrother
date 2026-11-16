package li.mercury.bigbrother.core.common.dispatchers

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.RUNTIME

/** Selects which coroutine dispatcher to inject. */
@Qualifier
@Retention(RUNTIME)
annotation class Dispatcher(val dispatcher: BigBrotherDispatchers)

enum class BigBrotherDispatchers {
    IO,
    DEFAULT,
}
