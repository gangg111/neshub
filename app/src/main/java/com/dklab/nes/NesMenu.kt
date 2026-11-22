package com.dklab.nes

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * In-game menu styled after the NES controller artwork (kontroler_nes.svg): light
 * housing, dark face plate, grey stripes, red labels. Entries: actions (dark SELECT-style
 * pill), toggles (round button, red when on), steppers (- value +) and submenus (pill with
 * an arrow). Submenus replace the page in place; WRÓĆ goes back one level.
 */
class NesMenu(private val activity: Activity) {

    class Page(val title: String, val items: () -> List<Item>)

    sealed class Item(val label: String) {
        /** With [preview], the first tap only highlights the entry and shows its frame
         *  (image, caption); a second tap runs it. Running an action closes the menu. */
        class Action(label: String, val preview: (() -> Pair<Bitmap?, String>)? = null,
                     val run: () -> Unit) : Item(label)
        class Toggle(label: String, var on: Boolean, val set: (Boolean) -> Unit) : Item(label)
        /** - value + ; holding a button repeats. */
        class Stepper(label: String, var value: Int, val min: Int, val max: Int,
                      val step: Int = 1, val format: (Int) -> String = { "%02d".format(it) },
                      val set: (Int) -> Unit) : Item(label)
        class Submenu(label: String, val page: Page) : Item(label)
        /** One row, several round on/off buttons with a letter inside (e.g. TURBO  B  A). */
        /** Round switches side by side; [radio] = exactly one on (pill-shaped, for words). */
        class MultiToggle(label: String, val keys: List<String>, val on: BooleanArray,
                          val radio: Boolean = false, val set: (Int, Boolean) -> Unit) : Item(label)
    }

    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var dialog: Dialog
    private lateinit var title: TextView
    private lateinit var body: LinearLayout
    private val stack = ArrayList<Page>()

    fun show(root: Page, onDismiss: () -> Unit): Dialog {
        dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val plate = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(PLATE, 6f)
            setPadding(px(16f), px(14f), px(16f), px(16f))
        }
        plate.addView(stripe(), lp(px(8f), bottom = 10f))
        title = TextView(activity).apply {
            setTextColor(LABEL)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        plate.addView(title, lp(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10f))
        plate.addView(stripe(), lp(px(8f), bottom = 14f))
        body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        plate.addView(body, lp(ViewGroup.LayoutParams.WRAP_CONTENT))

        val housing = FrameLayout(activity).apply {
            background = shape(HOUSING, 16f, OUTLINE, 3f)
            setPadding(px(14f), px(14f), px(14f), px(14f))
            addView(plate)
        }
        dialog.setContentView(ScrollView(activity).apply { addView(housing) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(px(340f).coerceAtMost(activity.resources.displayMetrics.widthPixels - px(24f)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
        }
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null); onDismiss() }
        open(root)
        dialog.show()
        return dialog
    }

    private fun open(page: Page) {
        stack.add(page)
        render()
    }

    private fun back() {
        stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) dialog.dismiss() else render()
    }

    /** Builds the current page. Items are re-read each time, so values are fresh. */
    private fun render() {
        val page = stack.last()
        title.text = page.title.uppercase()
        body.removeAllViews()

        // Frame preview: one shared slot, visible only while a previewed action is highlighted.
        val shot = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = shape(INK, 6f, STRIPE, 2f)
            setPadding(px(3f), px(3f), px(3f), px(3f))
        }
        val caption = TextView(activity).apply {
            setTextColor(HOUSING)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val previewBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(shot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(150f)))
            addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4f) })
        }
        val rows = ArrayList<View>()
        var selected: View? = null
        fun select(row: View?, item: Item.Action?) {
            selected = row
            rows.forEach { it.isSelected = it === row }
            (previewBox.parent as? ViewGroup)?.removeView(previewBox)
            if (row == null || item?.preview == null) return
            val (bmp, text) = item.preview.invoke()
            if (bmp != null) {
                shot.setImageDrawable(BitmapDrawable(activity.resources, bmp).apply { isFilterBitmap = false })
                shot.visibility = View.VISIBLE
            } else shot.visibility = View.GONE
            caption.text = "$text\nDotknij ponownie, aby potwierdzić"
            body.addView(previewBox, body.indexOfChild(row) + 1, lp(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10f))
        }

        for (item in page.items()) {
            val r = row(item) { row, it -> if (selected === row) true else { select(row, it); false } }
            if (item !is Item.Action) r.setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN && selected != null) select(null, null); false
            }
            rows.add(r)
            body.addView(r, lp(px(58f), bottom = 10f))
        }
        body.addView(stripe(), lp(px(8f), top = 4f, bottom = 12f))
        body.addView(row(Item.Action("WRÓĆ") {}) { _, _ -> true }.apply {
            setOnClickListener { back() }
        }, lp(px(58f)))
    }

    /** One menu entry: light housing, red label, control on the right. [gate] decides, for
     *  a previewed action, whether this tap runs it (true) or only highlighted it (false). */
    private fun row(item: Item, gate: (View, Item.Action) -> Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16f), 0, px(14f), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected), shape(HOUSING_PRESSED, 10f, LABEL, 3.5f))
                addState(intArrayOf(android.R.attr.state_pressed), shape(HOUSING_PRESSED, 10f, INK, 2.5f))
                addState(intArrayOf(), shape(HOUSING, 10f, INK, 2.5f))
            }
            isClickable = true
        }
        row.addView(TextView(activity).apply {
            text = item.label.uppercase()
            setTextColor(LABEL)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        when (item) {
            is Item.Action -> {
                row.addView(pill(""), LinearLayout.LayoutParams(px(58f), px(26f)))
                row.setOnClickListener {
                    if (item.preview == null || gate(row, item)) { dialog.dismiss(); item.run() }
                }
            }
            is Item.Submenu -> {
                row.addView(pill("›"), LinearLayout.LayoutParams(px(58f), px(26f)))
                row.setOnClickListener { open(item.page) }
            }
            is Item.Toggle -> {
                val state = smallText()
                val knob = View(activity)
                fun paint() {
                    state.text = if (item.on) "WŁ" else "WYŁ"
                    knob.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (item.on) RED else CROSS)
                        setStroke(px(2.5f), INK)
                    }
                }
                paint()
                row.addView(state)
                row.addView(knob, LinearLayout.LayoutParams(px(38f), px(38f)))
                row.setOnClickListener {
                    item.on = !item.on
                    item.set(item.on)
                    paint()
                }
            }
            is Item.MultiToggle -> {
                row.isClickable = false
                val paints = ArrayList<() -> Unit>()
                item.keys.forEachIndexed { i, key ->
                    val knob = TextView(activity).apply {
                        text = key
                        textSize = if (item.radio) 13f else 18f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        if (item.radio) { minWidth = px(42f); setPadding(px(12f), 0, px(12f), 0) }
                    }
                    fun paint() {
                        knob.setTextColor(if (item.on[i]) HOUSING else 0xFF8A8F92.toInt())
                        knob.background = GradientDrawable().apply {
                            if (item.radio) cornerRadius = px(21f).toFloat() else shape = GradientDrawable.OVAL
                            setColor(if (item.on[i]) RED else CROSS)
                            setStroke(px(2.5f), INK)
                        }
                    }
                    paint(); paints += ::paint
                    knob.setOnClickListener {
                        if (item.radio) {
                            if (item.on[i]) return@setOnClickListener
                            for (j in item.on.indices) item.on[j] = j == i
                            paints.forEach { it() }
                            item.set(i, true)
                        } else {
                            item.on[i] = !item.on[i]
                            item.set(i, item.on[i])
                            paint()
                        }
                    }
                    row.addView(knob, LinearLayout.LayoutParams(
                        if (item.radio) ViewGroup.LayoutParams.WRAP_CONTENT else px(42f), px(42f)).apply {
                        if (i > 0) marginStart = px(if (item.radio) 8f else 12f)
                    })
                }
            }
            is Item.Stepper -> {
                row.isClickable = false
                val value = TextView(activity).apply {
                    setTextColor(INK)
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    text = item.format(item.value)
                }
                fun step(d: Int) {
                    val v = (item.value + d).coerceIn(item.min, item.max)
                    if (v != item.value) { item.value = v; value.text = item.format(v); item.set(v) }
                }
                row.addView(stepButton("−") { step(-item.step) }, LinearLayout.LayoutParams(px(40f), px(40f)))
                row.addView(value, LinearLayout.LayoutParams(px(62f), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(stepButton("+") { step(item.step) }, LinearLayout.LayoutParams(px(40f), px(40f)))
            }
        }
        return row
    }

    /** Round dark button (like the D-pad plastic); repeats while held. */
    private fun stepButton(label: String, onStep: () -> Unit) = TextView(activity).apply {
        text = label
        setTextColor(HOUSING)
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CROSS_LIT); setStroke(px(2.5f), INK) })
            addState(intArrayOf(),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CROSS); setStroke(px(2.5f), INK) })
        }
        isClickable = true
        val repeat = object : Runnable {
            override fun run() { onStep(); handler.postDelayed(this, 70) }
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; onStep(); handler.postDelayed(repeat, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
            }
            true
        }
    }

    private fun pill(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(HOUSING)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = shape(CROSS, 14f, INK, 2.5f)
    }

    private fun smallText() = TextView(activity).apply {
        setTextColor(INK)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, px(10f), 0)
    }

    private fun stripe() = View(activity).apply { background = shape(STRIPE, 3f) }

    private fun lp(h: Int, top: Float = 0f, bottom: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
            topMargin = px(top); bottomMargin = px(bottom)
        }

    private fun shape(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 0f) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * dp
            if (strokeDp > 0f) setStroke(px(strokeDp), stroke)
        }

    private companion object {
        // kontroler_nes.svg
        val HOUSING = 0xFFE3E5E1.toInt()
        val HOUSING_PRESSED = 0xFFC4C6C1.toInt()
        val OUTLINE = 0xFFAEB1AA.toInt()
        val PLATE = 0xFF3D4143.toInt()
        val STRIPE = 0xFFA6A9AA.toInt()
        val CROSS = 0xFF303436.toInt()
        val CROSS_LIT = 0xFF5E6366.toInt()
        val INK = 0xFF1B1E20.toInt()
        val RED = 0xFFFF3530.toInt()
        val LABEL = 0xFFF52B26.toInt()
    }
}
