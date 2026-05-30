package com.capstone.toma.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

interface MultipleClickHandler {
    fun processClick(onClick: () -> Unit)
}

class MultipleClickHandlerImpl(private val delay: Long = 500L) : MultipleClickHandler {
    private var lastClickTime: Long = 0

    override fun processClick(onClick: () -> Unit) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime > delay) {
            lastClickTime = currentTime
            onClick()
        }
    }
}

fun Modifier.debouncedClickable(
    enabled: Boolean = true,
    delay: Long = 500L,
    onClick: () -> Unit
): Modifier = composed {
    val handler = rememberMultipleClickHandler(delay)
    this.clickable(enabled = enabled) {
        handler.processClick(onClick)
    }
}

@Composable
fun rememberMultipleClickHandler(delay: Long = 500L): MultipleClickHandler {
    return remember { MultipleClickHandlerImpl(delay) }
}
