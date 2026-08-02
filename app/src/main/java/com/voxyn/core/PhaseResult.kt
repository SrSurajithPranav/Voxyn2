package com.voxyn.core

sealed class PhaseResult<out T> {
    data class Success<T>(val value: T) : PhaseResult<T>()
    data class Failure(
        val error: String,
        val canFallback: Boolean = false
    ) : PhaseResult<Nothing>()
}
