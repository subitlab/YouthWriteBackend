package subit.database.sqlImpl

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import subit.console.AnsiStyle.Companion.RESET
import subit.console.SimpleAnsiColor.Companion.CYAN
import subit.console.SimpleAnsiColor.Companion.GREEN
import subit.console.SimpleAnsiColor.Companion.RED
import subit.dataClasses.*
import subit.database.*
import subit.logger.ForumLogger
import subit.utils.Power.shutdown
import java.sql.Driver
import kotlin.reflect.KClass

/**
 * @param T 表类型
 * @property table 表
 */
abstract class DaoSqlImpl<T: Table>(table: T): KoinComponent
{
    suspend inline fun <R> query(crossinline block: suspend T.(Transaction)->R) = table.run {
        newSuspendedTransaction(Dispatchers.IO) { block(this) }
    }

    private val database: Database by inject()
    val table: T by lazy {
        transaction(database)
        {
            SchemaUtils.createMissingTablesAndColumns(table)
        }
        table
    }
}

/**
 * 数据库单例
 */
object SqlDatabaseImpl: IDatabase, KoinComponent
{
    /**
     * 数据库
     */
    private lateinit var config: ApplicationConfig
    private val logger = ForumLogger.getLogger()
    private val drivers:List<Driver> = listOf(
        org.postgresql.Driver(),
    )

    /**
     * 创建Hikari数据源,即数据库连接池
     */
    private fun createHikariDataSource(
        url: String,
        driver: String,
        user: String?,
        password: String?
    ) = HikariDataSource(HikariConfig().apply {
        this.driverClassName = driver
        this.jdbcUrl = url
        if (user != null) this.username = user
        if (password != null) this.password = password
        this.maximumPoolSize = 3
        this.isAutoCommit = false
        this.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        this.poolName = "subit"
        validate()
    })

    override val name: String = "sql"

    /**
     * 初始化数据库.
     */
    override fun Application.init()
    {
        config = environment.config
        val lazyInit = config.propertyOrNull("database.sql.lazyInit")?.getString()?.toBoolean() ?: true

        logger.info("Init database. impl: sql, LazyInit: $lazyInit")
        val url = config.propertyOrNull("database.sql.url")?.getString()
        val driver0 = config.propertyOrNull("database.sql.driver")?.getString()
        val user = config.propertyOrNull("database.sql.user")?.getString()
        val password = config.propertyOrNull("database.sql.password")?.getString()

        if (url == null)
        {
            logger.severe("${RED}Database configuration not found.")
            logger.severe("${RED}Please add properties in application.conf:")
            logger.severe("${CYAN}database.sql.url${RESET}")
            logger.severe("${CYAN}database.sql.driver${RESET} (optional)")
            logger.severe("${CYAN}database.sql.user${GREEN} (optional)${RESET}")
            logger.severe("${CYAN}database.sql.password${GREEN} (optional)${RESET}")
            logger.severe("${CYAN}database.sql.lazyInit${GREEN} (optional, default = true)${RESET}")

            shutdown(1, "Database configuration not found.")
        }

        val driver = if (driver0.isNullOrEmpty())
        {
            logger.warning("Sql driver not found, try to load driver by url.")
            val driver = drivers.find { it.acceptsURL(url) }?.javaClass?.name ?: run {
                logger.severe("${RED}Driver not found.")
                logger.severe("${RED}Please confirm that your database is supported.")
                logger.severe("${RED}If it is supported, Please specify the driver manually by adding the option:")
                logger.severe("${CYAN}database.sql.driver")
                shutdown(1, "Driver not found.")
            }
            logger.info("Load driver by url: $driver")
            driver
        }
        else driver0

        logger.info("Load database configuration. url: $url, driver: $driver, user: $user")

        val impls: List<DaoImpl<*>> = listOf(
            DaoImpl(::BannedWordsImpl, BannedWords::class),
            DaoImpl(::BlocksImpl, Blocks::class),
            DaoImpl(::LikesImpl, Likes::class),
            DaoImpl(::NoticesImpl, Notices::class),
            DaoImpl(::OperationsImpl, Operations::class),
            DaoImpl(::PermissionsImpl, Permissions::class),
            DaoImpl(::PostsImpl, Posts::class),
            DaoImpl(::PostVersionsImpl, PostVersions::class),
            DaoImpl(::PrivateChatsImpl, PrivateChats::class),
            DaoImpl(::ProhibitsImpl, Prohibits::class),
            DaoImpl(::ReportsImpl, Reports::class),
            DaoImpl(::StarsImpl, Stars::class),
            DaoImpl(::TagsImpl, Tags::class),
            DaoImpl(::UsersImpl, Users::class),
            DaoImpl(::WordMarkingsImpl, WordMarkings::class),
        )

        val module = module(!lazyInit)
        {
            named("sql-database-impl")

            single { Database.connect(createHikariDataSource(url, driver, user, password)) }.bind<Database>()

            for (impl in impls)
            {
                @Suppress("UNCHECKED_CAST")
                singleOf(impl.constructor).bind(impl.type as KClass<Any>)
            }
        }
        getKoin().loadModules(listOf(module))

        if (!lazyInit)
        {
            logger.info("${CYAN}Using database implementation: ${RED}sql${CYAN}, and ${RED}lazyInit${CYAN} is ${GREEN}false.")
            logger.info("${CYAN}It may take a while to initialize the database. Please wait patiently.")

            impls.map { it.type }.map { getKoin().get(it) as DaoSqlImpl<*> }.forEach { it.table }
        }
    }
}

/////////////////////////////////////
/// 定义数据库一些类型在数据库中的类型 ///
/////////////////////////////////////

/**
 * 包装的列类型, 用于将数据库中的列类型转换为其他类型
 * @param Base 原始类型
 * @param T 转换后的类型
 * @property base 原始列类型
 * @property warp 转换函数
 * @property unwrap 反转换函数
 */
abstract class WarpColumnType<Base: Any, T: Any>(
    private val base: ColumnType<Base>,
    private val warp: (Base)->T,
    private val unwrap: (T)->Base
): ColumnType<T>()
{
    override fun sqlType() = base.sqlType()
    override fun valueFromDB(value: Any) = base.valueFromDB(value)?.let(warp)
    override fun notNullValueToDB(value: T): Any = base.notNullValueToDB(unwrap(value))
}

// BlockId
class BlockIdColumnType: WarpColumnType<Int, BlockId>(IntegerColumnType(), ::BlockId, BlockId::value)
fun Table.blockId(name: String) = registerColumn(name, BlockIdColumnType())

// UserId
class UserIdColumnType: WarpColumnType<Int, UserId>(IntegerColumnType(), ::UserId, UserId::value)
fun Table.userId(name: String) = registerColumn(name, UserIdColumnType())

// PostId
class PostIdColumnType: WarpColumnType<Long, PostId>(LongColumnType(), ::PostId, PostId::value)
fun Table.postId(name: String) = registerColumn(name, PostIdColumnType())

// PostVersionId
class PostVersionIdColumnType: WarpColumnType<Long, PostVersionId>(LongColumnType(), ::PostVersionId, PostVersionId::value)
fun Table.postVersionId(name: String) = registerColumn(name, PostVersionIdColumnType())

// ReportId
class ReportIdColumnType: WarpColumnType<Long, ReportId>(LongColumnType(), ::ReportId, ReportId::value)
fun Table.reportId(name: String) = registerColumn(name, ReportIdColumnType())

// NoticeId
class NoticeIdColumnType: WarpColumnType<Long, NoticeId>(LongColumnType(), ::NoticeId, NoticeId::value)
fun Table.noticeId(name: String) = registerColumn(name, NoticeIdColumnType())

// WordMarkingId
class WordMarkingIdColumnType: WarpColumnType<Long, WordMarkingId>(LongColumnType(), ::WordMarkingId, WordMarkingId::value)
fun Table.wordMarkingId(name: String) = registerColumn(name, WordMarkingIdColumnType())