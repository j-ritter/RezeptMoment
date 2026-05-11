package com.example.rezeptmoment

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class PremiumActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Display the unlock premium fragment in the activity
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment_unlock_premium())
                .commit()
        }
    }
}
