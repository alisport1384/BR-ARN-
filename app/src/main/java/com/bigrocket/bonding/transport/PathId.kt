package com.bigrocket.bonding.transport

/** Opaque identifier for one physical path (Section 317 of the Internal In-App Bonding Engine
 *  Specification). A value class so it costs nothing at runtime over a raw String but still
 *  prevents accidentally passing a stream ID or sequence number where a path ID is expected. */
@JvmInline
value class PathId(val value: String) {
    override fun toString(): String = value

    companion object {
        val WIFI = PathId("wifi")
        val CELLULAR = PathId("cellular")
    }
}
