package com.latchi.iptv.utils

import android.app.Activity
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.R

/**
 * Central Android TV focus enabler.
 * Keeps phone touch UX intact and upgrades TV/keyboard/remote navigation globally.
 */
object TvFocusHelper {

    fun setup(activity: Activity, requestInitialFocus: Boolean = true) {
        if (!TvUtils.isTv(activity)) return
        val root = activity.findViewById<ViewGroup?>(android.R.id.content) ?: return
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        setupTree(root)
        if (requestInitialFocus && activity.currentFocus == null) {
            root.postDelayed({ findFirstFocusable(root)?.requestFocus() }, 180)
        }
    }

    fun setupTree(root: View) {
        val context = root.context
        if (!TvUtils.isTv(context)) return
        applyToView(root)
        if (root is ViewGroup) {
            root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            for (i in 0 until root.childCount) setupTree(root.getChildAt(i))
        }
    }

    fun setupRecycler(recyclerView: RecyclerView) {
        if (!TvUtils.isTv(recyclerView.context)) return
        recyclerView.isFocusable = true
        recyclerView.isFocusableInTouchMode = false
        recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        recyclerView.preserveFocusAfterLayout = true
    }

    fun setupFocusableItem(view: View, scale: Float = 1.06f) {
        if (!TvUtils.isTv(view.context)) return
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { view.foreground = view.context.getDrawable(R.drawable.focus_selector) } catch (_: Exception) {}
        }
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(140)
                .start()
        }
    }

    fun handleKey(activity: Activity, keyCode: Int, event: KeyEvent?): Boolean {
        if (!TvUtils.isTv(activity) || event?.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(activity, View.FOCUS_RIGHT)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(activity, View.FOCUS_LEFT)
            KeyEvent.KEYCODE_DPAD_UP -> moveFocus(activity, View.FOCUS_UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(activity, View.FOCUS_DOWN)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                activity.currentFocus?.performClick() ?: false
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                activity.onBackPressed()
                true
            }
            else -> false
        }
    }

    private fun moveFocus(activity: Activity, direction: Int): Boolean {
        val current = activity.currentFocus ?: return false
        val next = current.focusSearch(direction) ?: return false
        return if (next.visibility == View.VISIBLE && next.isEnabled) {
            next.requestFocus()
            true
        } else false
    }

    private fun applyToView(view: View) {
        if (view is RecyclerView) {
            setupRecycler(view)
            return
        }
        val shouldFocus = view.isClickable || view.hasOnClickListeners()
        if (shouldFocus) setupFocusableItem(view)
    }

    private fun findFirstFocusable(view: View): View? {
        if (view.visibility == View.VISIBLE && view.isFocusable && view.isEnabled) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstFocusable(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
