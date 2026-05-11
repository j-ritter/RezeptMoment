package com.example.rezeptmoment

import android.os.Bundle
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var drawerLayout: DrawerLayout

    // NEW: Activity‑scoped UpcomingViewModel
    private val upcomingViewModel: UpcomingViewModel by lazy {
        val db = AppDatabase.getInstance(this)
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return UpcomingViewModel(db.upcomingDao(), db.recipeDao()) as T
            }
        }.create(UpcomingViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCartBadgeObserver()

        // Setup DrawerLayout
        drawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView

        // NavController
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // AppBarConfiguration with drawer
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_recipesbottom, R.id.nav_shoppinglistbottom),
            drawerLayout
        )

        // Link Drawer + BottomNav with Navigation Controller
        NavigationUI.setupWithNavController(binding.navView, navController)
        NavigationUI.setupWithNavController(binding.bottomNav, navController)

        // Drawer toggle animation
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }

    override fun onSupportNavigateUp(): Boolean {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    private fun startCartBadgeObserver() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav) ?: return
        val badge = bottomNav.getOrCreateBadge(R.id.nav_shoppinglistbottom)

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // Use ViewModel helper instead of DAO directly
                upcomingViewModel.getShoppingItemCountFlow()
                    .collect { count ->
                        if (count > 0) {
                            badge.isVisible = true
                            badge.number = count
                        } else {
                            badge.clearNumber()
                            badge.isVisible = false
                        }
                    }
            }
        }
    }
}
