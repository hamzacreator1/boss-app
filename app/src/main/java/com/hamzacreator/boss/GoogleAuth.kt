package com.hamzacreator.boss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import java.net.URL

/**
 * Google sign-in for identity/personalization + the ✨ PRO badge.
 * Basic profile sign-in needs NO google-services.json and NO SHA-1.
 * Signed-in = PRO. There is NO credits system anywhere in this app.
 */
object GoogleAuth {

    fun client(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /** Cached account = silent sign-in on launch. Null when signed out. */
    fun account(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(context: Context): Boolean = account(context) != null

    fun displayName(context: Context): String =
        account(context)?.displayName.orEmpty()

    fun signOut(context: Context, done: () -> Unit) {
        try {
            client(context).signOut().addOnCompleteListener { done() }
        } catch (_: Exception) {
            done()
        }
    }

    /** Downloads the profile photo off the main thread. Null on any failure. */
    fun loadPhotoBitmap(context: Context, onDone: (Bitmap?) -> Unit) {
        val url = account(context)?.photoUrl?.toString()
        if (url.isNullOrEmpty()) {
            onDone(null)
            return
        }
        Thread {
            val bmp = try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(bmp) }
        }.start()
    }
}
