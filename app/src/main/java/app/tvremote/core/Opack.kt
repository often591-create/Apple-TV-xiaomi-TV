package app.tvremote.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Apple's OPACK binary serialization (used by the Companion protocol). */
object Opack {

    fun pack(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun le(value: Long, size: Int): ByteArray =
        ByteArray(size) { ((value ushr (8 * it)) and 0xFF).toByte() }

    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(0x04)
            is Boolean -> out.write(if (v) 0x01 else 0x02)
            is Int -> writeInt(out, v.toLong())
            is Long -> writeInt(out, v)
            is Double -> {
                out.write(0x36)
                out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array())
            }
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                when {
                    b.size <= 0x20 -> out.write(0x40 + b.size)
                    b.size <= 0xFF -> { out.write(0x61); out.write(le(b.size.toLong(), 1)) }
                    b.size <= 0xFFFF -> { out.write(0x62); out.write(le(b.size.toLong(), 2)) }
                    else -> { out.write(0x64); out.write(le(b.size.toLong(), 4)) }
                }
                out.write(b)
            }
            is ByteArray -> {
                when {
                    v.size <= 0x20 -> out.write(0x70 + v.size)
                    v.size <= 0xFF -> { out.write(0x91); out.write(le(v.size.toLong(), 1)) }
                    v.size <= 0xFFFF -> { out.write(0x92); out.write(le(v.size.toLong(), 2)) }
                    else -> { out.write(0x93); out.write(le(v.size.toLong(), 4)) }
                }
                out.write(v)
            }
            is List<*> -> {
                out.write(0xD0 + minOf(v.size, 0xF))
                v.forEach { write(out, it) }
                if (v.size >= 0xF) out.write(0x03)
            }
            is Map<*, *> -> {
                out.write(0xE0 + minOf(v.size, 0xF))
                v.forEach { (k, value) -> write(out, k); write(out, value) }
                if (v.size >= 0xF) out.write(0x03)
            }
            else -> throw IllegalArgumentException("Cannot OPACK ${v::class}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        when {
            v in 0 until 0x28 -> out.write((v + 8).toInt())
            v in 0..0xFF -> { out.write(0x30); out.write(le(v, 1)) }
            v in 0..0xFFFF -> { out.write(0x31); out.write(le(v, 2)) }
            v in 0..0xFFFFFFFFL -> { out.write(0x32); out.write(le(v, 4)) }
            else -> { out.write(0x33); out.write(le(v, 8)) }
        }
    }

    fun unpack(data: ByteArray): Any? = Reader(data).read()

    private class Reader(val d: ByteArray) {
        var p = 0
        val objects = ArrayList<Any?>()

        fun u8(): Int = d[p++].toInt() and 0xFF
        fun leInt(n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = v or ((d[p + i].toLong() and 0xFF) shl (8 * i))
            p += n
            return v
        }
        fun take(n: Int): ByteArray { val r = d.copyOfRange(p, p + n); p += n; return r }

        fun read(): Any? {
            val t = u8()
            var addToObjects = true
            val value: Any? = when {
                t == 0x01 -> { addToObjects = false; true }
                t == 0x02 -> { addToObjects = false; false }
                t == 0x04 -> { addToObjects = false; null }
                t == 0x05 -> take(16)
                t == 0x06 -> leInt(8)
                t in 0x08..0x2F -> { addToObjects = false; (t - 8).toLong() }
                t == 0x35 -> ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
                t == 0x36 -> ByteBuffer.wrap(take(8)).order(ByteOrder.LITTLE_ENDIAN).double
                (t and 0xF0) == 0x30 -> leInt(1 shl (t and 0xF))
                t in 0x40..0x60 -> String(take(t - 0x40), Charsets.UTF_8)
                t in 0x61..0x64 -> { val n = leInt(t and 0xF).toInt(); String(take(n), Charsets.UTF_8) }
                t in 0x70..0x90 -> take(t - 0x70)
                t in 0x91..0x94 -> { val n = leInt(1 shl ((t and 0xF) - 1)).toInt(); take(n) }
                (t and 0xF0) == 0xD0 -> {
                    addToObjects = false
                    val count = t and 0xF
                    val list = ArrayList<Any?>()
                    if (count == 0xF) {
                        while ((d[p].toInt() and 0xFF) != 0x03) list.add(read())
                        p++
                    } else repeat(count) { list.add(read()) }
                    list
                }
                t in 0xA0..0xC0 -> { addToObjects = false; objects[t - 0xA0] }
                t in 0xC1..0xC4 -> { addToObjects = false; objects[leInt(t - 0xC0).toInt()] }
                (t and 0xE0) == 0xE0 -> {
                    addToObjects = false
                    val count = t and 0xF
                    val map = LinkedHashMap<Any?, Any?>()
                    if (count == 0xF) {
                        while ((d[p].toInt() and 0xFF) != 0x03) { val k = read(); map[k] = read() }
                        p++
                    } else repeat(count) { val k = read(); map[k] = read() }
                    map
                }
                else -> throw IllegalArgumentException("Unknown OPACK tag 0x%02x".format(t))
            }
            if (addToObjects && objects.none { same(it, value) }) objects.add(value)
            return value
        }

        private fun same(a: Any?, b: Any?): Boolean =
            if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
    }
}

/** HomeKit TLV8 encoding. */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val SIGNATURE = 0x0A
    const val NAME = 0x11

    fun write(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            if (value.isEmpty()) { out.write(tag); out.write(0); continue }
            var pos = 0
            while (pos < value.size) {
                val n = minOf(255, value.size - pos)
                out.write(tag); out.write(n); out.write(value, pos, n)
                pos += n
            }
        }
        return out.toByteArray()
    }

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var p = 0
        while (p + 1 < data.size) {
            val tag = data[p].toInt() and 0xFF
            val len = data[p + 1].toInt() and 0xFF
            val v = data.copyOfRange(p + 2, p + 2 + len)
            result[tag] = (result[tag] ?: ByteArray(0)) + v
            p += 2 + len
        }
        return result
    }
}
