package de.asdfghasdfgh.sokuseki

import android.app.Application

class SokusekiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StatusStore.init(this)
    }
}
