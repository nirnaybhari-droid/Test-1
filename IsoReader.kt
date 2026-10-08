package com.example.isoextractor

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/** A run of bytes in the image. pos < 0 means "zeros" (sparse). */
class Extent(val pos: Long, val len: Long)

class IsoEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val extents: List<Extent> = emptyList(),
    val inline: ByteArray? = null   // UDF files small enough to live inside their ICB
)

private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
private fun ByteBuffer.u32(o: Int) = getInt(o).toLong() and 0xFFFFFFFFL
private fun ByteBuffer.u16(o: Int) = getShort(o).toInt() and 0xFFFF
private fun tagId(b: ByteArray) = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)

/**
 * Reads ISO 9660 (+ Joliet) and UDF 1.02-2.01 images.
 * UDF is tried first; if it can't be parsed we fall back to ISO 9660/Joliet.
 */
class IsoReader(private val pfd: ParcelFileDescriptor) : Closeable {
    private val ch = FileInputStream(pfd.fileDescriptor).channel
    private var joliet = false
    private var udf = false
    private var blk = SECTOR
    private var partStarts = mapOf<Int, Long>()   // partition number -> absolute byte start
    private var partMaps = listOf<Int>()          // partition reference -> partition number
    private val rootEntry: IsoEntry

    init {
        var found: IsoEntry? = null
        var err: String? = null
        try {
            val r = initUdf()
            listUdf(r)          // sanity check: the root directory must be readable
            udf = true
            found = r
        } catch (e: Exception) {
            err = e.message
        }
        rootEntry = found ?: initIso(err)
    }

    fun root() = rootEntry

    private fun read(pos: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n < 0) break
            p += n
        }
        return buf.array()
    }

    // ---------------------------------------------------------------- ISO 9660 / Joliet

    private fun initIso(udfError: String?): IsoEntry {
        var lba = 0L
        var size = 0L
        var found = false
        var sector = 16L
        while (sector < 64) {
            val b = read(sector * SECTOR, SECTOR)
            val type = b[0].toInt() and 0xFF
            if (type == 255 || String(b, 1, 5, Charsets.US_ASCII) != "CD001") break
            val v = le(b)
            val isJoliet = type == 2 && b[88].toInt() == 0x25 && b[89].toInt() == 0x2F &&
                (b[90].toInt() == 0x40 || b[90].toInt() == 0x43 || b[90].toInt() == 0x45)
            if ((type == 1 && !found) || isJoliet) {
                lba = v.u32(158)    // root record at 156; LBA at +2
                size = v.u32(166)   // data length at +10
                found = true
                if (isJoliet) joliet = true
            }
            sector++
        }
        if (!found) throw IllegalArgumentException(
            "Not a supported disc image" + (udfError?.let { " ($it)" } ?: "")
        )
        return IsoEntry("", true, size, listOf(Extent(lba * SECTOR, size)))
    }

    private fun listIso(dir: IsoEntry): List<IsoEntry> {
        val data = readData(dir)
        val bb = le(data)
        val out = mutableListOf<IsoEntry>()
        var i = 0
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) { i = (i / SECTOR + 1) * SECTOR; continue }
            val nl = data[i + 32].toInt() and 0xFF
            val raw = if (joliet) String(data, i + 33, nl, Charsets.UTF_16BE)
                      else String(data, i + 33, nl, Charsets.ISO_8859_1)
            val name = raw.replace(Regex(";\\d+$"), "").removeSuffix(".").replace('/', '_')
            if (name.isNotEmpty() && name != "." && name != ".." && name != "\u0000" && name != "\u0001") {
                val size = bb.u32(i + 10)
                out += IsoEntry(
                    name, (data[i + 25].toInt() and 2) != 0, size,
                    listOf(Extent(bb.u32(i + 2) * SECTOR, size))
                )
            }
            i += len
        }
        return out
    }

    // ---------------------------------------------------------------- UDF

    private fun pos(ref: Int, lbn: Long): Long {
        val part = partMaps.getOrNull(ref) ?: error("Bad UDF partition reference")
        return (partStarts[part] ?: error("Missing UDF partition")) + lbn * blk
    }

    private fun initUdf(): IsoEntry {
        val avdp = read(256L * SECTOR, SECTOR)
        require(tagId(avdp) == 2) { "no UDF anchor" }
        val a = le(avdp)
        val vdsLen = a.u32(16)
        val vdsLoc = a.u32(20)
        val starts = mutableMapOf<Int, Long>()
        var maps = listOf<Int>()
        var fsdLbn = -1L
        var fsdRef = 0
        for (i in 0 until minOf(vdsLen / SECTOR, 64L)) {
            val b = read((vdsLoc + i) * SECTOR, SECTOR)
            val v = le(b)
            when (tagId(b)) {
                5 -> starts[v.u16(22)] = v.u32(188) * SECTOR
                6 -> {
                    blk = v.u32(212).toInt()
                    fsdLbn = v.u32(252)
                    fsdRef = v.u16(256)
                    var o = 440
                    val m = mutableListOf<Int>()
                    repeat(v.u32(268).toInt()) {
                        require(b[o].toInt() == 1) { "UDF 2.50+ metadata/virtual partitions are not supported" }
                        m += v.u16(o + 4)
                        o += b[o + 1].toInt() and 0xFF
                    }
                    maps = m
                }
                8 -> break
            }
        }
        require(fsdLbn >= 0 && starts.isNotEmpty()) { "incomplete UDF descriptors" }
        partStarts = starts
        partMaps = maps
        val f = read(pos(fsdRef, fsdLbn), blk)
        require(tagId(f) == 256) { "no UDF file set descriptor" }
        val fv = le(f)
        return udfNode("", true, fv.u16(408), fv.u32(404))   // root ICB long_ad at 400
    }

    /** Reads a File Entry / Extended File Entry and returns it as an IsoEntry. */
    private fun udfNode(name: String, isDir: Boolean, ref: Int, lbn: Long): IsoEntry {
        val b = read(pos(ref, lbn), blk)
        val t = tagId(b)
        require(t == 261 || t == 266) { "unsupported UDF entry ($t)" }
        val v = le(b)
        val x = t == 266
        val mode = v.u16(34) and 7                      // 0 short_ad, 1 long_ad, 3 embedded
        val base = (if (x) 216 else 176) + v.u32(if (x) 208 else 168).toInt()
        val lad = v.u32(if (x) 212 else 172).toInt()
        val extents = mutableListOf<Extent>()
        var inline: ByteArray? = null
        if (mode == 3) {
            inline = b.copyOfRange(base, base + lad)
        } else {
            var ad = v
            var off = base
            var end = base + lad
            val step = if (mode == 1) 16 else 8
            while (off + step <= end) {
                val l = ad.u32(off)
                val kind = (l shr 30).toInt()
                val len = l and 0x3FFFFFFFL
                val p = ad.u32(off + 4)
                val pr = if (mode == 1) ad.u16(off + 8) else ref
                off += step
                if (len == 0L) break
                when (kind) {
                    0 -> extents += Extent(pos(pr, p), len)
                    3 -> {      // pointer to a continuation block of more descriptors
                        ad = le(read(pos(pr, p), len.toInt()))
                        off = 24
                        end = 24 + ad.u32(20).toInt()
                    }
                    else -> extents += Extent(-1, len)   // allocated-but-unrecorded: zeros
                }
            }
        }
        return IsoEntry(name, isDir, v.getLong(56), extents, inline)
    }

    private fun listUdf(dir: IsoEntry): List<IsoEntry> {
        val d = readData(dir)
        val v = le(d)
        val out = mutableListOf<IsoEntry>()
        var i = 0
        while (i + 38 <= d.size && v.u16(i) == 257) {
            val chars = d[i + 18].toInt()
            val lfi = d[i + 19].toInt() and 0xFF
            val liu = v.u16(i + 36)
            if (lfi > 1 && (chars and 0x0C) == 0) {     // skip parent (8) and deleted (4) entries
                val name = udfName(d, i + 38 + liu, lfi)
                if (name.isNotEmpty() && name != "." && name != "..") {
                    out += udfNode(name, (chars and 2) != 0, v.u16(i + 28), v.u32(i + 24))
                }
            }
            i = (i + 38 + liu + lfi + 3) and 3.inv()
        }
        return out
    }

    private fun udfName(d: ByteArray, o: Int, n: Int): String {
        val s = when (d[o].toInt() and 0xFF) {
            8, 254 -> String(d, o + 1, n - 1, Charsets.ISO_8859_1)
            16, 255 -> String(d, o + 1, n - 1, Charsets.UTF_16BE)
            else -> "?"
        }
        return s.replace('/', '_')
    }

    // ---------------------------------------------------------------- shared

    fun list(dir: IsoEntry): List<IsoEntry> =
        (if (udf) listUdf(dir) else listIso(dir))
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))

    private fun readData(e: IsoEntry): ByteArray {
        val out = ByteArrayOutputStream()
        copy(e, out)
        return out.toByteArray()
    }

    fun copy(e: IsoEntry, out: OutputStream) {
        e.inline?.let { out.write(it, 0, minOf(it.size.toLong(), e.size).toInt()); return }
        var left = e.size
        val buf = ByteBuffer.allocate(256 * 1024)
        for (x in e.extents) {
            var p = x.pos
            var n = minOf(x.len, left)
            left -= n
            while (n > 0) {
                val c = minOf(buf.capacity().toLong(), n).toInt()
                val got = if (p < 0) {
                    Arrays.fill(buf.array(), 0, c, 0.toByte())
                    c
                } else {
                    buf.clear()
                    buf.limit(c)
                    ch.read(buf, p).also { if (it <= 0) return }
                }
                out.write(buf.array(), 0, got)
                if (p >= 0) p += got
                n -= got
            }
            if (left <= 0) break
        }
    }

    override fun close() = pfd.close()

    companion object { const val SECTOR = 2048 }
}
