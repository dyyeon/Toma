package com.capstone.toma

import android.app.Application
import com.google.firebase.FirebaseApp

class TomaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Firebase
        FirebaseApp.initializeApp(this)
    }
}
