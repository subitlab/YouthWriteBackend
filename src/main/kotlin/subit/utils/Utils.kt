package subit.utils

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.kotlin.datetime.timestampParam
import java.util.*

fun String?.toUUIDOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

fun Long.toInstant(): Instant =
    Instant.fromEpochMilliseconds(this)

fun Long.toTimestamp() =
    timestampParam(this.toInstant())