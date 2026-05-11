package com.example.rezeptmoment.ui.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

// One canonical event set for the whole app.
sealed class AppEvent {
    // iOS Notification.Name equivalents
    data object DidUpdateShoppingList : AppEvent()
    data class DidUpdateRecipe(val recipeId: java.util.UUID? = null) : AppEvent()
    data object ShouldUpdatePdfThumbnailInDetailView : AppEvent()
    data object DidImportRecipes : AppEvent()
    data object DidUnlockPremium : AppEvent()

    // Local convenience events
    data object RecipesUpdated : AppEvent()         // e.g., after reorder/delete/rename
}

/**
 * Simple app-wide event bus using SharedFlow.
 * - extraBufferCapacity allows fire-and-forget (no suspension in UI thread)
 * - replay = 0 so only new events after subscription are received (like NotificationCenter)
 */
object EventBus {
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<AppEvent> = _events

    fun post(event: AppEvent) {
        _events.tryEmit(event)
    }
}