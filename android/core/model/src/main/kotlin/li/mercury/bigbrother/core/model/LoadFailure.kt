package li.mercury.bigbrother.core.model

/** Why records can't be shown. */
sealed interface LoadFailure {
    /** The app has not been granted root. */
    data object NoRoot : LoadFailure

    /** The KernelSU module, and with it the daemon, is not installed. */
    data object ModuleMissing : LoadFailure

    data class Other(val message: String) : LoadFailure
}
