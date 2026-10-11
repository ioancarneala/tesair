package com.shilapi.xcertplay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/** Brief feedback for a changed screen; connection state never waits for an animation. */
internal class BrowserMotion : View.OnAttachStateChangeListener, DefaultLifecycleObserver {
    private var state: Any? = null
    private var target: View? = null
    private var animator: ValueAnimator? = null
    private var lifecycle: Lifecycle? = null

    fun update(view: View, nextState: Any) {
        val changed = state != null && state != nextState
        state = nextState
        if (target !== view) {
            cancel()
            target?.removeOnAttachStateChangeListener(this)
            target = view
            view.addOnAttachStateChangeListener(this)
            observeLifecycle(view)
        }
        if (!canAnimate(view)) {
            cancel()
            return
        }
        if (!changed) return
        cancel()
        val offset = BrowserDesign.dp(view.context, 6).toFloat()
        view.alpha = START_ALPHA
        view.translationY = offset
        val transition = ValueAnimator.ofFloat(0f, 1f)
        animator = transition
        transition.duration = 220
        transition.interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
        transition.addUpdateListener {
            if (!canAnimate(view)) {
                cancel()
                return@addUpdateListener
            }
            val fraction = it.animatedValue as Float
            view.alpha = START_ALPHA + (1f - START_ALPHA) * fraction
            view.translationY = offset * (1f - fraction)
        }
        transition.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator === animation) {
                    animator = null
                    settle(view)
                }
            }
        })
        transition.start()
    }

    fun cancel() {
        val running = animator
        animator = null
        running?.cancel()
        target?.let(::settle)
    }

    override fun onStop(owner: LifecycleOwner) = cancel()

    override fun onViewAttachedToWindow(view: View) = observeLifecycle(view)

    override fun onViewDetachedFromWindow(view: View) {
        cancel()
        lifecycle?.removeObserver(this)
        lifecycle = null
    }

    private fun observeLifecycle(view: View) {
        val next = view.findViewTreeLifecycleOwner()?.lifecycle
        if (lifecycle === next) return
        lifecycle?.removeObserver(this)
        lifecycle = next
        next?.addObserver(this)
    }

    private fun canAnimate(view: View) = ValueAnimator.areAnimatorsEnabled() &&
        view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
        lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false

    private fun settle(view: View) {
        view.alpha = 1f
        view.translationY = 0f
    }

    private companion object { const val START_ALPHA = .84f }
}
