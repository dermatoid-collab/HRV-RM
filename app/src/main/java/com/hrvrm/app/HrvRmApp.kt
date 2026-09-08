package com.hrvrm.app

import android.app.Application
import com.hrvrm.app.data.AppContainer

class HrvRmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
