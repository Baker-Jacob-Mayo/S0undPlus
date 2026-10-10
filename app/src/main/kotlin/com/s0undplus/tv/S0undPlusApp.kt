package com.s0undplus.tv

import android.app.Application

class S0undPlusApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
