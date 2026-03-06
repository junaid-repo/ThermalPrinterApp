package com.example.thermalprinterapp

import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.widget.Toast
import org.json.JSONObject
import androidx.biometric.BiometricManager

class BiometricHelper(private val activity: AppCompatActivity) {

    private val PREF_FILE = "secure_app_prefs"

    // ✅ SAVE CREDENTIALS (Username & Password)
    fun saveCredentials(username: String, password: String) {
        try {
            val masterKey = MasterKey.Builder(activity)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                activity,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            sharedPreferences.edit()
                .putString("saved_username", username)
                .putString("saved_password", password)
                .apply()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasSavedCredentials(): Boolean {
        return retrieveCredentials() != null
    }

    // ✅ AUTHENTICATE & RETURN CREDENTIALS
    // ✅ AUTHENTICATE & RETURN CREDENTIALS
    fun authenticate(onSuccess: (String, String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)

        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)

                    // Retrieve Creds
                    val creds = retrieveCredentials()
                    if (creds != null) {
                        onSuccess(creds.first, creds.second)
                    } else {
                        Toast.makeText(activity, "No saved login found", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Optional: You might want to ignore the "User canceled" error so it doesn't show a toast
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                        Toast.makeText(activity, "Auth Error: $errString", Toast.LENGTH_SHORT).show()
                    }
                }
            })

        // 🟢 UPDATED: Allow Biometric OR Device Credential (PIN/Pattern/Password)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Secure Login")
            .setSubtitle("Log in using your biometric or screen lock")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            // ⚠️ DO NOT add .setNegativeButtonText("Cancel") here. It will crash the app!
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun retrieveCredentials(): Pair<String, String>? {
        return try {
            val masterKey = MasterKey.Builder(activity)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                activity,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val u = sharedPreferences.getString("saved_username", null)
            val p = sharedPreferences.getString("saved_password", null)

            if (u != null && p != null) Pair(u, p) else null
        } catch (e: Exception) {
            null
        }
    }
}