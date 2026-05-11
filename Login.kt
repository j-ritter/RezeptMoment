package com.example.rezeptmoment.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
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

class Login : AppCompatActivity() {
    private lateinit var db: AppDatabase
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        // ✅ Initialize Room database
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "rezeptmoment.db"
        ).build()

        sharedPreferences = getSharedPreferences("RezeptmomentPrefs", MODE_PRIVATE)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Skip login if already logged in
        if (isUserLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Register link
        val registerText: TextView = findViewById(R.id.txtSign)
        registerText.setOnClickListener {
            startActivity(Intent(this, SignUp::class.java))
        }

        // Forgot password
        val forgotPasswordText: TextView = findViewById(R.id.txtForgotPassword)
        forgotPasswordText.setOnClickListener {
            Toast.makeText(this, "Contact support for password reset", Toast.LENGTH_SHORT).show()
        }

        // Login button
        val loginButton: Button = findViewById(R.id.btnlog)
        loginButton.setOnClickListener {
            performLogin()
        }
    }

    private fun performLogin() {
        val emailEdit: EditText = findViewById(R.id.edtEmail)
        val passwordEdit: EditText = findViewById(R.id.edtPassword)

        val emailInput = emailEdit.text.toString().trim()
        val passwordInput = passwordEdit.text.toString().trim()

        if (emailInput.isEmpty() || passwordInput.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ Real Room database login
        lifecycleScope.launch {
            try {
                Log.d("LOGIN", "Attempting login for: $emailInput")
                val user = db.userDao().getUserByEmail(emailInput.lowercase())

                if (user != null) {
                    Log.d("LOGIN", "User found: ${user.email}")
                    val passwordHash = hashPassword(passwordInput)
                    if (verifyPassword(passwordInput, user.passwordHash)) {
                        Log.d("LOGIN", "✅ Password correct!")
                        saveLogin(emailInput)
                        if (isFirstLogin()) {
                            showWelcomeDialog(emailInput)
                        } else {
                            startActivity(Intent(this@Login, MainActivity::class.java))
                            finish()
                        }
                    } else {
                        Log.d("LOGIN", "❌ Password mismatch")
                        Toast.makeText(this@Login, "Invalid password", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.d("LOGIN", "❌ User not found")
                    Toast.makeText(this@Login, "User not found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("LOGIN", "Login error", e)
                Toast.makeText(this@Login, "Login failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ✅ Password hashing (SHA-256)
    private fun hashPassword(password: String): String {
        val bytes = password.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    private fun verifyPassword(input: String, storedHash: String): Boolean {
        return hashPassword(input) == storedHash
    }

    private fun saveLogin(email: String) {
        val normalizedEmail = email.trim().lowercase()
        val editor = sharedPreferences.edit()
        editor.putString("USER_EMAIL", normalizedEmail)
        editor.putBoolean("IS_LOGGED_IN", true)
        editor.putBoolean("isFirstLogin", false)
        editor.apply()
        Log.d("LOGIN", "User session saved: $email")
    }

    private fun isUserLoggedIn(): Boolean {
        return sharedPreferences.getBoolean("IS_LOGGED_IN", false)
    }

    private fun isFirstLogin(): Boolean {
        return sharedPreferences.getBoolean("isFirstLogin", true)
    }

    private fun showWelcomeDialog(email: String) {
        AlertDialog.Builder(this)
            .setTitle("Welcome to Rezeptmoment!")
            .setMessage("You're all set! Start planning your meals.")
            .setPositiveButton("Get Started") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .setCancelable(false)
            .show()
    }
}