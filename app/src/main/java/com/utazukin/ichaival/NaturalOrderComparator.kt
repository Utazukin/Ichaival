/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

class NaturalOrderComparator(private val ignoreLeadingZeros: Boolean = true) : Comparator<String> {
    private fun compareRight(a: String, b: String): Int {
        var bias = 0
        var ia = 0
        var ib = 0

        // The longest run of digits wins. That aside, the greatest
        // value wins, but we can't know that it will until we've scanned
        // both numbers to know that they have the same magnitude, so we
        // remember it in BIAS.
        while (true) {
            var ca: Char = charAt(a, ia)
            var cb: Char = charAt(b, ib)

            while (ca.code != 0 && !Character.isLetterOrDigit(ca))
                ca = charAt(a, ++ia)

            while (cb.code != 0 && !Character.isLetterOrDigit(cb))
                cb = charAt(b, ++ib)

            if (!isDigit(ca) && !isDigit(cb)) {
                return bias
            }
            if (!isDigit(ca)) {
                return -1
            }
            if (!isDigit(cb)) {
                return +1
            }
            if (ca.code == 0 && cb.code == 0) {
                return bias
            }
            if (bias == 0) {
                if (ca.lowercaseChar() < cb.lowercaseChar()) {
                    bias = -1
                } else if (ca.lowercaseChar() > cb.lowercaseChar()) {
                    bias = +1
                }
            }
            ia++
            ib++
        }
    }

    override fun compare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        var nza: Int
        var nzb: Int
        var ca: Char
        var cb: Char
        while (true) {
            // Only count the number of zeroes leading the last number compared
            nzb = 0
            nza = 0
            ca = charAt(a, ia)
            cb = charAt(b, ib)

            // skip over leading spaces or zeros
            while (Character.isSpaceChar(ca) || (ignoreLeadingZeros && ca == '0') || (ca.code != 0 && !Character.isLetterOrDigit(ca))) {
                if (ignoreLeadingZeros && ca == '0') {
                    nza++
                } else {
                    // Only count consecutive zeroes
                    nza = 0
                }
                ca = charAt(a, ++ia)
            }
            while (Character.isSpaceChar(cb) || (ignoreLeadingZeros && cb == '0') || (cb.code != 0 && !Character.isLetterOrDigit(cb))) {
                if (ignoreLeadingZeros && cb == '0') {
                    nzb++
                } else {
                    // Only count consecutive zeroes
                    nzb = 0
                }
                cb = charAt(b, ++ib)
            }

            // Process run of digits
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                val bias = compareRight(a.substring(ia), b.substring(ib))
                if (bias != 0) {
                    return bias
                }
            }
            if (ca.code == 0 && cb.code == 0) {
                // The strings compare the same. Perhaps the caller
                // will want to call strcmp to break the tie.
                return compareEqual(a, b, nza, nzb, ignoreLeadingZeros)
            }

            if (ca.lowercaseChar() < cb.lowercaseChar()) {
                return -1
            }
            if (ca.lowercaseChar() > cb.lowercaseChar()) {
                return +1
            }
            ++ia
            ++ib
        }
    }

    companion object {
        private fun isDigit(c: Char): Boolean {
            return Character.isDigit(c) || c == '.' || c == ','
        }

        private fun charAt(s: String, i: Int): Char {
            return if (i >= s.length) Char(0) else s[i]
        }

        private fun compareEqual(a: String, b: String, nza: Int, nzb: Int, ignoreLeadingZeros: Boolean): Int {
            if (ignoreLeadingZeros && nza - nzb != 0) return nza - nzb
            return if (a.length == b.length) a.compareTo(b, ignoreCase = true) else a.length - b.length
        }
    }
}
