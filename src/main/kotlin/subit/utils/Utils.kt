@file:Suppress("NOTHING_TO_INLINE")

package subit.utils

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.kotlin.datetime.timestampParam
import java.util.*

inline fun String?.toUUIDOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
inline fun <reified T: Enum<T>> String?.toEnumOrNull(): T? = runCatching { enumValueOf<T>(this!!) }.getOrNull()

fun Long.toInstant(): Instant =
    Instant.fromEpochMilliseconds(this)

fun Long.toTimestamp() =
    timestampParam(this.toInstant())