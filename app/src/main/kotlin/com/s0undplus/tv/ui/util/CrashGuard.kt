package com.s0undplus.tv.ui.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Last line of defence for the app's long-lived coroutine scopes. An exception that escapes a `launch` in a scope
 * without a handler goes to the thread's uncaught-exception handler and kills the process; this logs it and lets the
 * app carry on. It is a safety net, not a licence to stop catching exceptions where they can be handled.
 *
 * Only the exception's class and its first stack frame are logged: messages can carry URLs or response bodies.
 */
val CrashGuard = CoroutineExceptionHandler { _, e ->
    Log.e("S0undPlus", "Unhandled coroutine failure (${e.javaClass.name}) at ${e.stackTrace.firstOrNull()}")
}
