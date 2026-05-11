package com.example.rezeptmoment.ui

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class GridSpacingItemDecoration(
    private val topSpacePx: Int,
    private val trailingSpacePx: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        // Apply iOS-like insets:
        outRect.top = topSpacePx
        outRect.right = trailingSpacePx
        outRect.left = 0
        outRect.bottom = 0
    }
}
