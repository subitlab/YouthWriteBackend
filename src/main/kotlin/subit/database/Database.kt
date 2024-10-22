package subit.database

import io.ktor.server.application.*
import subit.console.AnsiStyle.Companion.RESET
import subit.console.SimpleAnsiColor.Companion.CYAN
import subit.console.SimpleAnsiColor.Companion.GREEN
import subit.console.SimpleAnsiColor.Companion.RED
import subit.database.memoryImpl.MemoryDatabaseImpl
import subit.database.sqlImpl.SqlDatabaseImpl
import subit.logger.YouthWriteLogger
import subit.utils.Power.shutdown
import kotlin.reflect.KClass

val databaseImpls: List<IDatabase> = listOf(
    SqlDatabaseImpl,
    MemoryDatabaseImpl
)

fun Application.loadDatabaseImpl()
{
    val logger = YouthWriteLogger.getLogger()
    val impls = databaseImpls.associateBy { it.name }
    logger.config("Available database implementations: ${impls.keys.joinToString(", ")}")

    val databaseImpl = environment.config.propertyOrNull("database.impl")?.getString()?.lowercase()

    if (databaseImpl == null)
    {
        val implNames = impls.keys.joinToString(", ")
        logger.severe("${RED}Database implementation not found")
        logger.severe("${RED}Please add properties in application.conf: ${CYAN}database.impl ${GREEN}(options: $implNames)${RESET}")
        shutdown(1, "Database implementation not found")
    }

    val impl = impls[databaseImpl]
    if (impl != null)
    {
        logger.info("Using database implementation: $databaseImpl")
        impl.apply {
            init()
        }
        return
    }
    logger.severe("${RED}Database implementation not found: $GREEN$databaseImpl")
    logger.severe("${RED}Available implementations: $GREEN${impls.keys.joinToString(", ")}")
    shutdown(1, "Database implementation not found")
}

data class DaoImpl<T: Any>(val constructor: () -> T, val type: KClass<T>)

interface IDatabase
{
    val name: String
    fun Application.init()
}