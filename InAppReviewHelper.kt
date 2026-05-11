package com.example.rezeptmoment

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

object InAppReviewHelper {
    fun maybeAskForReview(activity: Activity, recipeCount: Int) {
        if (recipeCount == 3) { // 🎯 trigger on 3rd recipe
            val manager = ReviewManagerFactory.create(activity)
            val request = manager.requestReviewFlow()

            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(activity, reviewInfo)
                    flow.addOnCompleteListener {
                        Log.d("InAppReview", "Review flow finished")
                        // Google handles whether to actually show dialog or not.
                    }
                } else {
                    Log.w("InAppReview", "Request failed", task.exception)
                }
            }
        }
    }
}