package com.rectilitak.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rectilitak.chat.plugin.R

data class IconEntry(
    val label: String,
    val cotType: String,
    val drawableResId: Int = 0,
    val isAprs: Boolean = false
)

class CotIconAdapter(
    private val context: Context,
    private val pluginContext: Context,
    private val items: List<IconEntry>
) : BaseAdapter() {

    companion object {
        private const val TAG = "CotIconAdapter"
        private const val ICON_SIZE = 48
    }

    // Lazy icon cache — loaded on first access per icon
    private val iconCache = mutableMapOf<String, Bitmap?>()
    private val loadedKeys = mutableSetOf<String>()

    private fun getIcon(entry: IconEntry): Bitmap? {
        if (entry.cotType in loadedKeys) return iconCache[entry.cotType]

        loadedKeys.add(entry.cotType)
        val bitmap = if (entry.drawableResId > 0) {
            loadAprsIcon(entry)
        } else {
            renderMilStdIcon(entry.cotType)
        }
        iconCache[entry.cotType] = bitmap
        return bitmap
    }

    private fun loadAprsIcon(entry: IconEntry): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 1 // 64px icons, keep as-is
            }
            BitmapFactory.decodeResource(pluginContext.resources, entry.drawableResId, opts)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load APRS icon ${entry.label}: ${e.message}")
            null
        }
    }

    private fun renderMilStdIcon(cotType: String): Bitmap? {
        try {
            val sidc2525c = com.atakmap.android.icons.Icon2525cTypeResolver
                .mil2525cFromCotType(cotType) ?: return null
            val sidc2525d = com.atakmap.android.icons.Icon2525cTypeResolver
                .get2525DFrom2525C(sidc2525c)
            if (sidc2525d != null && sidc2525d.length >= 20) {
                val renderer = armyc2.c5isr.renderer.MilStdIconRenderer.getInstance()
                val modifiers = HashMap<String, String>()
                val attributes = HashMap<String, String>()
                attributes["PIXELSIZE"] = ICON_SIZE.toString()
                val img = renderer.RenderIcon(sidc2525d, modifiers, attributes)
                if (img?.image != null) return img.image
            }
        } catch (e: Exception) {
            Log.w(TAG, "c5isr failed for $cotType: ${e.message}")
        }
        try {
            val sidc = com.atakmap.android.icons.Icon2525cTypeResolver
                .mil2525cFromCotType(cotType) ?: return null
            val renderer = armyc2.c2sd.renderer.MilStdIconRenderer.getInstance()
            val img = renderer.RenderIcon(sidc, SparseArray<String>(), SparseArray<String>())
            if (img?.image != null) return img.image
        } catch (e: Exception) {
            Log.w(TAG, "c2sd failed for $cotType: ${e.message}")
        }
        return null
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): IconEntry = items[position]
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
            setPadding(8, 6, 8, 6)
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ICON_SIZE, ICON_SIZE).apply { marginEnd = 12 }
                tag = "icon"
            })
            addView(TextView(context).apply {
                setTextColor(pluginContext.resources.getColor(R.color.chat_text, null))
                textSize = 13f
                tag = "label"
            })
        }

        val entry = items[position]
        val iconView = layout.findViewWithTag<ImageView>("icon")
        val labelView = layout.findViewWithTag<TextView>("label")

        val prefix = if (entry.isAprs) "[APRS] " else ""
        labelView.text = "$prefix${entry.label}"

        val bitmap = getIcon(entry)
        if (bitmap != null) {
            iconView.setImageBitmap(bitmap)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.setImageBitmap(null)
            iconView.visibility = View.GONE
        }

        return layout
    }
}
