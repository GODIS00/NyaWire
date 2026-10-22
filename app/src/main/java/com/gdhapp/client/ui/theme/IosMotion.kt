package com.gdhapp.client.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** iOS-like motion: critically damped springs that settle without overshoot, and the UIKit ease-in-out curve for fades. */
object IosMotion {
    private val easing = CubicBezierEasing(.25f, .1f, .25f, 1f)
    val slide: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val size: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(250, easing = easing)
    fun <T> value(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 420f)

    /** Pages move side by side like a paged scroll view, so transparent pages never overlap mid-transition. */
    fun page(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        // Pages own their layout; animating their measured size makes the viewport jump.
        return ContentTransform(
            targetContentEnter = slideInHorizontally(slide) { it * direction },
            initialContentExit = slideOutHorizontally(slide) { -it * direction },
            sizeTransform = null
        )
    }

    val none: ContentTransform get() = EnterTransition.None togetherWith ExitTransition.None
}
