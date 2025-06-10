package subit.dataClasses

import kotlinx.serialization.Serializable
import subit.database.EmailCodes


@Serializable
data class EmailInfo(val email: String, val usage: EmailCodes.EmailCodeUsage)
