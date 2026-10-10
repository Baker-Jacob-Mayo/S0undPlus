package com.s0undplus.tv.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A coroutine scope tied to this screen's time on the back stack: cancelled when the screen leaves composition, so
 * chat sockets and pending requests never outlive the screen that started them.
 */
@Composable
fun rememberScreenScope(): CoroutineScope {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CrashGuard) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    return scope
}
