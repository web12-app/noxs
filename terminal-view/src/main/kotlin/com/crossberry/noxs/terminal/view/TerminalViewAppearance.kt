/*
 * Noxs terminal-view — original implementation.
 * Full appearance/behavior bundle applied to a TerminalView in one call.
 * Pure data: the app layer maps persisted settings into this object.
 */
package com.crossberry.noxs.terminal.view

import com.crossberry.noxs.terminal.emulator.TerminalScrollMode

data class TerminalViewAppearance(
    val theme: TerminalTheme = TerminalTheme.NOXS_DARK,
    val fontFamily: TerminalFontFamily = TerminalFontFamily.MONOSPACE,
    /** Terminal font size in sp (clamped by the view between min/max). */
    val fontSizeSp: Float = 14f,
    val minFontSizeSp: Float = 10f,
    val maxFontSizeSp: Float = 28f,
    /** Vertical spacing multiplier (1.0 = natural). */
    val lineSpacing: Float = 1.0f,
    /** Extra horizontal tracking between glyphs, in em units. */
    val letterSpacingEm: Float = 0f,
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BLOCK,
    val cursorBlink: Boolean = true,
    /** Blink period in ms (200..1500). */
    val cursorBlinkPeriodMs: Long = 550L,
    /** Underline/bar thickness multiplier, 1..4. */
    val cursorWidth: Int = 2,
    /** Content inset in dp around the terminal glyphs. */
    val paddingDp: Float = 0f,
    /** Whole-view opacity, 0.5..1.0. */
    val opacity: Float = 1f,
    val scrollMode: TerminalScrollMode = TerminalScrollMode.SMART,
    val followLiveOutput: Boolean = true,
    val showNewOutputIndicator: Boolean = true,
    val autoFollowWhileRunning: Boolean = true,
    val pinchZoom: Boolean = true,
    val twoFingerScroll: Boolean = false,
    val longPressSelection: Boolean = true,
    val haptics: Boolean = false,
    val textAntialias: Boolean = true,
    val reduceAnimations: Boolean = false
)
