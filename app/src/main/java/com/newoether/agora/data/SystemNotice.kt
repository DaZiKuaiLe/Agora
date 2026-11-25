package com.newoether.agora.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** First line of an Agora runtime annotation on the wire. Not part of the user or tool body. */
internal const val SYSTEM_NOTICE_MARK = "【系统提示】"

internal const val ASK_WIRE_REMINDER =
    "当前是用户和你讨论方案或提问环节，不可进行写入或修改"

internal fun wireWorkModeLine(askMode: Boolean): String =
    if (askMode) "工作模式：Ask（$ASK_WIRE_REMINDER）" else "工作模式：Agent"

internal fun wireTimeLine(epochMs: Long, zone: ZoneId): String =
    "time: ${formatWireTimestamp(epochMs, zone)}"

internal fun formatWireTimestamp(epochMs: Long, zone: ZoneId): String {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
    val wall = zoned.format(WALL_CLOCK)
    val offset = zoned.offset.id.let { raw -> if (raw == "Z") "+00:00" else raw }
    return "$wall ${zone.id} (UTC$offset)"
}

internal fun wireSystemNotice(lines: List<String>): String =
    (listOf(SYSTEM_NOTICE_MARK) + lines).joinToString("\n")

internal fun appendWireSystemNotice(text: String, notice: String): String =
    if (text.isEmpty()) notice else "$text\n\n$notice"

internal fun isLeadingSystemNotice(text: String): Boolean =
    text.trimStart().startsWith(SYSTEM_NOTICE_MARK)

private val WALL_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
