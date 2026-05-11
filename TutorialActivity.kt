package com.example.rezeptmoment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity

class TutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        val headerLabel: TextView = findViewById(R.id.headerLabel)
        val textLabel: TextView = findViewById(R.id.textLabel)

        val button: MaterialButton = findViewById(R.id.tutorialButtonActivity)

        // ✅ Strings from XML resources
        headerLabel.text = getString(R.string.tutorial_header)
        textLabel.text = getString(R.string.tutorial_text)


        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val markShown: () -> Unit = {
            prefs.edit().putInt("didShowWelcomeTutorial", 1).apply()
        }

        button.setOnClickListener {
            markShown()
            finish()
        }

        onBackPressedDispatcher.addCallback(this) {
            markShown()
            finish()
        }
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, TutorialActivity::class.java)
            context.startActivity(intent)
        }
    }
}
