/*
 * Noxs terminal-emulator — original implementation.
 * Maps Android key codes + modifier state to terminal byte sequences.
 */
package com.noxs.linux.terminal.emulator

object KeyHandler {

    const val MOD_CTRL = 1
    const val MOD_ALT = 2
    const val MOD_SHIFT = 4

    /** @return bytes to send, or null when the key is not a terminal special key. */
    fun map(code: Int, mods: Int, appCursor: Boolean): ByteArray? {
        val ctrl = mods and MOD_CTRL != 0
        val alt = mods and MOD_ALT != 0
        val shift = mods and MOD_SHIFT != 0

        // Ctrl+letter → C0 controls. Android letter keycodes are 29..54
        // (KEYCODE_A..KEYCODE_Z), mapping to ASCII 'a'..'z' by adding 68.
        // Ctrl+C (KEYCODE_C = 31) yields ETX (0x03), etc.
        if (ctrl && code in 29..54) {
            val b = (code + 68 - 'a'.code + 1).toByte()
            return withAlt(alt, byteArrayOf(b))
        }
        if (ctrl || shift) {
            // modifiedArrow already encodes the xterm modifier parameter;
            // do not stack an extra ESC prefix on top of it.
            modifiedArrow(code, ctrl, shift, appCursor)?.let { return it }
        }

        val seq: ByteArray? = when (code) {
            // Enter / backspace
            66 /* KEYCODE_ENTER */ -> byteArrayOf('\r'.code.toByte())
            67 /* KEYCODE_DEL (backspace) */ -> byteArrayOf(0x7f)
            68 /* KEYCODE_FORWARD_DEL */ -> "\u001b[3~".toByteArray()
            // Arrows
            19 -> cursorSeq('A', appCursor)
            20 -> cursorSeq('B', appCursor)
            21 -> cursorSeq('D', appCursor)
            22 -> cursorSeq('C', appCursor)
            // Navigation
            92 /* PAGE_UP */ -> "\u001b[5~".toByteArray()
            93 /* PAGE_DOWN */ -> "\u001b[6~".toByteArray()
            111 /* ESCAPE */ -> byteArrayOf(0x1b)
            122 /* MOVE_HOME */ -> cursorSeq('H', appCursor)
            123 /* MOVE_END */ -> cursorSeq('F', appCursor)
            124 /* INSERT */ -> "\u001b[2~".toByteArray()
            // F1..F12
            131 -> "\u001bOP".toByteArray()
            132 -> "\u001bOQ".toByteArray()
            133 -> "\u001bOR".toByteArray()
            134 -> "\u001bOS".toByteArray()
            135 -> "\u001b[15~".toByteArray()
            136 -> "\u001b[17~".toByteArray()
            137 -> "\u001b[18~".toByteArray()
            138 -> "\u001b[19~".toByteArray()
            139 -> "\u001b[20~".toByteArray()
            140 -> "\u001b[21~".toByteArray()
            141 -> "\u001b[23~".toByteArray()
            142 -> "\u001b[24~".toByteArray()
            else -> null
        }
        return seq?.let { withAlt(alt, it) }
    }

    /** Ctrl+Arrow / Shift+Arrow word-jump sequences used by bash readline. */
    fun modifiedArrow(code: Int, ctrl: Boolean, shift: Boolean, appCursor: Boolean): ByteArray? {
        val dir = when (code) {
            19 -> 'A'; 20 -> 'B'; 21 -> 'D'; 22 -> 'C'
            else -> return null
        }
        return when {
            ctrl -> "\u001b[1;5$dir".toByteArray()
            shift -> "\u001b[1;2$dir".toByteArray()
            else -> cursorSeq(dir, appCursor)
        }
    }

    private fun cursorSeq(letter: Char, appCursor: Boolean): ByteArray =
        if (appCursor) "\u001bO$letter".toByteArray()
        else "\u001b[$letter".toByteArray()

    private fun withAlt(alt: Boolean, bytes: ByteArray): ByteArray =
        if (alt) ByteArray(1) { 0x1b } + bytes else bytes
}
