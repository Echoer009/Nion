package com.echonion.nion

import android.app.Application
import android.util.Log
import uniffi.nion_core.NionCore

class NionApp : Application() {
    lateinit var core: NionCore
        private set

    override fun onCreate() {
        super.onCreate()
        val dbPath = getDir("nion_data", MODE_PRIVATE).absolutePath + "/nion.db"
        Log.d("NionApp", "Database path: $dbPath")
        try {
            core = NionCore(dbPath)
            Log.d("NionApp", "NionCore initialized successfully")
        } catch (e: Exception) {
            Log.e("NionApp", "Failed to initialize NionCore", e)
        }
    }
}

fun Application.core(): NionCore = (this as NionApp).core
