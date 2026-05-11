package com.example.rezeptmoment

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.rezeptmoment.UnlockPremiumViewModel
import com.example.rezeptmoment.data.PremiumDao

class PremiumViewModelFactory(
    private val premiumDao: PremiumDao,
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UnlockPremiumViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return UnlockPremiumViewModel(premiumDao, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}