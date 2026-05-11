package com.example.rezeptmoment

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.data.RecipeElement
import com.example.rezeptmoment.ui.util.formatQuantity
import java.io.File
import java.util.UUID
import kotlin.math.abs



/**
 * Adapter that displays:
 * - A header row for Ingredients (if there are any)
 * - All ingredient elements
 * - A header row for Steps (if there are any)
 * - All non-ingredient elements (text, link, image, video...)
 *
 * Headers are lightweight rows (not persisted) and never participate in drag-reorder.
 * Drag/drop and persistence operate on the underlying element list only.
 */
class RecipeElementAdapter(
    private val onLinkClicked: (String) -> Unit = {},
    private val onIngredientClicked: (RecipeElement) -> Unit = {},
    private val onEditClicked: (RecipeElement) -> Unit = {},
    private val onDeleteClicked: (RecipeElement) -> Unit = {},
    private val onMediaClicked: (RecipeElement) -> Unit = {},
    private val onAddToShoppingList: (RecipeElement) -> Unit = {},


) : ListAdapter<RecipeElementAdapter.Row, RecyclerView.ViewHolder>(DiffCallback) {

    /* ---------------- Types ---------------- */

    private companion object {
        private const val TYPE_HEADER = 100
        private const val TYPE_TEXT = 0
        private const val TYPE_IMAGE = 1
        private const val TYPE_INGREDIENT = 2
        private const val TYPE_LINK = 3
        private const val TYPE_INSTRUCTION = 4


        // Stable IDs for headers (negative to avoid collision with XOR-ed UUID longs)
        private const val ID_HEADER_INGREDIENTS = -1L
        private const val ID_HEADER_STEPS = -2L
    }
    private val ingredientsOnShoppingList = mutableSetOf<UUID>()

    /** Public row model (header or element) used by the adapter internally. */
    sealed class Row {
        data class Header(
            val title: String,
            val showMarkedIcon: Boolean,
            /** A stable key: "ingredients" or "steps" */
            val key: String
        ) : Row()

        data class Element(val data: RecipeElement) : Row()
    }

    /** DiffUtil across Row items. */
    object DiffCallback : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row): Boolean {
            return when {
                old is Row.Header && new is Row.Header -> old.key == new.key
                old is Row.Element && new is Row.Element -> old.data.uniqueId == new.data.uniqueId
                else -> false
            }
        }

        override fun areContentsTheSame(old: Row, new: Row): Boolean {
            return old == new
        }
    }

    init {
        setHasStableIds(true)
    }


    /* ---------------- Public API ---------------- */

    /**
     * Submit elements with two lightweight headers (if sections are non-empty).
     * - titles are passed in so you can localize via getString(...)
     */
    fun submitWithHeaders(
        elements: List<RecipeElement>,
        ingredientsTitle: String,
        stepsTitle: String,
        showMarkedIconOnHeaders: Boolean = false,
        shoppingState: Set<UUID>? = null
    ) {
        baseElements = elements.toList()
        shoppingState?.let {  // store shopping state
            ingredientsOnShoppingList.clear()
            ingredientsOnShoppingList.addAll(it)
        }
        submitList(buildRows(elements, ingredientsTitle, stepsTitle, showMarkedIconOnHeaders))
    }


    /** Expose only the real elements for persistence logic (no headers). */
    fun currentElements(): List<RecipeElement> = baseElements

    fun updateShoppingStateFor(ingredientId: UUID, isOnList: Boolean) {
        if (isOnList) {
            ingredientsOnShoppingList.add(ingredientId)
        } else {
            ingredientsOnShoppingList.remove(ingredientId)
        }
        // Find the position of this ingredient and refresh it
        val position = currentList.indexOfFirst {
            it is Row.Element && it.data.uniqueId == ingredientId
        }
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    /**
     * Drag-reorder by adapter positions. We map adapter positions (with headers)
     * to pure element indices (without headers), swap there, and re-build rows.
     */
    fun swap(fromAdapterPos: Int, toAdapterPos: Int) {
        val fromIdx = elementIndexForAdapterPosition(fromAdapterPos) ?: return
        val toIdx = elementIndexForAdapterPosition(toAdapterPos) ?: return
        if (fromIdx == toIdx) return

        val mutable = baseElements.toMutableList()
        val item = mutable.removeAt(fromIdx)
        mutable.add(toIdx, item)
        baseElements = mutable
        // rebuild rows with same header settings & titles if we still have them cached
        submitList(buildRows(baseElements, cachedIngredientsTitle, cachedStepsTitle, cachedMarkedIcon))
    }

    /* ---------------- Internal state ---------------- */

    // The "truth" list without headers; used by persistOrder & swap
    private var baseElements: List<RecipeElement> = emptyList()

    // Cache the last titles and icon flag so swap() can rebuild rows consistently
    private var cachedIngredientsTitle: String = "Ingredients"
    private var cachedStepsTitle: String = "Steps"
    private var cachedMarkedIcon: Boolean = false
    private var dragPosition: Int? = null

    var currentServings: Long = 1
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private fun buildRows(
        elements: List<RecipeElement>,
        ingredientsTitle: String,
        stepsTitle: String,
        showMarkedIconOnHeaders: Boolean
    ): List<Row> {
        cachedIngredientsTitle = ingredientsTitle
        cachedStepsTitle = stepsTitle
        cachedMarkedIcon = showMarkedIconOnHeaders

        val ingredients = elements.filter { it.type == "ingredients" }
        val others = elements.filter { it.type != "ingredients" }

        val rows = mutableListOf<Row>()
        // Do NOT add Row.Header for either section!
        rows += ingredients.map { Row.Element(it) }
        rows += others.map { Row.Element(it) }
        return rows
    }

    fun isHeaderPosition(position: Int): Boolean =
        position in 0 until itemCount && (getItem(position) is Row.Header)

    fun rowToElementIndex(position: Int): Int? {
        if (position !in 0 until itemCount) return null
        if (getItem(position) is Row.Header) return null
        // Count headers in the current submitted list before this position
        val headersBefore = currentList.subList(0, position).count { it is Row.Header }
        return position - headersBefore
    }

    fun setDragPosition(position: Int?) {
        dragPosition = position
        notifyItemChanged(position ?: return)
    }

    /** Map adapter position (with headers) -> element index (without headers). */
    private fun elementIndexForAdapterPosition(adapterPos: Int): Int? {
        if (adapterPos < 0 || adapterPos >= itemCount) return null
        if (isHeaderPosition(adapterPos)) return null
        var count = 0
        for (i in 0..adapterPos) {
            val row = getItem(i)
            if (row is Row.Element) count++
        }
        return count - 1
    }

    private fun headerOffsetForSteps(): Int {
        return currentList.indexOfFirst { it is Row.Header && it.key == "steps" } + 1
    }

    /* ---------------- RecyclerView.Adapter ---------------- */

    override fun getItemId(position: Int): Long {
        return when (val row = getItem(position)) {
            is Row.Header -> when (row.key) {
                "ingredients" -> ID_HEADER_INGREDIENTS
                "steps" -> ID_HEADER_STEPS
                else -> ID_HEADER_STEPS - 1 // fallback, still negative and stable-ish
            }
            is Row.Element -> {
                val id: UUID = row.data.uniqueId
                id.mostSignificantBits xor id.leastSignificantBits
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val row = getItem(position)) {
            is Row.Header -> TYPE_HEADER
            is Row.Element -> {
                val e = row.data
                when {
                    e.type == "ingredients" -> TYPE_INGREDIENT
                    e.type == "steps" -> TYPE_INSTRUCTION
                    e.instructionType == "link" -> TYPE_LINK
                    e.type == "image" || e.instructionType == "image" || e.instructionType == "video" -> TYPE_IMAGE
                    else -> TYPE_TEXT
                }
            }
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> SectionHeaderViewHolder(inf.inflate(R.layout.item_section_header, parent, false))
            TYPE_IMAGE -> ImageViewHolder(inf.inflate(R.layout.item_recipe_image, parent, false))
            TYPE_INGREDIENT -> IngredientViewHolder(inf.inflate(R.layout.item_recipe_ingredient, parent, false))
            TYPE_INSTRUCTION -> InstructionViewHolder(inf.inflate(R.layout.item_recipe_instruction, parent, false))
            TYPE_LINK -> LinkViewHolder(inf.inflate(R.layout.item_recipe_link, parent, false))
            else -> TextViewHolder(inf.inflate(R.layout.item_recipe_text, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val isDragging = position == dragPosition
        val row = getItem(position)  // ✅ SINGLE declaration

        when (row) {
            is Row.Header -> (holder as SectionHeaderViewHolder).bind(row)
            is Row.Element -> when (holder) {
                is TextViewHolder ->
                    holder.bind(row, isDragging, onMediaClicked, onIngredientClicked, onEditClicked, onDeleteClicked, onLinkClicked, onAddToShoppingList)
                is ImageViewHolder ->
                    holder.bind(row, isDragging, onMediaClicked, onIngredientClicked, onEditClicked, onDeleteClicked, onLinkClicked)
                is IngredientViewHolder -> {
                    val isOnShoppingList = row.data.uniqueId?.let { ingredientsOnShoppingList.contains(it) } ?: false
                    holder.bind(
                        row, isDragging, onMediaClicked, onIngredientClicked, onEditClicked,
                        onDeleteClicked, onLinkClicked, currentServings, onAddToShoppingList, isOnShoppingList
                    )
                }
                is InstructionViewHolder ->
                    holder.bind(row, position - headerOffsetForSteps())
                is LinkViewHolder ->
                    holder.bind(row, isDragging, onMediaClicked, onIngredientClicked, onEditClicked, onDeleteClicked, onLinkClicked)
            }
        }
    }

    /* ---------------- ViewHolders ---------------- */

    class SectionHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.sectionTitleRecipeDetail)
        private val markedIcon: ImageView = itemView.findViewById(R.id.sectionMarkedIconRecipeDetail)

        fun bind(h: Row.Header) {
            title.text = h.title
            markedIcon.visibility = if (h.showMarkedIcon) View.VISIBLE else View.GONE
        }
    }

    class TextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.text)

        fun bind(
            row: RecipeElementAdapter.Row.Element,
            isDragging: Boolean,
            onMediaClicked: (RecipeElement) -> Unit,
            onIngredientClicked: (RecipeElement) -> Unit,
            onEditClicked: (RecipeElement) -> Unit,
            onDeleteClicked: (RecipeElement) -> Unit,
            onLinkClicked: (String) -> Unit,
            onAddToShoppingList: (RecipeElement) -> Unit
        ) {
            val e = row.data
            text.text = e.primaryText.ifBlank { e.instructionText.orEmpty() }
            val cartIcon: ImageView = itemView.findViewById(R.id.shoppingCartAddIcon)
            cartIcon.setOnClickListener { onAddToShoppingList(e) }
            // Add drag highlight
            if (isDragging) {
                itemView.setBackgroundColor(itemView.context.getColor(R.color.colorDragHighlight))
            } else {
                itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.image)

        fun bind(
            row: RecipeElementAdapter.Row.Element,
            isDragging: Boolean,
            onMediaClicked: (RecipeElement) -> Unit,
            onIngredientClicked: (RecipeElement) -> Unit,
            onEditClicked: (RecipeElement) -> Unit,
            onDeleteClicked: (RecipeElement) -> Unit,
            onLinkClicked: (String) -> Unit
        ) {
            val e = row.data
            val ctx = itemView.context
            val file = when {
                e.type == "image" || e.instructionType == "image" || e.instructionType == "video" ->
                    File(ctx.filesDir, "${e.uniqueId}.jpg")
                else -> null
            }
            if (file != null && file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let(image::setImageBitmap)
                    ?: image.setImageResource(R.drawable.ic_camera)
            } else {
                image.setImageResource(R.drawable.ic_cart)
            }
            itemView.setOnClickListener { onMediaClicked(e) }

            // Add drag highlight
            if (isDragging) {
                itemView.setBackgroundColor(itemView.context.getColor(R.color.colorDragHighlight))
            } else {
                itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    class IngredientViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.ingredientText)
        val cartIcon: ImageView = itemView.findViewById(R.id.shoppingCartAddIcon)

        fun bind(
            row: RecipeElementAdapter.Row.Element,
            isDragging: Boolean,
            onMediaClicked: (RecipeElement) -> Unit,
            onIngredientClicked: (RecipeElement) -> Unit,
            onEditClicked: (RecipeElement) -> Unit,
            onDeleteClicked: (RecipeElement) -> Unit,
            onLinkClicked: (String) -> Unit,
            currentServings: Long,
            onAddToShoppingList: (RecipeElement) -> Unit,
            isOnShoppingList: Boolean
        ) {
            val e = row.data
            val basePeople = e.numberOfCurrentPeople ?: 1L
            val ratio = currentServings.toFloat() / basePeople.toFloat()
            val scaledQty = e.quantity * ratio
            val name = e.primaryText.trim()
            val unit = e.secondaryText?.trim().orEmpty()
            val q = e.quantity
            val parts = buildList {
                if (abs(q) > 0.0001f) add(q.formatQuantity())
                if (unit.isNotEmpty()) add(unit)
                if (name.isNotEmpty()) add(name)
            }
            text.text = parts.joinToString(" ").ifBlank { e.primaryText }

            // Set cart icon state based on shopping list

            if (isOnShoppingList) {
                // User can REMOVE it
                cartIcon.setImageResource(R.drawable.ic_remove_shopping_cart)
                cartIcon.setColorFilter(itemView.context.getColor(android.R.color.holo_red_dark))
            } else {
                // User can ADD it
                cartIcon.setImageResource(R.drawable.ic_shopping_cart_add)
                cartIcon.setColorFilter(itemView.context.getColor(android.R.color.darker_gray))
            }

            itemView.setOnClickListener { onIngredientClicked(e) }
            itemView.setOnLongClickListener {
                val ctx = itemView.context
                val popup = android.widget.PopupMenu(ctx, itemView)
                popup.menu.add(ctx.getString(R.string.edit))
                    .setOnMenuItemClickListener { onEditClicked(e); true }
                popup.menu.add(ctx.getString(R.string.delete))
                    .setOnMenuItemClickListener { onDeleteClicked(e); true }
                popup.show()
                true
            }
            cartIcon.setOnClickListener { onAddToShoppingList(e) }

            // Add drag highlight
            if (isDragging) {
                itemView.setBackgroundColor(itemView.context.getColor(R.color.colorDragHighlight))
            } else {
                itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    class InstructionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.instructionNumber)
        private val text: TextView = itemView.findViewById(R.id.instructionText)

        fun bind(
            row: RecipeElementAdapter.Row.Element,
            position: Int
        ) {
            val instr = row.data.instructionText?.trim().orEmpty()
            if (instr.isBlank()) {
                // Optional: If possible, set itemView height to 0 or visibility to GONE for blank row
                itemView.visibility = View.GONE
                itemView.layoutParams = (itemView.layoutParams)?.apply { height = 0 }
            } else {
                text.text = instr
                number.text = "${position + 1}."
                number.visibility = View.VISIBLE
                text.visibility = View.VISIBLE
                itemView.visibility = View.VISIBLE
                itemView.layoutParams = (itemView.layoutParams)?.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            }
        }
    }

    class LinkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.linkText)

        fun bind(
            row: RecipeElementAdapter.Row.Element,
            isDragging: Boolean,
            onMediaClicked: (RecipeElement) -> Unit,
            onIngredientClicked: (RecipeElement) -> Unit,
            onEditClicked: (RecipeElement) -> Unit,
            onDeleteClicked: (RecipeElement) -> Unit,
            onLinkClicked: (String) -> Unit
        ) {
            val e = row.data
            text.text = e.instructionText?.takeIf { it.isNotBlank() } ?: e.primaryText
            itemView.setOnClickListener {
                if (e.primaryText.isNotBlank()) onLinkClicked(e.primaryText)
            }

            // Add drag highlight
            if (isDragging) {
                itemView.setBackgroundColor(itemView.context.getColor(R.color.colorDragHighlight))
            } else {
                itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }


}
