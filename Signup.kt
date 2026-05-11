package com.example.rezeptmoment.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.MainActivity
import com.example.rezeptmoment.R

import com.example.rezeptmoment.data.User
import kotlinx.coroutines.launch
import java.security.MessageDigest

class SignUp : AppCompatActivity() {
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_signup)

        // ✅ Initialize Room database
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "rezeptmoment.db"
        ).build()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val loginText: TextView = findViewById(R.id.txtLogin)
        loginText.setOnClickListener {
            startActivity(Intent(this, Login::class.java))
            finish()
        }

        val registerButton: Button = findViewById(R.id.btnSign)
        registerButton.setOnClickListener {
            performSignUp()
        }
    }

    private fun performSignUp() {
        val email = findViewById<EditText>(R.id.edtEmail)
        val password = findViewById<EditText>(R.id.edtPassword)

        val inputEmail = email.text.toString().trim()
        val inputPassword = password.text.toString().trim()

        if (inputEmail.isEmpty() || inputPassword.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(inputEmail).matches()) {
            Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
            return
        }

        if (inputPassword.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ Real Room database signup
        lifecycleScope.launch {
            try {
                val normalizedEmail = inputEmail.lowercase().trim()
                Log.d("SIGNUP", "Creating user: $inputEmail")

                // Check if user exists
                val existingUser = db.userDao().getUserByEmail(inputEmail.lowercase())
                if (existingUser != null) {
                    Log.d("SIGNUP", "❌ User already exists")
                    Toast.makeText(this@SignUp, "User already exists", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Create and save user
                val passwordHash = hashPassword(inputPassword)
                val user = User(
                    email = inputEmail.lowercase(),
                    passwordHash = passwordHash,
                    createdAt = System.currentTimeMillis()
                )

                db.userDao().insertUser(user)
                Log.d("SIGNUP", "✅ User created successfully: ${user.email}")
                saveSession(normalizedEmail)

                Toast.makeText(this@SignUp, "Account created successfully", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@SignUp, MainActivity::class.java))
                finish()

            } catch (e: Exception) {
                Log.e("SIGNUP", "Signup failed", e)
                Toast.makeText(this@SignUp, "Signup failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSession(email: String) {
        val prefs= getSharedPreferences("RezeptmomentPrefs", MODE_PRIVATE)
        prefs.edit().apply {putString("USER_EMAIL", email)
            putBoolean("IS_LOGGED_IN", true)
            apply()
        }
    }

    // ✅ Password hashing (SHA-256) - matches Login.kt
    private fun hashPassword(password: String): String {
        val bytes = password.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}