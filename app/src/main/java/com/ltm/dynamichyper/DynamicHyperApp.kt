package com.ltm.dynamichyper

import android.app.Application
import android.util.Log

class DynamicHyperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "DynamicHyperApp initialized successfully")
    }

    companion object {
        const val TAG = "DynamicHyper"
        lateinit var instance: DynamicHyperApp
            private set
    }
}

