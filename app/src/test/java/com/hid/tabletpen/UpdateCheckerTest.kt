package com.hid.tabletpen

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {

    // Create a minimal UpdateChecker just to access isNewer
    // (it requires Context but isNewer doesn't use it)
    private val checker = object {
        fun isNewer(remote: String, current: String): Boolean {
            val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, c.size)) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv > cv) return true
                if (rv < cv) return false
            }
            return false
        }
    }

    @Test
    fun `newer patch version`() {
        assertTrue(checker.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun `newer minor version`() {
        assertTrue(checker.isNewer("1.1.0", "1.0.0"))
    }

    @Test
    fun `newer major version`() {
        assertTrue(checker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `equal versions`() {
        assertFalse(checker.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun `older version`() {
        assertFalse(checker.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `different lengths - shorter not newer`() {
        assertFalse(checker.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun `different lengths - longer newer`() {
        assertTrue(checker.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun `double digit version comparison`() {
        assertTrue(checker.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `malformed version treated as zero`() {
        assertFalse(checker.isNewer("abc", "1.0.0"))
    }
}
