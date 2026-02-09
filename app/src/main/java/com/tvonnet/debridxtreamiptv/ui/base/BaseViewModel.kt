package com.tvonnet.debridxtreamiptv.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base ViewModel class following MVVM pattern
 * Provides common functionality for all ViewModels:
 * - State management with StateFlow
 * - Event handling
 * - Exception handling
 * 
 * @param STATE The UI state type
 * @param EVENT The UI event type
 */
abstract class BaseViewModel<STATE, EVENT> : ViewModel() {
    
    /**
     * Define the initial state for this ViewModel
     */
    protected abstract fun getInitialState(): STATE
    
    private val _uiState = MutableStateFlow(getInitialState())
    val uiState: StateFlow<STATE> = _uiState.asStateFlow()
    
    /**
     * Update the UI state immutably
     * @param reducer Function to produce new state from current state
     */
    protected fun updateState(reducer: STATE.() -> STATE) {
        _uiState.value = _uiState.value.reducer()
    }
    
    /**
     * Coroutine exception handler for viewModelScope
     * Delegates to handleException() method
     */
    protected val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        handleException(exception)
    }
    
    /**
     * Handle exceptions from coroutines
     * Override in child classes to provide custom error handling
     */
    protected open fun handleException(exception: Throwable) {
        // Default: do nothing
        // Child classes should override this method
    }
    
    /**
     * Handle UI events
     * @param event The event to handle
     */
    abstract fun onEvent(event: EVENT)
}

