package com.s0undplus.tv

import com.s0undplus.tv.fake.FakeTwitch

/** The real app, except that its container is wired to [FakeTwitch]. */
class TestApp : S0undPlusApp() {
    override fun createContainer(): AppContainer {
        FakeTwitch.start()
        return AppContainer(
            context = this,
            http = FakeTwitch.httpClient(),
            config = FakeTwitch.config(),
            updateApiUrl = FakeTwitch.baseUrl,
        )
    }
}
