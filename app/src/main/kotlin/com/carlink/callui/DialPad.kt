package com.carlink.callui

import com.carlink.ocbm.Ocbm

/** The in-call keypad: twelve keys in phone order, each the telephony HID index it sends (`INPUT_TELEPHONY`). */
object DialPad {
    val KEYS: List<Char> = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')

    /** The `Ocbm.TEL_*` index for [key]: `TEL_DIGIT0 + d` for a digit, star and pound their own. */
    fun index(key: Char): Byte =
        when (key) {
            '*' -> Ocbm.TEL_STAR
            '#' -> Ocbm.TEL_POUND
            in '0'..'9' -> (Ocbm.TEL_DIGIT0 + (key - '0')).toByte()
            else -> throw IllegalArgumentException("not a keypad key: $key")
        }
}
