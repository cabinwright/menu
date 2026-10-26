package dev.cabinwright.api.ui

/**
 * Probes one of the head unit's hidden APIs (automotive.jar and the like, reached by reflection):
 * on any failure, from a class this unit doesn't have to an exception thrown inside the
 * call, returns [fallback], so the same code runs on other units and on a phone.
 */
internal inline fun <T> probeHidden(fallback: T, block: () -> T): T = try {
    block()
} catch (ignored: Throwable) {
    fallback
}
