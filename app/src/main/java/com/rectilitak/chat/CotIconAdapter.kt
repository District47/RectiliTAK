package com.rectilitak.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rectilitak.chat.plugin.R

/**
 * Spinner adapter that shows MIL-STD-2525 icon previews next to CoT type names.
 */
class CotIconAdapter(
    private val context: Context,
    private val items: List<Pair<String, String>>  // label to CoT type
) : BaseAdapter() {

    companion object {
        private const val TAG = "CotIconAdapter"
        private const val ICON_SIZE = 64
    }

    private val iconCache = mutableMapOf<String, Bitmap?>()

    init {
        Thread {
            items.forEach { (_, cotType) ->
                iconCache[cotType] = renderIcon(cotType)
            }
        }.start()
    }

    private fun renderIcon(cotType: String): Bitmap? {
        try {
            val sidc = com.atakmap.android.icons.Icon2525cTypeResolver
                .mil2525cFromCotType(cotType) ?: return null

            // Use the c5isr renderer (newer)
            val renderer = armyc2.c5isr.renderer.MilStdIconRenderer.getInstance()
            val modifiers = HashMap<String, String>()
            val attributes = HashMap<String, String>()
            attributes["PIXELSIZE"] = ICON_SIZE.toString()
            val img = renderer.RenderIcon(sidc, modifiers, attributes)
            if (img != null) {
                return img.image
            }
        } catch (e: Exception) {
            Log.w(TAG, "c5isr render failed for $cotType: ${e.message}")
        }

        try {
            val sidc = com.atakmap.android.icons.Icon2525cTypeResolver
                .mil2525cFromCotType(cotType) ?: return null

            // Fallback to c2sd renderer (older)
            val renderer = armyc2.c2sd.renderer.MilStdIconRenderer.getInstance()
            val modifiers = android.util.SparseArray<String>()
            val attributes = android.util.SparseArray<String>()
            val img = renderer.RenderIcon(sidc, modifiers, attributes)
            if (img != null) {
                return img.image
            }
        } catch (e: Exception) {
            Log.w(TAG, "c2sd render failed for $cotType: ${e.message}")
        }

        return null
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Pair<String, String> = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView)
    }

    private fun createView(position: Int, convertView: View?): View {
        val layout = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ICON_SIZE, ICON_SIZE).apply {
                    marginEnd = 12
                }
                tag = "icon"
            })
            addView(TextView(context).apply {
                setTextColor(context.resources.getColor(R.color.chat_text, null))
                textSize = 14f
                tag = "label"
            })
        }

        val (label, cotType) = items[position]
        val iconView = layout.findViewWithTag<ImageView>("icon")
        val labelView = layout.findViewWithTag<TextView>("label")

        labelView.text = label

        val bitmap = iconCache[cotType]
        if (bitmap != null) {
            iconView.setImageBitmap(bitmap)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.setImageBitmap(null)
            iconView.visibility = View.INVISIBLE
        }

        return layout
    }
}
