package com.example.rezeptmoment

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import com.example.rezeptmoment.UnlockPremiumViewModel
import com.google.android.material.button.MaterialButton

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [fragment_unlock_premium.newInstance] factory method to
 * create an instance of this fragment.
 */
class fragment_unlock_premium : Fragment() {
    private val viewModel: UnlockPremiumViewModel by viewModels()
    private lateinit var headlineLabel: TextView
    private lateinit var contentLabel: TextView
    private lateinit var makePurchaseButton: Button
    private lateinit var iconView: ImageView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        headlineLabel = view.findViewById(R.id.headlineLabelUnlockPremium)
        contentLabel = view.findViewById(R.id.contentLabelUnlockPremium)
        makePurchaseButton = view.findViewById(R.id.makePurchaseButtonUnlockPremium)
        iconView = view.findViewById(R.id.iconViewUnlockPremium)

        viewModel.load()
        viewModel.headlineText.observe(viewLifecycleOwner) { headlineLabel.text = it }
        viewModel.bodyText.observe(viewLifecycleOwner) { contentLabel.text = it }
        viewModel.buttonText.observe(viewLifecycleOwner) { makePurchaseButton.text = it }
        viewModel.buttonLoading.observe(viewLifecycleOwner) { makePurchaseButton.isEnabled = !it }
        viewModel.showError.observe(viewLifecycleOwner) { error ->
            if (!error.isNullOrBlank()) {
                AlertDialog.Builder(requireContext())
                    .setTitle(error)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        makePurchaseButton.setOnClickListener {
            if (!viewModel.showRestore.value!!) {
                viewModel.purchase(requireActivity())
            } else {
                viewModel.restorePurchases()
            }
        }

        // Restore/cancel navigation (matches iOS "close" button)
        val toolbar = requireActivity().findViewById<androidx.appcompat.widget.Toolbar>(R.id.custom_toolbar_recipes)
        toolbar?.menu?.clear()
        toolbar?.inflateMenu(R.menu.premium_menu)
        toolbar?.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_cancel) {
                requireActivity().onBackPressedDispatcher.onBackPressed()
                true
            } else false
        }
        val closeButton = view.findViewById<MaterialButton>(R.id.closeButtonUnlockPremium)
        closeButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

    }
}