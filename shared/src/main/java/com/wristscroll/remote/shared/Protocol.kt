package com.wristscroll.remote.shared

import java.nio.charset.StandardCharsets

/**
 * Shared protocol constants between the Wear OS watch remote (:wear) and
 * the Android phone companion receiver (:mobile).
 * All Data Layer message payloads are encoded as UTF-8 strings.
 */
object Protocol {
    // Data Layer message paths
    const val PATH_PREFIX = "/wristscroll"
    const val PATH_COMMAND = "/wristscroll/command"
    const val PATH_ERROR = "/wristscroll/error"
    const val PATH_PING = "/wristscroll/ping"
    const val PATH_PONG = "/wristscroll/pong"

    // Capabilities advertised in res/values/wear.xml
    const val CAPABILITY_PHONE_RECEIVER = "wristscroll_phone_receiver"
    const val CAPABILITY_WATCH_REMOTE = "wristscroll_watch_remote"

    // Watch-to-phone command payloads on /wristscroll/command
    const val CMD_NEXT_ITEM = "NEXT_ITEM"
    const val CMD_PREV_ITEM = "PREV_ITEM"
    const val CMD_SWIPE_LEFT = "SWIPE_LEFT"
    const val CMD_SWIPE_RIGHT = "SWIPE_RIGHT"
    const val CMD_DOUBLE_TAP_LIKE = "DOUBLE_TAP_LIKE"
    const val CMD_SINGLE_TAP_PAUSE = "SINGLE_TAP_PAUSE"

    val VALID_COMMANDS: Set<String> = setOf(
        CMD_NEXT_ITEM,
        CMD_PREV_ITEM,
        CMD_SWIPE_LEFT,
        CMD_SWIPE_RIGHT,
        CMD_DOUBLE_TAP_LIKE,
        CMD_SINGLE_TAP_PAUSE
    )

    // Phone-to-watch error payloads on /wristscroll/error
    const val ERR_SERVICE_DISABLED = "SERVICE_DISABLED"
    const val ERR_PHONE_NOT_READY = "PHONE_NOT_READY"
    const val ERR_APP_NOT_ALLOWED = "APP_NOT_ALLOWED"

    val VALID_ERRORS: Set<String> = setOf(
        ERR_SERVICE_DISABLED,
        ERR_PHONE_NOT_READY,
        ERR_APP_NOT_ALLOWED
    )

    // Default allowlist of foreground app package names on the phone
    val ALLOWED_PACKAGES: Set<String> = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.instagram.android",
        "com.google.android.youtube"
    )

    // Heartbeat timeout in milliseconds
    const val PING_TIMEOUT_MS = 2_000L

    // Intent extra used by WristScrollTileService to open MainActivity directly in Stealth Remote
    const val EXTRA_START_IN_STEALTH = "start_in_stealth"

    fun encodePayload(value: String): ByteArray =
        value.toByteArray(StandardCharsets.UTF_8)

    fun decodePayload(bytes: ByteArray): String =
        String(bytes, StandardCharsets.UTF_8).trim()
}
