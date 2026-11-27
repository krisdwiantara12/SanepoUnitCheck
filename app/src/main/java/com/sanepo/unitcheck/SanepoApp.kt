package com.sanepo.unitcheck

import android.app.Application
import com.sanepo.unitcheck.model.DiagnosticStore

class SanepoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticStore.ensureLoaded(this)
    }
}
