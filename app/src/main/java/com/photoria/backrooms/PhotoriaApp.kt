package com.photoria.backrooms

import android.app.Application
import android.content.Context

class PhotoriaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}
