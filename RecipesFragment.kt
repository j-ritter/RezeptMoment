package com.example.rezeptmoment

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.MigrationProgressListener
import com.example.rezeptmoment.data.Recipe

import com.example.rezeptmoment.data.MigrationUtils
import com.example.rezeptmoment.ui.RecipeDetailActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*
import com.google.android.play.core.review.ReviewManagerFactory

import android.util.Log
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.ConcatAdapter
import com.example.rezeptmoment.data.RecipeCategory
import com.example.rezeptmoment.ui.SectionHeaderAdapter
import androidx.appcompat.widget.PopupMenu
import android.view.Gravity
import androidx.activity.result.ActivityResult
import com.google.android.material.snackbar.Snackbar
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import androidx.lifecycle.Lifecycle
import com.example.rezeptmoment.ui.ExportImportActivity
import kotlinx.coroutines.flow.forEach
import java.io.File
import androidx.lifecycle.repeatOnLifecycle
import com.example.rezeptmoment.ui.Login
import kotlinx.coroutines.launch
import com.example.rezeptmoment.ui.util.AppEvent
import com.example.rezeptmoment.ui.util.EventBus
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class RecipesFragment : Fragment() {

    private lateinit var recycler: RecyclerView

    private lateinit var tutorialButton: Button

    // Overlay
    private lateinit var overlayContainer: FrameLayout

    private lateinit var overlayLabel: TextView

    // Search
    private lateinit var searchView: SearchView
    private var isSearching = false

    private var isPremiumUnlocked: Boolean = false


    // Data (wired to Room later)
    private var allRecipes: List<Recipe> = emptyList()
    private var filteredRecipes: List<Recipe> = emptyList()
    private var groupedCache: List<Pair<RecipeCategory, List<Recipe>>> = emptyList()

    private val expandedCategories = mutableSetOf<UUID>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_recipes, container, false)
        recycler = root.findViewById(R.id.recipesRecycler)
        tutorialButton = root.findViewById(R.id.tutorialButtonRecipes)
        overlayContainer = root.findViewById(R.id.progressOverlay)
        overlayLabel = root.findViewById(R.id.progressLabelRecipes)
        searchView = root.findViewById(R.id.searchViewRecipes)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Handle toolbar "+ add" button
        view.findViewById<View>(R.id.toolbar_add)?.setOnClickListener {
            showAddMenu()
        }
        view.findViewById<View>(R.id.toolbar_search)?.setOnClickListener {
            toggleSearchbar()
        }
        view.findViewById<View>(R.id.toolbar_menu)?.setOnClickListener {
            showSettingsMenu()
        }
        view.findViewById<View>(R.id.toolbar_export)?.setOnClickListener {
            val intent = Intent(requireContext(), ExportImportActivity::class.java)
                .putExtra(ExportImportActivity.EXTRA_MODE, "export")
            startActivity(intent)
        }
        // Use a vertical LinearLayoutManager for one-recipe-per-row layout
        recycler.layoutManager = LinearLayoutManager(requireContext())

        // 2) Tutorial CTA
        tutorialButton.setOnClickListener {
            val assignedCount = allRecipes.count { it.belongsToCategoryId != null }
            if (!isPremiumUnlocked && assignedCount > 2) {
                // ✅ Same as + button: show premium path
                showAddMenu()  // Calls showAddMenu() → Premium popup
            } else {
                createNewRecipe()
            }
        }

        // 3) Search
        configureSearchbar()

        // 4) Migrations then load data
        AppDatabase.runDatabaseMigrations(requireContext())

        // 5) Auto-refresh after an import
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { event ->
                    when (event) {
                        is AppEvent.DidImportRecipes -> {
                            loadItems()
                            Toast.makeText(requireContext(), getString(R.string.import_done_stub), Toast.LENGTH_SHORT).show()
                        }
                        is AppEvent.DidUpdateShoppingList -> updateCartBadge()
                        is AppEvent.DidUpdateRecipe -> loadItems()
                        else -> {}
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensures thumbnails, names, and marked/unmarked states are up to date
        loadItems()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        // We consider recipes loaded when allRecipes has items
        val hasAnyRecipes = allRecipes.isNotEmpty()

        // - Search & Export only make sense when there is at least one recipe
        menu.findItem(R.id.action_search)?.isEnabled = hasAnyRecipes
        menu.findItem(R.id.action_export)?.isEnabled = hasAnyRecipes

        // Add and Settings remain enabled (they open menus / flows)
        menu.findItem(R.id.action_add)?.isEnabled = true
        menu.findItem(R.id.action_settings)?.isEnabled = true

    }

    /* ========= Helpers for overlay ========= */

    private fun showActivityIndicator(labelText: String?) {
        overlayLabel.text = labelText ?: ""
        overlayContainer.isVisible = true
    }

    private fun hideActivityIndicator() {
        overlayContainer.isGone = true
        overlayLabel.text = ""
    }

    private fun checkIfTutorialWasShown() {
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val shown = prefs.getInt("didShowWelcomeTutorial", 0)

        if (shown == 0) {
            val db = AppDatabase.getInstance(requireContext())

            lifecycleScope.launch {
                val recipeCount = db.recipeDao().getRecipeCount()
                val categoryCount = db.recipeCategoryDao().getCategoryCount()

                if (recipeCount == 0 && categoryCount == 0) {
                    TutorialActivity.launch(requireContext())
                    prefs.edit().putInt("didShowWelcomeTutorial", 1).apply()
                }
            }
        }
    }

    private fun calculateColumnCount(): Int {
        // Prefer the measured width of the RecyclerView; fall back to screen width if not measured yet
        val pxWidth = if (recycler.width > 0) recycler.width else resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val widthDp = pxWidth / density

        // Tune this to the card size you want; 160–200dp works well. You used 180dp.
        val minItemWidthDp = 180f

        return maxOf(1, (widthDp / minItemWidthDp).toInt())
    }

    private fun anchorView(): View = recycler // safe fallback anchor for popups

    private fun showAddMenu() {
        val popup = PopupMenu(requireContext(), anchorView(), Gravity.END)
        val assignedCount = allRecipes.count { it.belongsToCategoryId != null }

        if (!isPremiumUnlocked && assignedCount > 2) {
            // iOS: show purchase path when not premium and “enough” content exists
            popup.menu.add(getString(R.string.premium_unlimited_items)) // "Unlimited Items"
                .setOnMenuItemClickListener {
                    startActivity(Intent(requireContext(), PremiumActivity::class.java))
                    true
                }
            popup.menu.add(getString(R.string.new_category))
                .setOnMenuItemClickListener {
                    createNewCategory(isEditingFirstCategory = false)
                    true
                }
        } else {
            popup.menu.add(getString(R.string.new_recipe)) // "New Recipe"
                .setOnMenuItemClickListener { createNewRecipe(); true }
            popup.menu.add(getString(R.string.new_category)) // "New Category"
                .setOnMenuItemClickListener {
                    startActivity(Intent(requireContext(), com.example.rezeptmoment.ui.OrganizeCategoriesActivity::class.java))
                    true
                }
            popup.menu.add(getString(R.string.import_recipes))
                .setOnMenuItemClickListener  {
                    val intent = Intent(requireContext(), ExportImportActivity::class.java)
                        .putExtra(ExportImportActivity.EXTRA_MODE, "import")
                    startActivity(intent)
                    true
                }
        }
        popup.show()
    }

    private fun showMarkedRecipesMenu() {
        val marked = allRecipes
            .filter { it.isMarked }
            .sortedBy { it.dateOfMarking ?: Date(0) }

        if (marked.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_marked_recipes), Toast.LENGTH_SHORT).show()
            return
        }

        val popup = PopupMenu(requireContext(), anchorView(), Gravity.END)
        marked.forEachIndexed { idx, recipe ->
            val title = recipe.primaryText ?: getString(R.string.untitled_recipe)
            popup.menu.add(0, idx, idx, title)
        }
        popup.setOnMenuItemClickListener { mi ->
            val recipe = marked[mi.itemId]
            openExistingRecipe(recipe)
            true
        }
        popup.show()
    }

    private fun showSettingsMenu() {
        val popup = PopupMenu(requireContext(), anchorView())

        // 1. Tutorial (always)
        popup.menu.add(getString(R.string.tutorial))
            .setOnMenuItemClickListener {
                TutorialActivity.launch(requireContext())
                true
            }


        // 2. Edit Categories (if categories exist)
        popup.menu.add(getString(R.string.edit_categories))
            .setEnabled(groupedCache.isNotEmpty())
            .setOnMenuItemClickListener {
                startActivity(Intent(requireContext(), com.example.rezeptmoment.ui.OrganizeCategoriesActivity::class.java))
                true
            }

        // 3. Premium (if not premium)
        if (!isPremiumUnlocked) {
            popup.menu.add(getString(R.string.premium_unlimited_items))
                .setOnMenuItemClickListener {
                    startActivity(Intent(requireContext(), PremiumActivity::class.java))
                    true
                }
        } else {
            // 5. Rate App (always, or as #5 when premium)
            popup.menu.add(getString(R.string.rate_this_app))
                .setOnMenuItemClickListener {
                    offerRatingOpportunity()
                    true
                }
        }

        // Rate App when not premium (fills slot #5)
        if (!isPremiumUnlocked) {
            popup.menu.add(getString(R.string.rate_this_app))
                .setOnMenuItemClickListener {
                    offerRatingOpportunity()
                    true
                }
        }

        popup.menu.add("Log Out")
            .setOnMenuItemClickListener {
                performLogout()
                true
            }

        popup.show()
    }

    private fun createNewCategory(isEditingFirstCategory: Boolean) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = if (isEditingFirstCategory) getString(R.string.new_category)
            else getString(R.string.name_ellipsis)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            setPadding(
                resources.getDimensionPixelSize(R.dimen.dialog_content_padding),
                resources.getDimensionPixelSize(R.dimen.dialog_content_padding_small),
                resources.getDimensionPixelSize(R.dimen.dialog_content_padding),
                resources.getDimensionPixelSize(R.dimen.dialog_content_padding_small)
            )
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.new_category))
            .setMessage(getString(R.string.choose_category_name))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val title = if (raw.isEmpty()) getString(R.string.new_category) else raw

                viewLifecycleOwner.lifecycleScope.launch {
                    val db = AppDatabase.getInstance(ctx)
                    val dao = db.recipeCategoryDao()
                    val currentUserEmail = ctx.getSharedPreferences("RezeptmomentPrefs", Context.MODE_PRIVATE)
                        .getString("USER_EMAIL", "") ?: ""

                    if (isEditingFirstCategory) {
                        // Rename first category, if any
                        val first = dao.getCategoriesForUser(currentUserEmail).first().firstOrNull()
                        if (first != null) {
                            dao.updateCategory(first.copy(primaryText = title))
                        }
                        loadItems()
                    } else {
                        // Insert new category and then reindex like iOS
                        val existing = dao.getCategoriesForUser(currentUserEmail).first().sortedBy { it.orderingIndex }
                        val newOrdering =
                            if (existing.isNotEmpty()) existing.first().orderingIndex - 1 else -1

                        val newCat = RecipeCategory(
                            uniqueId = UUID.randomUUID(),
                            primaryText = title,
                            orderingIndex = newOrdering,
                            userId = currentUserEmail
                        )
                        dao.insertCategory(newCat)

                        // Reindex 1..N
                        val resorted = dao.getCategoriesForUser(currentUserEmail).first().sortedBy { it.orderingIndex }
                        val reindexed = resorted.mapIndexed { idx, c ->
                            c.copy(orderingIndex = (idx + 1).toLong())
                        }
                        dao.updateCategories(reindexed)

                        loadItems()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun ensureDefaultCategory() {
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val currentUserEmail = requireContext()
                .getSharedPreferences("RezeptmomentPrefs", Context.MODE_PRIVATE)
                .getString("USER_EMAIL", "") ?: ""
            val count = db.recipeCategoryDao().getCategoryCount()
            if (count == 0) {
                val defaultCat = RecipeCategory(
                    uniqueId = UUID.randomUUID(),
                    primaryText = getString(R.string.default_category_name), // "My Recipes"
                    orderingIndex = 0L,
                    userId = currentUserEmail
                )
                db.recipeCategoryDao().insertCategory(defaultCat)
            }
            loadItems()
        }
    }

    private fun showItemContextMenu(anchor: View, recipe: Recipe) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)

        popup.menu.add(getString(R.string.open))
            .setOnMenuItemClickListener { openExistingRecipe(recipe); true }

        // Toggle marker
        val toggleTitle = if (recipe.isMarked) getString(R.string.remove_marker) else getString(R.string.add_marker)
        popup.menu.add(toggleTitle)
            .setOnMenuItemClickListener {
                val db = AppDatabase.getInstance(requireContext())
                lifecycleScope.launch {
                    val updated = recipe.copy(
                        isMarked = !recipe.isMarked,
                        dateOfMarking = if (!recipe.isMarked) Date() else null
                    )
                    db.recipeDao().update(updated)       // <-- requires RecipeDao.update(Recipe)
                    showInfoView(wasAdded = updated.isMarked)
                    loadItems()
                }
                true
            }

        // Move
        popup.menu.add(getString(R.string.move_recipes))
            .setOnMenuItemClickListener {
                val intent = Intent(requireContext(), com.example.rezeptmoment.ui.MoveItemsActivity::class.java)
                    .putExtra("RECIPE_ID", recipe.uniqueId.toString())
                startActivity(intent)
                true
            }


        // Delete
        popup.menu.add(getString(R.string.delete_recipe))
            .setOnMenuItemClickListener {
                deleteRecipe(recipe)
                true
            }

        popup.show()
    }

    private fun showInfoView(wasAdded: Boolean) {
        val msg = if (wasAdded) getString(R.string.added) else getString(R.string.removed)
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
    }

    /* ======================== Search ======================== */

    private fun configureSearchbar() {
        searchView.isIconified = false
        searchView.clearFocus()

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                endSearching()
                filteredRecipes.firstOrNull()?.let { openExistingRecipe(it) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                val q = newText.orEmpty().trim()
                if (q.isEmpty()) {
                    clearSearchResults()

                    return true
                }

                // Sectioned search (like iOS)
                isSearching = true
                searchView.isVisible = true
                tutorialButton.isVisible = true

                val lower = q.lowercase(Locale.getDefault())
                val recipesWithName = allRecipes.filter { !it.primaryText.isNullOrBlank() }
                val filtered = recipesWithName.filter {
                    it.primaryText!!.lowercase(Locale.getDefault()).contains(lower)
                }
                filteredRecipes = filtered

                val categoryById = groupedCache.associateBy({ it.first.uniqueId }, { it.first })
                val sectionsToAppend = LinkedHashSet<RecipeCategory>()
                for (r in filtered) {
                    r.belongsToCategoryId?.let { catId ->
                        categoryById[catId]?.let { sectionsToAppend.add(it) }
                    }
                }

                val sortedSections = sectionsToAppend.toList().sortedBy { it.orderingIndex }
                val groupedFiltered: List<Pair<RecipeCategory, List<Recipe>>> =
                    sortedSections.map { cat ->
                        val itemsInCat = filtered
                            .filter {
                                it.belongsToCategoryId != null &&
                                        it.belongsToCategoryId.toString().equals(cat.uniqueId.toString(), ignoreCase = true)
                            }
                            .sortedBy { it.orderingIndex }
                        cat to itemsInCat
                    }

                updateCollectionViewWithSections(groupedFiltered, highlightQuery = q)

                return true
            }
        })
    }

    private fun toggleSearchbar() {
        isSearching = !isSearching
        searchView.isVisible = isSearching
        if (isSearching) {
            filteredRecipes = allRecipes

            searchView.requestFocus()
        } else {
            endSearching()
        }
    }

    private fun clearSearchResults() {
        val full: List<Pair<RecipeCategory, List<Recipe>>> =
            groupedCache.map { (cat, _) ->
                val itemsInCat = allRecipes
                    .filter {
                        it.belongsToCategoryId != null &&
                                it.belongsToCategoryId.toString().equals(cat.uniqueId.toString(), ignoreCase = true)
                    }
                    .sortedBy { it.orderingIndex }
                cat to itemsInCat
            }

        updateCollectionViewWithSections(full, highlightQuery = null)

    }

    private fun endSearching() {
        isSearching = false
        searchView.setQuery("", false)
        searchView.clearFocus()
        searchView.isGone = true

        // Restore the sectioned list using cached grouping
        filteredRecipes = allRecipes
        updateCollectionViewWithSections(groupedCache, highlightQuery = null)

    }

    /* ======================== UI updates ====================== */

    private fun updateCollectionViewWithSections(
        grouped: List<Pair<RecipeCategory, List<Recipe>>>,
        highlightQuery: String? = null
    ) {
        val adapters = mutableListOf<RecyclerView.Adapter<*>>()

        for ((category, recipesInCategory) in grouped) {
            val isExpanded = expandedCategories.contains(category.uniqueId)
            val headerAdapter = SectionHeaderAdapter(
                title = category.primaryText,
                recipeCount = recipesInCategory.size,
                categoryId = category.uniqueId,
                isExpanded = isExpanded,
                onArrowClick = {
                    if (isExpanded) {
                        expandedCategories.remove(category.uniqueId)
                    } else {
                        expandedCategories.add(category.uniqueId)
                    }
                    val newGrouped = buildGroupedList()
                    updateCollectionViewWithSections(newGrouped)
                }
            )
            adapters.add(headerAdapter)

            if (isExpanded) {
                val recipeAdapter = RecipesAdapter(
                    onClick = { recipe -> openExistingRecipe(recipe) },
                    onLongPress = { recipe, anchor -> showItemContextMenu(anchor, recipe) }
                )
                recipeAdapter.setHighlightQuery(highlightQuery)
                recipeAdapter.submitList(recipesInCategory)
                adapters.add(recipeAdapter)
            }
        }

        recycler.adapter = ConcatAdapter(adapters)


        tutorialButton.isVisible = true

        filteredRecipes = allRecipes
        configureNavbar()
    }



    private fun buildGroupedList(): List<Pair<RecipeCategory, List<Recipe>>> {
        val categories = groupedCache.map { it.first }
        return categories.map { category ->
            val recipesInCategory = allRecipes
                .filter {
                    it.belongsToCategoryId != null &&
                            it.belongsToCategoryId.toString().equals(category.uniqueId.toString(), ignoreCase = true)
                }
                .sortedBy { it.orderingIndex }
            category to recipesInCategory
        }
    }

    /* ======================== Actions =========================== */

    private val createRecipeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadItems() // refresh list

            // 🔑 After adding, check recipe count for rating opportunity
            lifecycleScope.launch {
                val db = AppDatabase.getInstance(requireContext())
                val count = db.recipeDao().getRecipeCount()
                if (count == 3) {
                    offerRatingOpportunity()
                }
            }
        }
    }

    private val importRcpsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            handleImportedRcps(uri)
        }
    }

    private fun startImportFlow() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // cannot filter by extension reliably; we validate ourselves
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream", "application/zip", "application/x-zip-compressed"
            ))
        }
        importRcpsLauncher.launch(intent)
    }

    private fun handleImportedRcps(uri: Uri) {
        val name = queryDisplayName(uri) ?: ""
        val mime = requireContext().contentResolver.getType(uri).orEmpty()

        val looksRcps = name.lowercase().endsWith(".rcps") ||
                mime == "application/octet-stream" ||
                mime == "application/zip" ||
                mime == "application/x-zip-compressed"

        if (!looksRcps) {
            Toast.makeText(requireContext(), getString(R.string.invalid_import_type), Toast.LENGTH_LONG).show()
            return
        }

        showActivityIndicator(getString(R.string.import_recipes))

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Empty file")
                }

                val db = AppDatabase.getInstance(requireContext())
                val currentUserEmail = requireContext()
                    .getSharedPreferences("RezeptmomentPrefs", Context.MODE_PRIVATE)
                    .getString("USER_EMAIL", "") ?: ""
                val result = withContext(Dispatchers.IO) {
                    com.example.rezeptmoment.data.RcpsImporter.import(
                        bytes = bytes,
                        db = db,
                        filesDir = requireContext().filesDir,
                        userId = currentUserEmail
                    )
                }

                hideActivityIndicator()
                Snackbar.make(requireView(), getString(R.string.import_done_summary, result.categories, result.recipes, result.elements), Snackbar.LENGTH_LONG).show()
                loadItems()
            } catch (e: Throwable) {
                hideActivityIndicator()
                Toast.makeText(requireContext(), e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun queryDisplayName(uri: Uri): String? {
        val cr = requireContext().contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun hasShownRatingPrompt(): Boolean {
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("rating_prompt_shown", false)
    }

    private fun markRatingPromptShown() {
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("rating_prompt_shown", true).apply()
    }

    private fun offerRatingOpportunity() {
        if (hasShownRatingPrompt()) return

        val manager = ReviewManagerFactory.create(requireContext())
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                val flow = manager.launchReviewFlow(requireActivity(), reviewInfo)
                flow.addOnCompleteListener {
                    markRatingPromptShown()
                    Log.d("InAppReview", "Review flow finished")
                }
            } else {
                Log.w("InAppReview", "Review request failed", task.exception)
            }
        }
    }

    private fun createNewRecipe() {
        val intent = Intent(requireContext(), CreateRecipeActivity::class.java)
        createRecipeLauncher.launch(intent)
    }

    private fun openExistingRecipe(recipe: Recipe) {
        val intent = Intent(requireContext(), RecipeDetailActivity::class.java)
        intent.putExtra("recipeId", recipe.uniqueId.toString())
        startActivity(intent)
    }

    /* ======================== Data load =============================== */

    private fun loadItems() {
        val db = AppDatabase.getInstance(requireContext())

        lifecycleScope.launch {
            // Get categories sorted
            isPremiumUnlocked = db.premiumDao().getPremium() != null

            // Get recipes for CURRENT user only
            val currentUserEmail = requireContext()
                .getSharedPreferences("RezeptmomentPrefs", Context.MODE_PRIVATE)
                .getString("USER_EMAIL", "") ?: ""

            val categories = db.recipeCategoryDao()
                .getCategoriesForUser(currentUserEmail).first()
                .sortedBy { it.orderingIndex }

            val recipes = db.recipeDao().getRecipesForUser(currentUserEmail).first()

            // Enrich with shopping list info
            val upcomingDao = db.upcomingDao()
            val enrichedRecipes = recipes.map { recipe ->
                val count = upcomingDao.countShoppingItemsForRecipe(recipe.uniqueId)
                recipe.hasShoppingItems = count > 0
                recipe.shoppingItemCount = count
                recipe
            }

            // Group recipes by category
            val grouped = categories.map { category ->
                val recipesInCategory = enrichedRecipes
                    .filter {
                        it.belongsToCategoryId != null &&
                                it.belongsToCategoryId.toString().equals(category.uniqueId.toString(), ignoreCase = true)
                    }

                    .sortedBy { it.orderingIndex }
                category to recipesInCategory
            }

            // Update state
            allRecipes = enrichedRecipes
            filteredRecipes = enrichedRecipes
            groupedCache = grouped

            // Update UI
            updateCollectionViewWithSections(grouped)
            updateCartBadge()
        }
    }

    private fun configureNavbar() {
        // Title (large titles aren’t a built-in Android pattern; keep it simple)
        requireActivity().title = getString(R.string.recipes)
        // Let the system re-run onPrepareOptionsMenu with latest data
        requireActivity().invalidateOptionsMenu()
    }

    private fun updateCartBadge() {
        val bottomNav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            ?: return
        val badge = bottomNav.getOrCreateBadge(R.id.nav_shoppinglistbottom)

        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val count = db.upcomingDao().countPendingShoppingItems()
            if (count > 0) {
                badge.isVisible = true
                badge.number = count
            } else {
                badge.clearNumber()
                badge.isVisible = false
            }
        }
    }

    private fun deleteRecipe(recipe: Recipe) {
        val ctx = requireContext().applicationContext
        val db = AppDatabase.getInstance(ctx)
        val recipeId = recipe.uniqueId

        showActivityIndicator(getString(R.string.deleting))

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1) Elements of this recipe
                val elements = db.recipeElementDao().getElementsForRecipe(recipeId).first()

                // 2) Delete element files
                val filesDir = ctx.filesDir
                elements.forEach { el ->
                    val elId = el.uniqueId ?: return@forEach
                    val ext = when {
                        el.type == "image" || el.instructionType == "image" -> "jpg"
                        el.instructionType == "video" -> (el.attachmentType ?: "mov")
                        else -> "pdf"
                    }
                    File(filesDir, "$elId.$ext").takeIf { it.exists() }?.delete()
                    if (el.instructionType == "video") {
                        File(filesDir, "$elId.jpg").takeIf { it.exists() }?.delete()
                    }
                }

                // 3) Delete upcoming objects linked to elements
                val elementIds = elements.mapNotNull { it.uniqueId }
                if (elementIds.isNotEmpty()) {
                    db.upcomingDao().deleteUpcomingForIngredientIds(elementIds)
                }

                // 4) Delete elements
                if (elements.isNotEmpty()) {
                    db.recipeElementDao().deleteElementsForRecipe(recipeId)
                }

                // 5) Delete recipe preview image
                File(filesDir, "$recipeId.jpg").takeIf { it.exists() }?.delete()

                // 6) Delete recipe row
                db.recipeDao().delete(recipe)

                // 7) UI done
                hideActivityIndicator()
                Snackbar.make(requireView(), getString(R.string.deleted_recipe), Snackbar.LENGTH_SHORT).show()
                loadItems()
            } catch (e: Throwable) {
                hideActivityIndicator()
                Toast.makeText(ctx, e.message ?: "Delete failed", Toast.LENGTH_LONG).show()
            }
        }
    }


    /* ======================== Menu ========================================== */

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_recipes, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add -> { showAddMenu(); true }
            R.id.action_search -> { toggleSearchbar(); true }
            R.id.action_export -> {
                val intent = Intent(requireContext(), ExportImportActivity::class.java)
                    .putExtra(ExportImportActivity.EXTRA_MODE, "export")
                startActivity(intent)
                true
            }
            R.id.action_settings -> { showSettingsMenu(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performLogout() {
        // Clear SharedPreferences
        val prefs = requireContext().getSharedPreferences("RezeptmomentPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Go back to Login
        val intent = Intent(requireContext(), Login::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)

        // Close current activity
        requireActivity().finish()
    }

}
