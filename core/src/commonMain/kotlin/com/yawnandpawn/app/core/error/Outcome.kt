package com.yawnandpawn.app.core.error

/**
 * The result of an operation that can fail in an expected way (AD-12): ports and use cases return this instead of
 * throwing. [E] is usually [DomainError].
 */
sealed interface Outcome<out T, out E> {
    data class Success<out T>(
        val value: T,
    ) : Outcome<T, Nothing>

    data class Failure<out E>(
        val error: E,
    ) : Outcome<Nothing, E>
}

/** The value on success, `null` on failure. */
fun <T, E> Outcome<T, E>.valueOrNull(): T? = (this as? Outcome.Success)?.value

/** The error on failure, `null` on success. */
fun <T, E> Outcome<T, E>.errorOrNull(): E? = (this as? Outcome.Failure)?.error

/** Transforms the success value; a failure passes through unchanged. */
inline fun <T, R, E> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> =
    when (this) {
        is Outcome.Success -> Outcome.Success(transform(value))
        is Outcome.Failure -> this
    }

/** Chains another operation that can fail; a failure passes through unchanged. */
inline fun <T, R, E> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> =
    when (this) {
        is Outcome.Success -> transform(value)
        is Outcome.Failure -> this
    }
