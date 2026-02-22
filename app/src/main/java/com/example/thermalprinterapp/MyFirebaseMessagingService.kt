package com.example.thermalprinterapp

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    // 1. This runs automatically when the app is first installed/opened
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New Token Generated: $token")

        // TODO: Call your Spring Boot API here to save this token!
        // Example: RetrofitClient.api.saveToken(token)
    }

    // 2. This runs when a message arrives while the app is OPEN (Foreground)
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Show the notification manually if the app is open
        remoteMessage.notification?.let {
            Log.d("FCM", "Title: ${it.title}, Body: ${it.body}")
            // Create code here to show a Pop-up / Notification Bar
        }
    }
}