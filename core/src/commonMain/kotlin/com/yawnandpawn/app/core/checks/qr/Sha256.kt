package com.yawnandpawn.app.core.checks.qr

/**
 * SHA-256 (FIPS 180-4) in plain Kotlin, so `:core` can fingerprint a registered code on every target without a platform
 * crypto API. Used only for [RegisteredCode]: the fingerprint keeps the raw content of a code (which may be private, for
 * example a Wi-Fi QR code) out of every store, and it is not a security boundary.
 *
 * Magic numbers are allowed here: the rotation amounts and round constants are the standard's own numbers, and naming
 * each one would not make them clearer.
 */
@Suppress("MagicNumber")
internal object Sha256 {
    /** The lowercase hex digest of [bytes]. */
    fun hex(bytes: ByteArray): String = digest(bytes).joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }

    /** The 32-byte digest of [bytes]. */
    fun digest(bytes: ByteArray): ByteArray {
        val hash = INITIAL.copyOf()
        val words = IntArray(SCHEDULE)
        val padded = padded(bytes)
        for (block in 0 until padded.size / BLOCK_BYTES) {
            for (i in 0 until WORDS_PER_BLOCK) words[i] = wordAt(padded, block * BLOCK_BYTES + i * WORD_BYTES)
            for (i in WORDS_PER_BLOCK until SCHEDULE) {
                val w15 = words[i - 15]
                val w2 = words[i - 2]
                val s0 = w15.rotateRight(7) xor w15.rotateRight(18) xor (w15 ushr 3)
                val s1 = w2.rotateRight(17) xor w2.rotateRight(19) xor (w2 ushr 10)
                words[i] = words[i - 16] + s0 + words[i - 7] + s1
            }
            compress(hash, words)
        }
        return ByteArray(DIGEST_BYTES) { (hash[it / WORD_BYTES] ushr (Byte.SIZE_BITS * (WORD_BYTES - 1 - it % WORD_BYTES))).toByte() }
    }

    private fun compress(
        hash: IntArray,
        words: IntArray,
    ) {
        var a = hash[0]
        var b = hash[1]
        var c = hash[2]
        var d = hash[3]
        var e = hash[4]
        var f = hash[5]
        var g = hash[6]
        var h = hash[7]
        for (i in 0 until SCHEDULE) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + choice + ROUND[i] + words[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + majority
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        hash[0] += a
        hash[1] += b
        hash[2] += c
        hash[3] += d
        hash[4] += e
        hash[5] += f
        hash[6] += g
        hash[7] += h
    }

    /** [bytes], then 0x80, zeros, and the bit length as a big-endian 64-bit number, to a multiple of 64 bytes. */
    private fun padded(bytes: ByteArray): ByteArray {
        val total = ((bytes.size + 1 + LENGTH_BYTES + BLOCK_BYTES - 1) / BLOCK_BYTES) * BLOCK_BYTES
        val out = bytes.copyOf(total)
        out[bytes.size] = PAD_FIRST
        val bits = bytes.size.toLong() * Byte.SIZE_BITS
        for (i in 0 until LENGTH_BYTES) out[total - 1 - i] = (bits ushr (Byte.SIZE_BITS * i)).toByte()
        return out
    }

    private fun wordAt(
        bytes: ByteArray,
        offset: Int,
    ): Int = (0 until WORD_BYTES).fold(0) { word, i -> (word shl Byte.SIZE_BITS) or (bytes[offset + i].toInt() and BYTE_MASK) }

    private const val BYTE_MASK = 0xFF
    private const val HEX = 16
    private const val BLOCK_BYTES = 64
    private const val WORD_BYTES = 4
    private const val WORDS_PER_BLOCK = 16
    private const val SCHEDULE = 64
    private const val LENGTH_BYTES = 8
    private const val DIGEST_BYTES = 32
    private const val PAD_FIRST: Byte = -0x80

    private val INITIAL =
        intArrayOf(
            0x6a09e667,
            -0x4498517b,
            0x3c6ef372,
            -0x5ab00ac6,
            0x510e527f,
            -0x64fa9774,
            0x1f83d9ab,
            0x5be0cd19,
        )

    private val ROUND =
        longArrayOf(
            0x428a2f98,
            0x71374491,
            0xb5c0fbcf,
            0xe9b5dba5,
            0x3956c25b,
            0x59f111f1,
            0x923f82a4,
            0xab1c5ed5,
            0xd807aa98,
            0x12835b01,
            0x243185be,
            0x550c7dc3,
            0x72be5d74,
            0x80deb1fe,
            0x9bdc06a7,
            0xc19bf174,
            0xe49b69c1,
            0xefbe4786,
            0x0fc19dc6,
            0x240ca1cc,
            0x2de92c6f,
            0x4a7484aa,
            0x5cb0a9dc,
            0x76f988da,
            0x983e5152,
            0xa831c66d,
            0xb00327c8,
            0xbf597fc7,
            0xc6e00bf3,
            0xd5a79147,
            0x06ca6351,
            0x14292967,
            0x27b70a85,
            0x2e1b2138,
            0x4d2c6dfc,
            0x53380d13,
            0x650a7354,
            0x766a0abb,
            0x81c2c92e,
            0x92722c85,
            0xa2bfe8a1,
            0xa81a664b,
            0xc24b8b70,
            0xc76c51a3,
            0xd192e819,
            0xd6990624,
            0xf40e3585,
            0x106aa070,
            0x19a4c116,
            0x1e376c08,
            0x2748774c,
            0x34b0bcb5,
            0x391c0cb3,
            0x4ed8aa4a,
            0x5b9cca4f,
            0x682e6ff3,
            0x748f82ee,
            0x78a5636f,
            0x84c87814,
            0x8cc70208,
            0x90befffa,
            0xa4506ceb,
            0xbef9a3f7,
            0xc67178f2,
        ).let { constants -> IntArray(constants.size) { constants[it].toInt() } }
}
