package com.s0undplus.tv

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

open class S0undPlusApp : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { createContainer() }

    /** Instrumented tests substitute a container wired to a local fake server; the app always uses the real one. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    /** Shares the app's OkHttp client and can decode the animated GIF/WebP emotes that BTTV and 7TV serve. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
}
