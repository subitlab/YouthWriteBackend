@file:Suppress("NOTHING_TO_INLINE", "unused")

package subit.utils

import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.kotlin.datetime.timestampParam
import org.koin.core.component.KoinComponent
import subit.Loader
import subit.config.emailConfig
import subit.config.systemConfig
import subit.dataClasses.PostId
import subit.database.EmailCodes
import subit.logger.YouthWriteLogger
import subit.plugin.contentNegotiation.contentNegotiationJson
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.*
import javax.mail.Address
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlin.time.Duration.Companion.seconds

private val logger = YouthWriteLogger.getLogger()

/**
 * 检查邮箱格式是否正确
 */
fun checkEmail(email: String): Boolean = emailConfig.regex.matches(email)

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

private val sendEmailScope = CoroutineScope(Dispatchers.IO)

fun sendEmail(email: String, code: String, usage: EmailCodes.EmailCodeUsage) = sendEmailScope.async()
{
    withTimeout(15.seconds)
    {
        @Suppress("NAME_SHADOWING")
        val email = email.lowercase()
        val props = Properties()
        props.setProperty("mail.smtp.auth", "true")
        props.setProperty("mail.host", emailConfig.host)
        props.setProperty("mail.port", emailConfig.port.toString())
        props.setProperty("mail.smtp.starttls.enable", "true")
        val session = Session.getInstance(props)
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(emailConfig.sender))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(email))
        message.subject = emailConfig.verifyEmailTitle

        val body =
            Loader
                .getResource("email.html")
                ?.readAllBytes()
                ?.decodeToString()
                ?.replace("{code}", code)
                ?.replace("{usage}", usage.description)
                ?: run {
                    logger.severe("Failed to load email.html")
                    logger.severe("Send email failed: email: $email, code: $code, usage: $usage")
                    return@withTimeout
                }

        val mimeMultipart = MimeMultipart()
        val mimeBodyPart = MimeBodyPart()
        mimeBodyPart.setContent(body, "text/html; charset=utf-8")
        mimeMultipart.addBodyPart(mimeBodyPart)
        message.setContent(mimeMultipart)

        val transport = session.getTransport("smtp")
        transport.connect(emailConfig.host, emailConfig.port, emailConfig.sender, emailConfig.password)
        transport.sendMessage(message, arrayOf<Address>(InternetAddress(email)))
        transport.close()
    }
}