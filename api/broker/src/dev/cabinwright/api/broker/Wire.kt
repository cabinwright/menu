package dev.cabinwright.api.broker

import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The broker's wire format, on plain streams: a frame is a 4-byte big-endian length and that
 * many bytes of UTF-8 JSON, at most [MAX_FRAME]. A connection carries one request frame and one
 * response frame. Kept apart from [CarBroker]'s socket so it runs on any JVM.
 */
internal object Wire {
    const val VERSION = 1
    const val MAX_FRAME = 1 shl 20

    /** The request frame's payload: `{"v":1,"cap":…,"op":…,"args":{…}}`. */
    fun request(cap: String, op: String, args: JSONObject): ByteArray =
        JSONObject().put("v", VERSION).put("cap", cap).put("op", op).put("args", args)
            .toString().toByteArray(Charsets.UTF_8)

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        if (payload.size > MAX_FRAME) throw IOException("frame of ${payload.size} bytes")
        val n = payload.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(payload)
        out.flush()
    }

    /** Reads one frame; [EOFException] if the stream ends first, [IOException] for a bad length. */
    fun readFrame(input: InputStream): ByteArray {
        val head = readFully(input, 4)
        val n = (head[0].toInt() and 0xff shl 24) or (head[1].toInt() and 0xff shl 16) or
            (head[2].toInt() and 0xff shl 8) or (head[3].toInt() and 0xff)
        if (n < 0 || n > MAX_FRAME) throw IOException("frame of $n bytes")
        return readFully(input, n)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("stream ended after $off of $n bytes")
            off += r
        }
        return b
    }

    /** A response payload as a [Result]; anything that isn't one is [CarBroker.IO_ERROR]. */
    fun parseResult(payload: ByteArray): Result {
        val o = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (ignored: JSONException) {
            return failure(CarBroker.IO_ERROR, "The store's service sent a reply that couldn't be read.")
        }
        val ok = o.opt("ok") as? Boolean
            ?: return failure(CarBroker.IO_ERROR, "The store's service sent a reply that couldn't be read.")
        if (ok) {
            val data = o.opt("data")
            return Result(true, if (data == JSONObject.NULL) null else data, null, null)
        }
        val error = (o.opt("error") as? String)?.takeIf { it.isNotEmpty() } ?: CarBroker.FAILED
        return Result(false, null, error, o.opt("message") as? String)
    }

    /** `broker/hello`'s data as a [Hello]; null if the call failed or the data isn't one. */
    fun parseHello(r: Result): Hello? {
        val d = r.data as? JSONObject
        if (!r.ok || d == null) return null
        val version = (d.opt("version") as? Number)?.toInt() ?: return null
        val pkg = d.opt("package") as? String ?: return null
        val grants = LinkedHashMap<String, String>()
        val g = d.optJSONObject("grants")
        val caps = g?.names()
        if (caps != null) {
            for (i in 0 until caps.length()) {
                val cap = caps.optString(i)
                val state = g.opt(cap)
                if (state is String) grants[cap] = state
            }
        }
        return Hello(version, pkg, grants)
    }

    /**
     * Sends [request] and reads the reply. An I/O failure is [CarBroker.TIMEOUT] once
     * [deadline] (System.nanoTime()) has passed, else [CarBroker.IO_ERROR].
     */
    fun exchange(input: InputStream, output: OutputStream, request: ByteArray, deadline: Long): Result {
        if (request.size > MAX_FRAME) return failure(CarBroker.BAD_REQUEST, "The request is too big.")
        return try {
            writeFrame(output, request)
            parseResult(readFrame(input))
        } catch (ignored: IOException) {
            if (deadline - System.nanoTime() < SLACK_NS) {
                failure(CarBroker.TIMEOUT, "The store's service didn't answer in time.")
            } else {
                failure(CarBroker.IO_ERROR, "Lost the connection to the store's service.")
            }
        }
    }

    fun failure(error: String, message: String): Result = Result(false, null, error, message)

    /** Milliseconds left until [deadline] for a socket read timeout, at least 1 (0 means none). */
    fun remainingMs(deadline: Long): Int = maxOf(1L, (deadline - System.nanoTime()) / 1_000_000L).toInt()

    // a read timeout can fire a hair before the deadline the timeout was computed from
    private const val SLACK_NS = 20_000_000L
}

/** An [InputStream] that sets the socket's read timeout to what's left of [deadline] before each read. */
internal class DeadlineInput(
    private val inner: InputStream,
    private val deadline: Long,
    private val setTimeout: (Int) -> Unit,
) : InputStream() {
    override fun read(): Int {
        setTimeout(Wire.remainingMs(deadline))
        return inner.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        setTimeout(Wire.remainingMs(deadline))
        return inner.read(b, off, len)
    }
}
