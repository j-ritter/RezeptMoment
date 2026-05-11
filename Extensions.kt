// app/src/main/java/com/example/rezeptmoment/ui/util/Extensions.kt
package com.example.rezeptmoment.ui.util

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.FloatRange
import androidx.annotation.StringRes
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

/* ---------------- File helpers (getDocumentsDirectory / save image) ---------------- */

val Context.documentsDir: File
    get() = filesDir // iOS Documents -> Android internal filesDir

/**
 * Saves JPEG bytes as <uuid>.jpg in filesDir.
 * Returns the File on success, null on failure.
 */
fun Context.saveImageToFilesDir(imageBytes: ByteArray, fileNameNoExt: String): File? {
    val out = File(documentsDir, "$fileNameNoExt.jpg")
    return runCatching {
        FileOutputStream(out).use { it.write(imageBytes) }
        out
    }.getOrNull()
}

/* ---------------- Image resize (resizeImageKeepingAspectRatio) ---------------- */

/**
 * Scales a bitmap keeping aspect ratio to targetWidth.
 * Returns a new bitmap (ARGB_8888). If width already <= targetWidth, returns original.
 */
fun Bitmap.scaleToWidth(@FloatRange(from = 1.0) targetWidth: Float): Bitmap {
    if (width <= targetWidth) return this
    val scale = targetWidth / width.toFloat()
    val targetH = (height * scale).roundToInt()
    return Bitmap.createScaledBitmap(this, targetWidth.roundToInt(), targetH, true)
}

/**
 * Convenience to decode and scale a JPEG at path to a target width (px).
 */
fun decodeScaledBitmap(path: String, targetWidthPx: Int): Bitmap? {
    val original = BitmapFactory.decodeFile(path) ?: return null
    return original.scaleToWidth(targetWidthPx.toFloat())
}

/* ---------------- Grid “cell width” calculator (createCellWidth) ---------------- */

/**
 * Maps the Swift createCellWidth logic to Android.
 * Returns a suggested column count based on container width and a “useLargeDesign” flag.
 *
 * @param containerWidthPx – measured width available for the grid (in px)
 * @param useLargeDesign – mirror of the iOS UserDefaults flag
 */
fun computeGridColumns(containerWidthPx: Int, useLargeDesign: Boolean): Int {
    // The thresholds mirror the Swift code’s buckets (in px instead of “points”).
    val w = containerWidthPx
    return if (!useLargeDesign) {
        when {
            w <= 300 -> 3
            w <= 370 -> 4
            w <= 550 -> 5
            w <= 700 -> 6
            w <= 900 -> 7
            w <= 1100 -> 8
            w <= 1250 -> 9
            else -> 10
        }
    } else {
        when {
            w <= 370 -> 3
            w <= 550 -> 4
            w <= 700 -> 5
            w <= 900 -> 6
            w <= 1100 -> 7
            w <= 1250 -> 8
            else -> 9
        }
    }
}

/* ---------------- Clear temp directory (clearTmpDirectory) ---------------- */

fun Context.clearTempDirectory() {
    // Use cacheDir as rough equivalent of iOS temporaryDirectory
    cacheDir?.listFiles()?.forEach { child ->
        runCatching {
            if (child.isDirectory) child.deleteRecursively() else child.delete()
        }
    }
}

/* ---------------- String -> Float with locale fallback (floatValue) ---------------- */

fun String.toFloatLenient(default: Float = 0f): Float {
    val trimmed = trim()
    if (trimmed.isEmpty()) return default

    // Try current locale
    NumberFormat.getNumberInstance(Locale.getDefault()).parseOrNull(trimmed)?.let {
        return it.toFloat()
    }
    // Try dot
    NumberFormat.getNumberInstance(Locale.US).parseOrNull(trimmed)?.let {
        return it.toFloat()
    }
    // Try comma: replace comma with dot
    return trimmed.replace(',', '.').toFloatOrNull() ?: default
}

private fun NumberFormat.parseOrNull(text: String): Number? {
    val pos = ParsePosition(0)
    val num = parse(text, pos)
    return if (pos.index == text.length) num else null
}

/* ---------------- Dialog text validation (UIAlertController.textDidChange) ---------------- */

/**
 * Attach validation to a dialog with three fields:
 * 0: quantity (must be float), 1: (unused here), 2: ingredient (non-empty)
 *
 * @param positiveButton The confirm button to enable/disable.
 * @param quantityField  EditText for quantity.
 * @param ingredientField EditText for ingredient name.
 */
fun Dialog.attachIngredientValidation(
    positiveButton: Button,
    quantityField: EditText,
    ingredientField: EditText
) {
    fun validate() {
        val okQuantity = quantityField.text.toString().toFloatLenient(Float.NaN).isFinite()
        val okIngredient = ingredientField.text?.isNotBlank() == true
        positiveButton.isEnabled = okQuantity && okIngredient
    }
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = validate()
        override fun afterTextChanged(s: Editable?) {}
    }
    quantityField.addTextChangedListener(watcher)
    ingredientField.addTextChangedListener(watcher)
    // Initial state
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    validate()
}

/* ---------------- Small view helper ---------------- */

fun View.visibleOrGone(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

fun Float.formatQuantity(): String {
    val rounded = round(this * 10f) / 10f          // keep 1 decimal
    val s = String.format(Locale.US, "%.1f", rounded) // force '.' as decimal sep
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

fun TextView.setProgressText(@StringRes resId: Int, arg: Any) {
    text = context.getString(resId, arg)
}
