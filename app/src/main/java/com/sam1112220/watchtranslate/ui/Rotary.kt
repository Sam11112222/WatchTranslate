package com.sam1112220.watchtranslate.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent

/**
 * 表冠旋转输入。圆形屏以表冠为主要导航输入（设计报告第二节）。
 * 旋转表冠时回调滚动的像素增量，正值向下 / 顺时针。
 */
fun Modifier.rotaryInput(onScroll: (Float) -> Unit): Modifier = composed {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    this
        .focusRequester(fr)
        .focusable()
        .onRotaryScrollEvent { event ->
            onScroll(event.verticalScrollPixels)
            true
        }
}

/**
 * 触摸水平滑动翻页。Wear OS 上除表冠外，左右滑动也是公认导航手势。
 * 一次拖动只允许触发一次（fired 标志），否则长距离滑动会把累计位移
 * 反复叠加，导致一次手势连翻多页。
 */
fun Modifier.swipePage(
    onRight: () -> Unit,
    onLeft: () -> Unit,
    thresholdPx: Float = 60f
): Modifier = composed {
    val acc = remember { mutableFloatStateOf(0f) }
    this.pointerInput(Unit) {
        var fired = false
        detectHorizontalDragGestures(
            onDragStart = {
                fired = false
                acc.floatValue = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                if (fired) return@detectHorizontalDragGestures
                acc.floatValue += dragAmount
                if (acc.floatValue > thresholdPx) {
                    fired = true
                    onRight()
                } else if (acc.floatValue < -thresholdPx) {
                    fired = true
                    onLeft()
                }
            },
            onDragEnd = {
                acc.floatValue = 0f
                fired = false
            },
            onDragCancel = {
                acc.floatValue = 0f
                fired = false
            }
        )
    }
}
