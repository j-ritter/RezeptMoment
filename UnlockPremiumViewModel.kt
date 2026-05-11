package com.example.rezeptmoment

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.rezeptmoment.data.DidUnlockPremium
import com.example.rezeptmoment.data.PremiumDao
import com.example.rezeptmoment.ui.util.AppEvent
import com.example.rezeptmoment.ui.util.EventBus
import kotlinx.coroutines.launch

class UnlockPremiumViewModel(
    private val premiumDao: PremiumDao,
    application: Application
) : AndroidViewModel(application) {

    // UI state
    val headlineText = MutableLiveData("Unlimited Items")
    val bodyText = MutableLiveData("Add an unlimited number of new items.")
    val buttonText = MutableLiveData("Unlock Premium")
    val buttonLoading = MutableLiveData(false)
    val showRestore = MutableLiveData(false)
    val showError = MutableLiveData<String?>(null)

    // Billing
    private val billingClient = BillingClient.newBuilder(application)
        .enablePendingPurchases()
        .setListener { billingResult, purchases -> handlePurchases(billingResult, purchases) }
        .build()

    private val productId = "com.kantt.cm.Recipes.unlockPremium"
    private var localizedPrice: String = ""

    fun load() {
        viewModelScope.launch {
            if (premiumDao.getPremium() != null) {
                // Already unlocked — ensure UI reflects this.
                unlockPremium()
            }
        }
        connectBilling()
    }

    private fun connectBilling() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                queryProductDetails()
            }
            override fun onBillingServiceDisconnected() { /* no-op */ }
        })
    }

    private fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { _, detailsList ->
            detailsList.firstOrNull()?.let {
                localizedPrice = it.oneTimePurchaseOfferDetails?.formattedPrice ?: "Unlock Premium"
                buttonText.postValue(localizedPrice)
                showRestore.postValue(true)
            }
        }
    }

    fun purchase(activity: Activity) {
        buttonLoading.value = true

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()

        billingClient.queryProductDetailsAsync(params) { _, detailsList ->
            detailsList.firstOrNull()?.let { details ->
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(details)
                                .build()
                        )
                    )
                    .build()
                billingClient.launchBillingFlow(activity, flowParams)
                // purchases will be delivered to setListener -> handlePurchases
            } ?: run {
                buttonLoading.value = false
                showError.value = "Error, try again"
            }
        }
    }

    fun restorePurchases() {
        buttonLoading.value = true
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
            ::handleRestoredPurchases
        )
    }

    private fun handlePurchases(
        billingResult: BillingResult,
        purchases: List<Purchase>?
    ) {
        buttonLoading.value = false

        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                } else if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    unlockPremium()
                }
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            showError.value = "Error, try again"
        }
    }

    private fun handleRestoredPurchases(
        billingResult: BillingResult,
        purchases: List<Purchase>
    ) {
        buttonLoading.value = false

        if (purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
            unlockPremium()
            showError.value = "Your purchases were restored"
        } else {
            showError.value = "No purchases to restore"
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                unlockPremium()
            }
        }
    }

    /** Single source of truth for unlocking: persists, updates UI, and notifies the app. */
    fun unlockPremium() {
        viewModelScope.launch {
            // persist
            premiumDao.clearPremium()
            premiumDao.insertPremium(DidUnlockPremium())

            // update UI state
            headlineText.postValue("Premium Unlocked")
            bodyText.postValue("Thanks for supporting the app!")
            buttonText.postValue("Unlocked")
            buttonLoading.postValue(false)
            showRestore.postValue(false)
            showError.postValue(null)

            // notify the app (Android equivalent of iOS Notification.Name.didUnlockPremium)
            EventBus.post(AppEvent.DidUnlockPremium)
        }
    }
}
