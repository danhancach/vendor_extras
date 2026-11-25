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

package com.android.axion.blur

import android.graphics.Matrix as AndroidMatrix
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Matrix as ComposeMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.toSize
import com.android.axion.blur.BlurEngine
import com.android.axion.blur.settings.AxBackdropBlurSettingsSpec
import com.android.axion.kotlin.settings.SettingsFlow
import com.android.axion.kotlin.settings.SettingsType
import kotlin.math.roundToInt

val LocalAxBlurEnabled = staticCompositionLocalOf { true }

/**
 * Ket qua ve surface local (draw-path Compose/BlurEngine — KHONG GPU/SF PRESENT).
 * NoSurface / AlphaZero khong emit callback.
 */
enum class AxBlurSurfacePaintKind {
    /** BlurEngine cross-window / source, surfaceAlpha>0. */
    NativeBackdrop,
    /** fallbackColor ve that khi blur off / unsupported / draw fail. */
    FallbackSurfaceRendered,
}

/**
 * Listener sau native backdrop draw (cross-window/source, surfaceAlpha>0).
 * KHONG fire khi alpha_zero / fallback-only / blur disabled.
 * Giu cho caller cu; open-glass uu tien [LocalAxBlurDestinationSurfaceDrawListener].
 */
val LocalAxBlurNativeBackdropDrawListener =
    staticCompositionLocalOf<((widthPx: Int, heightPx: Int, surfaceAlpha: Float) -> Unit)?> {
        null
    }

/**
 * Listener sau ve thanh cong surface α>0 (NativeBackdrop HOAC FallbackSurfaceRendered).
 * Chi fire khi listener != null — khong callback moi frame o surface thuong.
 */
val LocalAxBlurDestinationSurfaceDrawListener =
    staticCompositionLocalOf<
        ((widthPx: Int, heightPx: Int, surfaceAlpha: Float, kind: AxBlurSurfacePaintKind) -> Unit)?
    > {
        null
    }

object AxBlurSurfaceDefaults {
    @Composable
    fun surfaceColor(): Color = colorResource(R.color.ax_blur_surface_bright_expressive)

    @Composable
    fun surfaceColor(alpha: Float): Color =
        surfaceColor().copy(alpha = alpha.coerceIn(0f, 1f))

    @Composable
    fun tintColor(surfaceColor: Color? = null): Color {
        val tint = colorResource(R.color.ax_blur_surface_bright_expressive_tint)
        return surfaceColor?.copy(alpha = tint.alpha) ?: tint
    }

    @Composable
    fun tintColor(alpha: Float, surfaceColor: Color? = null): Color {
        val tint = tintColor(surfaceColor)
        return tint.copy(alpha = tint.alpha * alpha.coerceIn(0f, 1f))
    }

    /** Width of the [highlightBrush] rim. */
    val HighlightWidth: Dp = 1.dp

    /**
     * Specular rim for a glass surface: bright along the top edge and fading toward the bottom,
     * as if lit from above. It's what separates glass from a flat translucent fill. White in both
     * themes; a grey outline over a light frost reads as a dirty edge instead.
     */
    @Composable
    fun highlightBrush(): Brush {
        val dark = isSystemInDarkTheme()
        return Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.20f else 0.70f),
                Color.White.copy(alpha = if (dark) 0.04f else 0.15f),
            ),
        )
    }
}

@Composable
fun AxBlurLifecycle(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val parentEnabled = LocalAxBlurEnabled.current
    CompositionLocalProvider(
        LocalAxBlurEnabled provides (parentEnabled && enabled),
        content = content,
    )
}

@Composable
fun rememberAxBlurEnabled(
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
): Boolean {
    val resolver = LocalContext.current.contentResolver
    val globalSettings = remember(resolver) { SettingsFlow(resolver, SettingsType.GLOBAL) }
    val globalDefaultDisabled = !AxBlurProperties.defaultGlobalBlurEnabled
    val globalDisabled by remember(globalSettings, globalDefaultDisabled) {
        globalSettings.observeBoolean(Settings.Global.DISABLE_WINDOW_BLURS, globalDefaultDisabled)
    }.collectAsState(
        initial = globalSettings.getInt(
            Settings.Global.DISABLE_WINDOW_BLURS,
            if (globalDefaultDisabled) 1 else 0,
        ) != 0,
    )
    val enabledKey = settingsSpec.enabledKey
    val settingEnabled = if (enabledKey == null) {
        true
    } else {
        val secureSettings = remember(resolver) { SettingsFlow(resolver, SettingsType.SECURE) }
        val value by remember(secureSettings, enabledKey, settingsSpec.defaultEnabled) {
            secureSettings.observeBoolean(enabledKey, settingsSpec.defaultEnabled)
        }.collectAsState(
            initial = secureSettings.getInt(
                enabledKey,
                if (settingsSpec.defaultEnabled) 1 else 0,
            ) != 0,
        )
        value
    }
    return !globalDisabled && settingEnabled
}

@Composable
fun AxBlurSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    cornerRadius: Dp,
    enabled: Boolean = true,
    surfaceColor: Color? = null,
    tintColor: Color? = null,
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
    contentAlignment: Alignment = Alignment.TopStart,
    alpha: Float = 1f,
    highlight: Brush? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val resolvedSurfaceColor = surfaceColor ?: AxBlurSurfaceDefaults.surfaceColor()
    val resolvedTintColor = tintColor ?: AxBlurSurfaceDefaults.tintColor(surfaceColor)
    Box(
        modifier = modifier
            .clip(shape)
            .axBlurBackground(
                enabled = enabled,
                fallbackColor = resolvedSurfaceColor,
                tintColor = resolvedTintColor,
                cornerRadius = cornerRadius,
                settingsSpec = settingsSpec,
                alpha = alpha,
                highlight = highlight,
                shape = shape,
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
fun Modifier.axBlurBackground(
    enabled: Boolean,
    fallbackColor: Color,
    tintColor: Color,
    cornerRadius: Dp = Dp.Unspecified,
    /**
     * Ban kinh Y (elliptical). Unspecified → dung [cornerRadius] (scalar cu).
     * Anisotropic host scale: localRx≠localRy de sau scale visual rx≈ry.
     */
    cornerRadiusY: Dp = Dp.Unspecified,
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
    alpha: Float = 1f,
    highlight: Brush? = null,
    highlightWidth: Dp = AxBlurSurfaceDefaults.HighlightWidth,
    includeViewBackdrop: Boolean = true,
    /**
     * Sau native backdrop draw (cross-window/source, alpha>0).
     * null → dung [LocalAxBlurNativeBackdropDrawListener] neu co.
     * Mac dinh giu API cu — khong anh huong caller khong await open-glass.
     */
    onNativeBackdropDrawn: ((widthPx: Int, heightPx: Int, surfaceAlpha: Float) -> Unit)? = null,
    /**
     * Sau ve thanh cong surface α>0: NativeBackdrop hoac FallbackSurfaceRendered.
     * null → [LocalAxBlurDestinationSurfaceDrawListener]. KHONG goi native ACK khi fallback.
     */
    onDestinationSurfaceDrawn:
        ((
            widthPx: Int,
            heightPx: Int,
            surfaceAlpha: Float,
            kind: AxBlurSurfacePaintKind,
        ) -> Unit)? =
            null,
    shape: Shape? = null,
): Modifier {
    val density = LocalDensity.current
    val view = LocalView.current
    val blurEnabled = LocalAxBlurEnabled.current && enabled
    val blur = remember(view, settingsSpec) {
        BlurEngine(view).apply {
            useSettings(settingsSpec)
            setIncludeViewBackdrop(includeViewBackdrop)
            setEnabled(blurEnabled)
        }
    }
    val tintArgb = tintColor.toArgb()
    val cornerRadii = remember { FloatArray(8) }
    val requestedCornerRadiusXPx = if (cornerRadius.isSpecified) {
        with(density) { cornerRadius.toPx() }
    } else {
        Float.NaN
    }
    val requestedCornerRadiusYPx = if (cornerRadiusY.isSpecified) {
        with(density) { cornerRadiusY.toPx() }
    } else {
        Float.NaN
    }
    val surfaceAlpha = alpha.coerceIn(0f, 1f)
    val blurAlpha = (surfaceAlpha * 255).toInt()
    val highlightWidthPx = with(density) { highlightWidth.toPx() }
    val nativeBackdropListener =
        onNativeBackdropDrawn ?: LocalAxBlurNativeBackdropDrawListener.current
    val destinationSurfaceListener =
        onDestinationSurfaceDrawn ?: LocalAxBlurDestinationSurfaceDrawListener.current

    DisposableEffect(blur) {
        onDispose(blur::dispose)
    }

    // Alpha/visibility native DOC LAP draw: underPeekClip peek=0 skip drawContent
    // → neu chi setAlpha trong draw, BackgroundBlurDrawable giu alpha cu tren SF.
    // BlurEngine.setAlpha idempotent (same value no-op) — khong loop invalidate.
    SideEffect {
        blur.setIncludeViewBackdrop(includeViewBackdrop)
        blur.setEnabled(blurEnabled)
        blur.setAlpha(blurAlpha)
    }

    return axBlurFrameTracking(blur, blurEnabled).drawWithContent {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        // A non-null shape draws the blur and the fallback with its own outline, so per-corner
        // radii (a dialog, a bottom sheet) are honoured instead of one uniform radius.
        val outline = shape?.createOutline(size, layoutDirection, this)
        val cornerRadiusXPx = when {
            requestedCornerRadiusXPx.isFinite() -> requestedCornerRadiusXPx
            outline is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            outline is Outline.Rectangle -> 0f
            else -> size.minDimension * 0.5f
        }
        val cornerRadiusYPx =
            if (requestedCornerRadiusYPx.isFinite()) {
                requestedCornerRadiusYPx
            } else {
                cornerRadiusXPx
            }
        val elliptical = kotlin.math.abs(cornerRadiusXPx - cornerRadiusYPx) > 0.05f
        val drewBlur = if (blurEnabled && width > 0 && height > 0) {
            // Draw-time van setAlpha — frame animate/moving dung ngay trong pass ve.
            blur.setAlpha(blurAlpha)
            blur.setOverlayColor(tintArgb)
            var result = false
            drawIntoCanvas { canvas ->
                result = when (outline) {
                    is Outline.Rounded -> {
                        val rect = outline.roundRect
                        cornerRadii[0] = rect.topLeftCornerRadius.x
                        cornerRadii[1] = rect.topLeftCornerRadius.y
                        cornerRadii[2] = rect.topRightCornerRadius.x
                        cornerRadii[3] = rect.topRightCornerRadius.y
                        cornerRadii[4] = rect.bottomRightCornerRadius.x
                        cornerRadii[5] = rect.bottomRightCornerRadius.y
                        cornerRadii[6] = rect.bottomLeftCornerRadius.x
                        cornerRadii[7] = rect.bottomLeftCornerRadius.y
                        blur.draw(
                            canvas.nativeCanvas,
                            rect.left.roundToInt(),
                            rect.top.roundToInt(),
                            rect.right.roundToInt(),
                            rect.bottom.roundToInt(),
                            cornerRadii,
                        )
                    }
                    is Outline.Rectangle -> {
                        val rect = outline.rect
                        blur.draw(
                            canvas.nativeCanvas,
                            rect.left.roundToInt(),
                            rect.top.roundToInt(),
                            rect.right.roundToInt(),
                            rect.bottom.roundToInt(),
                            0f,
                        )
                    }
                    else -> if (elliptical) {
                        val radii =
                            floatArrayOf(
                                cornerRadiusXPx,
                                cornerRadiusYPx,
                                cornerRadiusXPx,
                                cornerRadiusYPx,
                                cornerRadiusXPx,
                                cornerRadiusYPx,
                                cornerRadiusXPx,
                                cornerRadiusYPx,
                            )
                        blur.draw(
                            canvas.nativeCanvas,
                            0,
                            0,
                            width,
                            height,
                            radii,
                        )
                    } else {
                        blur.draw(
                            canvas.nativeCanvas,
                            0,
                            0,
                            width,
                            height,
                            cornerRadiusXPx,
                        )
                    }
                }
            }
            result
        } else {
            false
        }
        val canReportSurface = surfaceAlpha > 0f && width > 0 && height > 0
        // Native backdrop that: surfaceAlpha>0 + cross-window/source (khong alpha_zero).
        val nativeBackdropOk =
            canReportSurface && drewBlur && blur.lastDrawWasNativeBackdrop()
        if (nativeBackdropOk) {
            nativeBackdropListener?.invoke(width, height, surfaceAlpha)
            destinationSurfaceListener?.invoke(
                width,
                height,
                surfaceAlpha,
                AxBlurSurfacePaintKind.NativeBackdrop,
            )
        }
        // Blur off / unsupported / draw fail: ve fallbackColor (khong tang opaque).
        // Chi ACK Fallback khi fallback that su da ve — khong fake native.
        if (!drewBlur && canReportSurface) {
            // Fades with the surface: a caller animating `alpha` out shouldn't leave the opaque
            // fallback behind on devices without blur.
            if (outline != null) {
                drawOutline(outline, color = fallbackColor, alpha = surfaceAlpha)
            } else {
                drawRoundRect(
                    color = fallbackColor,
                    cornerRadius = CornerRadius(cornerRadiusXPx, cornerRadiusYPx),
                    alpha = surfaceAlpha,
                )
            }
            destinationSurfaceListener?.invoke(
                width,
                height,
                surfaceAlpha,
                AxBlurSurfacePaintKind.FallbackSurfaceRendered,
            )
        }
        drawContent()
        if (highlight != null && surfaceAlpha > 0f && highlightWidthPx > 0f) {
            // Stroke centred on a rect inset by half its width, so the rim sits inside the clip.
            val inset = highlightWidthPx / 2f
            drawRoundRect(
                brush = highlight,
                topLeft = Offset(inset, inset),
                size = Size(size.width - highlightWidthPx, size.height - highlightWidthPx),
                cornerRadius =
                    CornerRadius(
                        (cornerRadiusXPx - inset).coerceAtLeast(0f),
                        (cornerRadiusYPx - inset).coerceAtLeast(0f),
                    ),
                style = Stroke(width = highlightWidthPx),
                alpha = surfaceAlpha,
            )
        }
    }
}

private fun Modifier.axBlurFrameTracking(blur: BlurEngine, enabled: Boolean): Modifier =
    this then AxBlurFrameTrackingElement(blur, enabled)

private data class AxBlurFrameTrackingElement(
    val blur: BlurEngine,
    val enabled: Boolean,
) : ModifierNodeElement<AxBlurFrameTrackingNode>() {
    override fun create() = AxBlurFrameTrackingNode(blur, enabled)

    override fun update(node: AxBlurFrameTrackingNode) {
        node.update(blur, enabled)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "axBlurFrameTracking"
        properties["enabled"] = enabled
    }
}

private class AxBlurFrameTrackingNode(
    private var blur: BlurEngine,
    private var enabled: Boolean,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    LayoutAwareModifierNode,
    ObserverModifierNode {

    private var lastCoordinates: LayoutCoordinates? = null
    private var geometry = AxBlurFrameGeometry()
    private var hostView: View? = null
    private var observingPreDraw = false
    private var skipNextHostDirty = false
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (isAttached && enabled) {
            val viewDirty = hostView?.isDirty == true
            lastCoordinates?.let { updateCoordinates(it) }
            if (viewDirty) {
                if (skipNextHostDirty) {
                    skipNextHostDirty = false
                } else {
                    skipNextHostDirty = true
                    invalidateDraw()
                }
            } else {
                skipNextHostDirty = false
            }
        }
        true
    }

    override val shouldAutoInvalidate = false

    private val composeMatrix = ComposeMatrix()
    private val androidMatrix = AndroidMatrix()
    private val androidMatrixValues = FloatArray(9)

    fun update(blur: BlurEngine, enabled: Boolean) {
        val blurChanged = this.blur !== blur
        val enabledChanged = this.enabled != enabled
        if (!blurChanged && !enabledChanged) return
        if (blurChanged) {
            this.blur.clearSourceSampleTransform()
            this.blur = blur
            // Day geometry hien tai sang engine moi.
            publishSampleTransform()
        }
        if (enabledChanged) {
            this.enabled = enabled
            if (!enabled) {
                blur.clearSourceSampleTransform()
            } else {
                publishSampleTransform()
            }
            updatePreDrawObserver()
        }
        invalidateDraw()
    }

    override fun onAttach() {
        onObservedReadsChanged()
    }

    override fun onDetach() {
        removePreDrawObserver()
        blur.clearSourceSampleTransform()
        hostView = null
        lastCoordinates = null
        geometry = AxBlurFrameGeometry()
        skipNextHostDirty = false
    }

    override fun onObservedReadsChanged() {
        observeReads {
            updatePreDrawObserver()
            lastCoordinates?.let { updateCoordinates(it) }
        }
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        updateCoordinates(coordinates)
    }

    override fun onRemeasured(size: IntSize) {
        val newSize = size.toSize()
        val nextGeometry = geometry.copy(size = newSize)
        if (geometry != nextGeometry) {
            geometry = nextGeometry
            invalidateDraw()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        updateCoordinates(coordinates)
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }

    private fun updateCoordinates(coordinates: LayoutCoordinates) {
        lastCoordinates = coordinates
        if (!coordinates.isAttached) {
            geometry = AxBlurFrameGeometry()
            blur.clearSourceSampleTransform()
            invalidateDraw()
            return
        }
        val root = coordinates.findRootCoordinates()
        // Goc local (0,0) trong root — dung de invalidate khi doi cho; transform day du qua publish.
        val originInRoot = coordinates.localToRoot(Offset.Zero)
        val newSize = Size(
            coordinates.size.width.toFloat(),
            coordinates.size.height.toFloat(),
        )
        val newWindowId = currentWindowId()
        val nextGeometry = AxBlurFrameGeometry(
            position = originInRoot,
            size = newSize,
            windowId = newWindowId,
            rootHash = System.identityHashCode(root),
        )
        if (geometry != nextGeometry) {
            geometry = nextGeometry
            publishSampleTransform()
            invalidateDraw()
        } else {
            // Size/goc giong bounding nhung scale/clip co the doi — luon refresh matrix.
            publishSampleTransform()
        }
    }

    private fun publishSampleTransform() {
        if (!enabled) return
        val coordinates = lastCoordinates
        if (coordinates == null || !coordinates.isAttached) {
            blur.clearSourceSampleTransform()
            return
        }
        val root = coordinates.findRootCoordinates()
        composeMatrix.reset()
        // Compose 1.12: transformFrom(source, Matrix) — KHONG truyen FloatArray (.values).
        // host/root → local node (gom scale/clip/transform), khong chi dich bounding box.
        coordinates.transformFrom(root, composeMatrix)
        composeMatrixToAndroid(composeMatrix, androidMatrix, androidMatrixValues)
        blur.setSourceSampleTransform(androidMatrix)
    }

    private data class AxBlurFrameGeometry(
        val position: Offset = Offset.Unspecified,
        val size: Size = Size.Unspecified,
        val windowId: Any? = null,
        val rootHash: Int = 0,
    )

    private fun currentWindowId(): Any? {
        return hostView?.windowId ?: currentValueOf(LocalView).windowId
    }

    private fun updatePreDrawObserver() {
        val view = if (isAttached && enabled) currentValueOf(LocalView) else null
        if (hostView !== view) {
            removePreDrawObserver()
            hostView = view
            skipNextHostDirty = false
        }
        if (view != null && !observingPreDraw) {
            view.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            observingPreDraw = true
        }
        if (view == null) {
            removePreDrawObserver()
        }
    }

    private fun removePreDrawObserver() {
        if (!observingPreDraw) return
        hostView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        observingPreDraw = false
    }
}

/** Compose Matrix 4x4 (column-major) → android.graphics.Matrix 3x3. */
private fun composeMatrixToAndroid(
    compose: ComposeMatrix,
    out: AndroidMatrix,
    scratch9: FloatArray,
) {
    val v = compose.values
    scratch9[AndroidMatrix.MSCALE_X] = v[ComposeMatrix.ScaleX]
    scratch9[AndroidMatrix.MSKEW_X] = v[ComposeMatrix.SkewX]
    scratch9[AndroidMatrix.MTRANS_X] = v[ComposeMatrix.TranslateX]
    scratch9[AndroidMatrix.MSKEW_Y] = v[ComposeMatrix.SkewY]
    scratch9[AndroidMatrix.MSCALE_Y] = v[ComposeMatrix.ScaleY]
    scratch9[AndroidMatrix.MTRANS_Y] = v[ComposeMatrix.TranslateY]
    scratch9[AndroidMatrix.MPERSP_0] = v[ComposeMatrix.Perspective0]
    scratch9[AndroidMatrix.MPERSP_1] = v[ComposeMatrix.Perspective1]
    scratch9[AndroidMatrix.MPERSP_2] = v[ComposeMatrix.Perspective2]
    out.setValues(scratch9)
}
