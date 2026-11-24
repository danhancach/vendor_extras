/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.axion.compose.host

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import com.android.axion.compose.lifecycle.AxComposeInitializer
import com.android.axion.compose.lifecycle.AxViewCompositionStrategy

/**
 * A [FrameLayout] host that safely runs Compose content in any context —
 * WindowManager overlays, services, custom Views — without requiring an Activity.
 *
 * Delegates lifecycle setup to [AxComposeInitializer]. Safe to call [setContent]
 * before or after the view is added to a window.
 * The difference with platform is, this one triggers [onAttachedToWindow] before the super method
 * seen in [frameworks/base/packages/SystemUI/src/com/android/systemui/biometrics/AuthContainerView.java]
 * while the platform docs suggests to add [AxComposeInitializer.onAttachedToWindow] after the super method
 *
 * Usage:
 * ```
 * val host = AxComposeView(context)
 * host.setContent { SampleComposable() }
 * windowManager.addView(host, layoutParams)
 * ```
 */
open class AxComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Callback goi ngay khi nhan ACTION_DOWN o cap View truoc khi dispatch xuong cay Compose. */
    var onDispatchTouchDown: (() -> Unit)? = null

    /** Vung cham chap nhan khi o che do collapsed. Neu null hoac ngoai vung, tra ve false de khong nuot touch he thong. */
    var touchBoundsProvider: (() -> Rect?)? = null

    /** Cho biet co dang o che do mo rong khong. */
    var isExpandedProvider: (() -> Boolean)? = null

    init {
        // Cho phep Compose ve vuot ra ngoai khung wrap_content / match_constraint ma khong bi cat xen
        clipChildren = false
        clipToPadding = false
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        clipChildren = false
        clipToPadding = false
        if (child is ViewGroup) {
            child.clipChildren = false
            child.clipToPadding = false
            child.setOnHierarchyChangeListener(object : OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, c: View?) {
                    (parent as? ViewGroup)?.let {
                        it.clipChildren = false
                        it.clipToPadding = false
                    }
                    (c as? ViewGroup)?.let {
                        it.clipChildren = false
                        it.clipToPadding = false
                    }
                }
                override fun onChildViewRemoved(parent: View?, c: View?) {}
            })
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val isExpanded = isExpandedProvider?.invoke() ?: true
        val bounds = if (!isExpanded) touchBoundsProvider?.invoke() else null
        val x = ev.x.toInt()
        val y = ev.y.toInt()
        val inPill = bounds == null || bounds.contains(x, y)

        if (!isExpanded && bounds != null && ev.actionMasked == MotionEvent.ACTION_DOWN && !inPill) {
            // Ngoai pill: nhuong touch cho keyguard/panel (mo khoa vuot).
            return false
        }

        if (ev.actionMasked == MotionEvent.ACTION_DOWN && inPill) {
            onDispatchTouchDown?.invoke()
            // Chan NPVC onIntercept truoc khi Compose xu ly.
            requestDisallowInterceptTouchEvent(true)
        }

        val handled = super.dispatchTouchEvent(ev)
        // Quan trong: du Compose khong consume, van claim touch trong pill —
        // neu return false, NPVC.onTouch van keo shade → PRIMARY_BOUNCER.
        if (!isExpanded && inPill) {
            return true
        }
        return handled
    }

    private val composeView = ComposeView(context).also {
        it.clipChildren = false
        it.clipToPadding = false
        it.setViewCompositionStrategy(AxViewCompositionStrategy)
        addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        AxComposeInitializer.onAttachedToWindow(this)
        compositionContext = createLifecycleAwareWindowRecomposer()
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        AxComposeInitializer.onDetachedFromWindow(this)
        compositionContext = null
    }

    fun setContent(content: @Composable () -> Unit) {
        composeView.setContent(content)
    }
}
