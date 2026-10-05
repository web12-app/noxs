/*
 * Noxs — original implementation.
 * Maps persisted TerminalSettings onto the TerminalView appearance bundle.
 * Lives in the app layer because the view module must not know about prefs.
 */
package com.crossberry.noxs.ui

import com.crossberry.noxs.terminal.emulator.TerminalScrollMode
import com.crossberry.noxs.terminal.view.TerminalCursorStyle
import com.crossberry.noxs.terminal.view.TerminalFontFamily
import com.crossberry.noxs.terminal.view.TerminalTheme
import com.crossberry.noxs.terminal.view.TerminalViewAppearance

fun TerminalSettings.toAppearance(): TerminalViewAppearance = TerminalViewAppearance(
    theme = TerminalTheme.byKey(theme.key),
    fontFamily = TerminalFontFamily.byKey(fontFamily.key),
    fontSizeSp = fontSizeSp.toFloat(),
    minFontSizeSp = TerminalSettings.FONT_MIN_SP.toFloat(),
    maxFontSizeSp = TerminalSettings.FONT_MAX_SP.toFloat(),
    lineSpacing = lineSpacingPercent / 100f,
    letterSpacingEm = letterSpacingPercent / 100f,
    cursorStyle = TerminalCursorStyle.byKey(cursorStyle.key),
    cursorBlink = cursorBlink,
    cursorBlinkPeriodMs = cursorBlinkPeriodMs.toLong(),
    cursorWidth = cursorWidth,
    paddingDp = paddingDp.toFloat(),
    opacity = opacityPercent / 100f,
    scrollMode = when (scrollMode) {
        ScrollModePref.NORMAL -> TerminalScrollMode.NORMAL
        ScrollModePref.SMART -> TerminalScrollMode.SMART
        ScrollModePref.HISTORY -> TerminalScrollMode.HISTORY
    },
    followLiveOutput = followLiveOutput,
    showNewOutputIndicator = showNewOutputIndicator,
    autoFollowWhileRunning = autoFollowWhileRunning,
    pinchZoom = pinchZoom,
    twoFingerScroll = twoFingerScroll,
    longPressSelection = selectionEnabled,
    haptics = hapticFeedback,
    textAntialias = textAntialias,
    reduceAnimations = reduceAnimations
)
