package com.naslink.ui.screens

internal fun shouldPerformSelectionHaptic(
    selectedPaths: Set<String>,
    toggledPath: String,
): Boolean = selectedPaths.isEmpty() && toggledPath !in selectedPaths
