/*
 * Noxs — original implementation.
 * Lightweight ring-buffer logger. UI never shows raw stack traces; the
 * diagnostics screen shows these entries instead.
 */
package com.noxs.linux.shared

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object NoxsLog {

    private const val MAX_ENTRIES = 512
    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) = add("D", tag, message)
    fun i(tag: String, message: String) = add("I", tag, message)
    fun w(tag: String, message: String) = add("W", tag, message)
    fun w(tag: String, message: String, error: Throwable?) {
        // Same no-stacktrace policy as e(): type + message only.
        add("W", tag, if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message)
    }
    fun e(tag: String, message: String, error: Throwable? = null) {
        // Never store stack traces: log type + message only (privacy by default).
        add("E", tag, if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message)
    }

    private fun add(level: String, tag: String, message: String) {
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast("${fmt.format(Date())} $level/$tag: ${message.take(2000)}")
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }
}
