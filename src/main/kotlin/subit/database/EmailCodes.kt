package subit.database

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import subit.config.emailConfig
import subit.logger.YouthWriteLogger
import subit.utils.sendEmail
import java.time.OffsetDateTime

class EmailCodes: DaoSqlImpl<EmailCodes.EmailsTable>(EmailsTable)
{
    object EmailsTable: Table("email_codes")
    {
        val email = varchar("email", 100).index()
        val code = varchar("code", 10)
        val time = timestampWithTimeZone("time").index()
        val usage = enumerationByName<EmailCodeUsage>("usage", 20)
    }

    @Serializable
    enum class EmailCodeUsage(@Transient val description: String)
    {
        CLAIM_AUTHOR("认领账户"),
    }

    init
    {
        val logger = YouthWriteLogger.getLogger()
        // 启动定期清理过期验证码任务
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch()
        {
            while (true)
            {
                logger.config("Clearing expired email codes")
                logger.severe("Failed to clear expired email codes") { clearExpiredEmailCode() }
                delay(1000/*ms*/*60/*s*/*5/*min*/)
            }
        }
    }

    suspend fun addEmailCode(email: String, code: String, usage: EmailCodeUsage): Unit = query()
    {
        insert {
            it[EmailsTable.email] = email.lowercase()
            it[EmailsTable.code] = code
            it[EmailsTable.usage] = usage
            it[time] = OffsetDateTime.now().plusSeconds(emailConfig.codeValidTime)
        }
    }

    /**
     * 验证邮箱验证码，验证成功后将立即删除验证码
     */
    suspend fun verifyEmailCode(email: String, code: String, usage: EmailCodeUsage): Boolean = query()
    {
        @Suppress("NAME_SHADOWING")
        val email = email.lowercase()
        val result = select(time).where {
            (EmailsTable.email eq email) and (EmailsTable.code eq code) and (EmailsTable.usage eq usage)
        }.singleOrNull()?.let { it[time] }

        if (result != null)
        {
            EmailsTable.deleteWhere {
                (EmailsTable.email eq email) and (EmailsTable.code eq code) and (EmailsTable.usage eq usage)
            }
        }

        result != null && result >= OffsetDateTime.now()
    }

    private suspend fun clearExpiredEmailCode(): Unit = query()
    {
        EmailsTable.deleteWhere { time lessEq OffsetDateTime.now() }
    }
}

private val logger = YouthWriteLogger.getLogger()

suspend fun EmailCodes.sendEmailCode(email: String, usage: EmailCodes.EmailCodeUsage)
{
    @Suppress("NAME_SHADOWING")
    val email = email.lowercase()
    val code = (1..6).map { ('0'..'9').random() }.joinToString("")
    sendEmail(email, code, usage).invokeOnCompletion {
        if (it != null) logger.severe("发送邮件失败: email: $email, usage: $usage", it)
        else logger.info("发送邮件成功: $email, $code, $usage")
    }
    addEmailCode(email, code, usage)
}