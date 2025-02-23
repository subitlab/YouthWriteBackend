@file:Suppress("NOTHING_TO_INLINE", "unused")

package subit.utils

import com.auth0.jwt.algorithms.Algorithm
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.kotlin.datetime.timestampParam
import org.koin.core.component.KoinComponent
import subit.config.systemConfig
import subit.dataClasses.PostId
import subit.plugin.contentNegotiation.contentNegotiationJson
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.*

inline fun String?.toUUIDOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
inline fun <reified R> String?.decodeOrElse(block: (Throwable) -> R): R
{
    if (this == null) return block(NullPointerException("null string"))
    return this.runCatching { contentNegotiationJson.decodeFromString<R>(this) }.getOrElse { block(it) }
}
inline fun <reified R> String?.decodeOrNull(): R? = decodeOrElse { null }

fun Long.toInstant(): Instant =
    Instant.fromEpochMilliseconds(this)

fun Long.toTimestamp() =
    timestampParam(this.toInstant())

fun PostId.getSecret(): String
{
    val mod = 36 * 36 * 36 * 36 * 36 * 36L
    val algorithm: Algorithm = Algorithm.HMAC512(systemConfig.postSecret)
    val rp = algorithm.sign(this.toString().toByteArray())
    val r = rp.fold(0L) { acc, byte -> (acc * 256 + byte.toLong()) % mod }
    return r.toString(36).padStart(6, '0').lowercase()
}

open class LineOutputStream(private val line: (String) -> Unit): OutputStream()
{
    private val arrayOutputStream = ByteArrayOutputStream()
    override fun write(b: Int)
    {
        if (b == '\n'.code)
        {
            val str: String
            synchronized(arrayOutputStream)
            {
                str = arrayOutputStream.toString()
                arrayOutputStream.reset()
            }
            runCatching { line(str) }
        }
        else
        {
            arrayOutputStream.write(b)
        }
    }
}

open class LinePrintStream(private val line: (String) -> Unit): PrintStream(LineOutputStream(line))
{
    override fun println(x: Any?) = x.toString().split('\n').forEach(line)

    override fun println() = println("" as Any?)
    override fun println(x: Boolean) = println(x as Any?)
    override fun println(x: Char) = println(x as Any?)
    override fun println(x: Int) = println(x as Any?)
    override fun println(x: Long) = println(x as Any?)
    override fun println(x: Float) = println(x as Any?)
    override fun println(x: Double) = println(x as Any?)
    override fun println(x: CharArray) = println(x.joinToString("") as Any?)
    override fun println(x: String?) = println(x as Any?)
}

fun getKoin() = object: KoinComponent {}