package dev.cabinwright.api.broker

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Plain-main tests (no JUnit on the build machine) of the broker client's wire format, against
 * a fake broker on a loopback TCP socket: the host JVM can't reach Linux's abstract namespace,
 * so CarBroker's LocalSocket is left to the car. Exits 1 on the first failure.
 */
object BrokerTest {
    private var passed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        framing()
        request()
        results()
        unreadable()
        hello()
        exchanges()
        timeouts()
        println("ok: $passed checks")
    }

    private fun check(ok: Boolean, what: String) {
        if (!ok) {
            println("FAIL: $what")
            exitProcess(1)
        }
        passed++
    }

    private fun throwsIo(what: String, f: () -> Unit): IOException? {
        try {
            f()
        } catch (e: IOException) {
            passed++
            return e
        }
        println("FAIL: $what didn't throw")
        exitProcess(1)
    }

    private fun frame(payload: ByteArray): ByteArray = ByteArrayOutputStream().also { Wire.writeFrame(it, payload) }
        .toByteArray()

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    private fun framing() {
        val f = frame("{}".toByteArray())
        check(f.contentEquals(bytes(0, 0, 0, 2, '{'.code, '}'.code)), "frame is a big-endian length and the bytes")
        check(Wire.readFrame(ByteArrayInputStream(f)).contentEquals("{}".toByteArray()), "frame round trip")
        check(Wire.readFrame(ByteArrayInputStream(frame(ByteArray(0)))).isEmpty(), "empty frame")
        val big = ByteArray(Wire.MAX_FRAME) { 'a'.code.toByte() }
        val bigFrame = frame(big)
        check(bigFrame.size == Wire.MAX_FRAME + 4, "a 1 MiB frame is allowed")
        check(Wire.readFrame(ByteArrayInputStream(bigFrame)).contentEquals(big), "1 MiB round trip")
        // a stream that hands out a few bytes per read
        val trickle = object : InputStream() {
            val inner = ByteArrayInputStream(frame("hello".toByteArray()))
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 2))
        }
        check(String(Wire.readFrame(trickle)) == "hello", "frame read in pieces")
        throwsIo("writing a frame over 1 MiB") {
            Wire.writeFrame(ByteArrayOutputStream(), ByteArray(Wire.MAX_FRAME + 1))
        }
        throwsIo("a length over 1 MiB") { Wire.readFrame(ByteArrayInputStream(bytes(0, 0x10, 0, 1))) }
        throwsIo("a negative length") { Wire.readFrame(ByteArrayInputStream(bytes(0xff, 0xff, 0xff, 0xff))) }
        val e = throwsIo("a short header") { Wire.readFrame(ByteArrayInputStream(bytes(0, 0))) }
        check(e is EOFException, "a short header is EOF")
        val e2 = throwsIo("a short body") { Wire.readFrame(ByteArrayInputStream(bytes(0, 0, 0, 5, 1, 2))) }
        check(e2 is EOFException, "a short body is EOF")
    }

    private fun request() {
        val o = JSONObject(String(Wire.request("tweaks", "set", JSONObject().put("id", "auto-dst").put("on", true))))
        check(o.getInt("v") == 1, "request v")
        check(o.getString("cap") == "tweaks" && o.getString("op") == "set", "request cap and op")
        check(o.getJSONObject("args").getString("id") == "auto-dst", "request args")
        check(o.getJSONObject("args").getBoolean("on"), "request args bool")
        val unicode = JSONObject(String(Wire.request("root", "exec", JSONObject().put("command", "echo é"))))
        check(unicode.getJSONObject("args").getString("command") == "echo é", "request is UTF-8")
    }

    private fun result(json: String): Result = Wire.parseResult(json.toByteArray())

    private fun results() {
        val ok = result("""{"ok":true,"data":{"lines":["a","b"]}}""")
        check(ok.ok && ok.error == null && ok.message == null, "ok result")
        check((ok.data as JSONObject).getJSONArray("lines").length() == 2, "ok data")
        check(result("""{"ok":true,"data":[1]}""").data is JSONArray, "array data")
        check(result("""{"ok":true}""").let { it.ok && it.data == null }, "no data")
        check(result("""{"ok":true,"data":null}""").let { it.ok && it.data == null }, "null data is null")
        val denied = result("""{"ok":false,"error":"not_parked","message":"Park the car first."}""")
        check(!denied.ok && denied.error == CarBroker.NOT_PARKED, "error code")
        check(denied.message == "Park the car first.", "error message")
        check(denied.data == null, "no data on error")
        for (code in listOf("unknown_caller", "not_granted", "denied", "bad_request", "unsupported", "failed")) {
            check(result("""{"ok":false,"error":"$code"}""").error == code, "error $code passes through")
        }
        check(
            result("""{"ok":false}""").let {
                !it.ok && it.error == CarBroker.FAILED && it.message == null
            },
            "no code",
        )
        check(result("""{"ok":false,"error":""}""").error == CarBroker.FAILED, "empty code")
    }

    private fun unreadable() {
        for (bad in listOf("", "not json", "[]", "{}", """{"ok":"yes"}""", "{\"ok\":tr")) {
            val r = result(bad)
            check(!r.ok && r.error == CarBroker.IO_ERROR && r.message != null, "unreadable reply '$bad'")
        }
    }

    private fun hello() {
        val h = Wire.parseHello(
            result(
                """{"ok":true,"data":{"version":1,"package":"dev.example","grants":""" +
                    """{"tweaks":"always","root":"ask","logs":3}}}""",
            ),
        )
        check(h != null && h.version == 1 && h.pkg == "dev.example", "hello")
        check(h!!.grants == mapOf("tweaks" to "always", "root" to "ask"), "hello grants (non-strings left out)")
        check(
            Wire.parseHello(result("""{"ok":true,"data":{"version":2,"package":"p"}}"""))?.grants?.isEmpty() == true,
            "no grants",
        )
        check(Wire.parseHello(result("""{"ok":false,"error":"unknown_caller"}""")) == null, "hello refused")
        check(Wire.parseHello(result("""{"ok":true,"data":{"package":"p"}}""")) == null, "hello without version")
        check(Wire.parseHello(result("""{"ok":true,"data":{"version":1}}""")) == null, "hello without package")
        check(Wire.parseHello(result("""{"ok":true,"data":[]}""")) == null, "hello data not an object")
        check(Wire.parseHello(Wire.failure(CarBroker.NOT_RUNNING, "x")) == null, "no broker")
    }

    // ---- against a fake broker

    /** Starts a one-connection fake broker; [serve] gets its streams. Returns the port. */
    private fun fake(serve: (InputStream, OutputStream) -> Unit): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val t = Thread {
            server.use { s ->
                s.accept().use { c ->
                    try {
                        serve(c.getInputStream(), c.getOutputStream())
                    } catch (ignored: IOException) {
                    }
                }
            }
        }
        t.isDaemon = true
        t.start()
        return server.localPort
    }

    /** What CarBroker.call does, over TCP to [port]. */
    private fun call(
        port: Int,
        cap: String,
        op: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Int = 2_000,
    ): Result {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            val input = DeadlineInput(s.getInputStream(), deadline) { s.soTimeout = it }
            return Wire.exchange(input, s.getOutputStream(), Wire.request(cap, op, args), deadline)
        }
    }

    private fun exchanges() {
        var seen: JSONObject? = null
        val hello = fake { i, o ->
            seen = JSONObject(String(Wire.readFrame(i)))
            val data = """{"version":1,"package":"dev.example","grants":{"tweaks":"always"}}"""
            Wire.writeFrame(o, """{"ok":true,"data":$data}""".toByteArray())
        }
        val h = Wire.parseHello(call(hello, "broker", "hello"))
        check(h?.pkg == "dev.example" && h.grants["tweaks"] == "always", "hello from the fake broker")
        check(
            seen?.getString("cap") == "broker" && seen?.getString("op") == "hello" && seen?.getInt("v") == 1,
            "broker saw hello",
        )

        val refused = fake { i, o ->
            Wire.readFrame(i)
            Wire.writeFrame(o, """{"ok":false,"error":"not_granted","message":"Not allowed."}""".toByteArray())
        }
        val r = call(refused, "root", "exec", JSONObject().put("command", "id"))
        check(!r.ok && r.error == CarBroker.NOT_GRANTED && r.message == "Not allowed.", "refusal from the fake broker")

        val hangsUp = fake { i, _ -> Wire.readFrame(i) }
        check(call(hangsUp, "tweaks", "list").error == CarBroker.IO_ERROR, "broker hangs up without a reply")

        val halfReply = fake { i, o ->
            Wire.readFrame(i)
            o.write(bytes(0, 0, 0, 50, '{'.code))
            o.flush()
        }
        check(call(halfReply, "tweaks", "list").error == CarBroker.IO_ERROR, "reply cut short")

        val tooBig = fake { i, o ->
            Wire.readFrame(i)
            o.write(bytes(0x7f, 0, 0, 0))
            o.flush()
        }
        check(call(tooBig, "tweaks", "list").error == CarBroker.IO_ERROR, "reply over 1 MiB")

        val garbage = fake { i, o ->
            Wire.readFrame(i)
            Wire.writeFrame(o, "<html>".toByteArray())
        }
        check(call(garbage, "tweaks", "list").error == CarBroker.IO_ERROR, "reply that isn't JSON")

        val notSent = fake { _, _ -> }
        val huge = call(notSent, "root", "exec", JSONObject().put("command", "x".repeat(Wire.MAX_FRAME)))
        check(huge.error == CarBroker.BAD_REQUEST, "a request over 1 MiB isn't sent")
    }

    private fun timeouts() {
        val silent = fake { i, _ ->
            Wire.readFrame(i)
            Thread.sleep(1_500)
        }
        val start = System.nanoTime()
        val t = call(silent, "tweaks", "list", timeoutMs = 300)
        val tookMs = (System.nanoTime() - start) / 1_000_000
        check(t.error == CarBroker.TIMEOUT && t.message != null, "a silent broker times out")
        check(tookMs in 250..1_200, "the timeout is the call's ($tookMs ms)")

        // a trickling reply still ends at the call's deadline, not per read
        val trickle = fake { i, o ->
            Wire.readFrame(i)
            o.write(bytes(0, 0, 0, 100))
            repeat(20) {
                o.write('a'.code)
                o.flush()
                Thread.sleep(100)
            }
        }
        val start2 = System.nanoTime()
        val t2 = call(trickle, "tweaks", "list", timeoutMs = 500)
        val took2 = (System.nanoTime() - start2) / 1_000_000
        check(t2.error == CarBroker.TIMEOUT && took2 < 1_200, "a trickling reply times out at the deadline ($took2 ms)")
    }
}
