package com.tvonnet.debridxtreamiptv.ui.base

/**
 * Generic UI State wrapper for async operations
 * Represents different states of data loading:
 * - Idle: Initial state, no operation started
 * - Loading: Operation in progress
 * - Success: Operation completed successfully with data
 * - Error: Operation failed with error message
 * 
 * Usage example:
 * ```kotlin
 * data class MyUiState(
 *     val dataState: UiState<List<Item>> = UiState.Idle
 * )
 * 
 * // Update state
 * updateState { copy(dataState = UiState.Loading) }
 * // On success
 * updateState { copy(dataState = UiState.Success(items)) }
 * // On error
 * updateState { copy(dataState = UiState.Error("Failed to load")) }
 * ```
 */
sealed class UiState<out T> {
    /**
     * Initial state - no operation performed yet
     */
    object Idle : UiState<Nothing>()
    
    /**
     * Loading state - operation in progress
     */
    object Loading : UiState<Nothing>()
    
    /**
     * Success state - operation completed with data
     * @param data The result data
     */
    data class Success<T>(val data: T) : UiState<T>()
    
    /**
     * Error state - operation failed
     * @param message Error message to display
     * @param exception Optional exception for debugging
     */
    data class Error(
        val message: String,
        val exception: Throwable? = null
    ) : UiState<Nothing>()
}

