package com.example.registarappointmentsystem.core

/**
 * Simple UI wrapper for async operations.
 * - [isLoading]: true while an operation is in progress
 * - [data]: non-null on success
 * - [errorMessage]: non-null on failure
 */
data class UiResult<T>(
    val isLoading: Boolean = false,
    val data: T? = null,
    val errorMessage: String? = null
)

