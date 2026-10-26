package dev.cabinwright.api.broker

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.IOException

/**
 * The client of the store's broker (`cabinwrightd`), through which an app installed from the
 * store uses the capabilities the driver gave it. The broker knows the app by its uid, so a
 * call carries no identity: an app can only use what its index entry declares and the driver
 * allowed.
 *
 * ```
 * Thread {
 *     val hello = CarBroker.hello() ?: return@Thread        // no store on this head unit
 *     if (hello.grants[CarBroker.TWEAKS] == "off") return@Thread
 *     val r = CarBroker.call(CarBroker.TWEAKS, "set", JSONObject().put("id", "auto-dst").put("on", true))
 *     if (!r.ok) Log.w(TAG, "${r.error}: ${r.message}")
 * }.start()
 * ```
 *
 * Every call blocks until the broker answers or the timeout passes, so call off the main thread.
 * A failed call has one of the error codes below; a broker older than the app answers an
 * operation it doesn't have with [UNSUPPORTED].
 */
object CarBroker {
    /** The broker's socket, in the abstract namespace. */
    const val SOCKET = "dev.cabinwright.broker"

    // capabilities
    const val BROKER = "broker"
    const val TWEAKS = "tweaks"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val ROOT = "root"
    const val STORE = "store"

    // errors from the broker
    /** The app isn't one the store installed (or its certificate changed). */
    const val UNKNOWN_CALLER = "unknown_caller"

    /** The app doesn't have this capability, or the driver hasn't agreed to a tweak's needs. */
    const val NOT_GRANTED = "not_granted"

    /** The driver said no, or didn't answer the prompt in time. */
    const val DENIED = "denied"

    /** The capability asks the driver first, which waits until the car is parked: retry later. */
    const val NOT_PARKED = "not_parked"
    const val BAD_REQUEST = "bad_request"

    /** The broker doesn't have this operation. */
    const val UNSUPPORTED = "unsupported"
    const val FAILED = "failed"

    // errors from this client
    /** No broker is listening: the store isn't installed or its service isn't running. */
    const val NOT_RUNNING = "not_running"

    /** No answer within the call's timeout. */
    const val TIMEOUT = "timeout"

    /** The connection broke, or the reply wasn't one. */
    const val IO_ERROR = "io_error"

    private const val HELLO_TIMEOUT_MS = 5_000

    /** Blocking; call off the main thread. null when the broker isn't running (or won't answer this app). */
    fun hello(): Hello? = Wire.parseHello(call(BROKER, "hello", JSONObject(), HELLO_TIMEOUT_MS))

    /** Blocking; call off the main thread. One request on its own connection. */
    fun call(cap: String, op: String, args: JSONObject = JSONObject(), timeoutMs: Int = 10_000): Result {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val request = Wire.request(cap, op, args)
        val socket = LocalSocket()
        try {
            try {
                socket.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
            } catch (ignored: IOException) {
                return Wire.failure(NOT_RUNNING, "The store's service isn't running.")
            }
            val input = DeadlineInput(socket.inputStream, deadline) { socket.soTimeout = it }
            return Wire.exchange(input, socket.outputStream, request, deadline)
        } finally {
            try {
                socket.close()
            } catch (ignored: IOException) {
            }
        }
    }
}

/**
 * What `broker/hello` says: the broker's version, the app's package, and its grants, capability
 * to "always", "session" (until the car is turned off), "once", "ask" or "off".
 */
class Hello(val version: Int, val pkg: String, val grants: Map<String, String>)

/**
 * A call's outcome. When [ok], [data] is the reply's JSON value (a JSONObject, JSONArray,
 * String, Number or Boolean, or null). Otherwise [error] is one of [CarBroker]'s codes and
 * [message] says what happened in plain words, when there is one.
 */
class Result(val ok: Boolean, val data: Any?, val error: String?, val message: String?)
