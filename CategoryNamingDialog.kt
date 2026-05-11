package com.example.rezeptmoment.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.rezeptmoment.CreateRecipeActivity

class CategoryNamingDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Name your categories")
            .setMessage("Would you like to organize your recipes by naming categories now?")
            .setPositiveButton("Yes") { _, _ ->
                // Go to OrganizeCategoriesActivity
                startActivity(
                    android.content.Intent(requireContext(),
                        com.example.rezeptmoment.ui.OrganizeCategoriesActivity::class.java)
                )
            }
            .setNegativeButton("Later", null)
            .create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? CreateRecipeActivity)?.onCategoryDialogDismissed()
    }
}