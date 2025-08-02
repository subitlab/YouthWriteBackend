package subit.database

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.inject
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.Star
import subit.dataClasses.UserId
import subit.database.StarCountTriggerManager.setupTriggers
import subit.database.utils.asSlice
import subit.logger.YouthWriteLogger
import subit.utils.toInstant
import kotlin.getValue
import kotlin.time.Duration

/**
 * 收藏数据库交互类
 */
class Stars: DaoSqlImpl<Stars.StarTable>(StarTable)
{
    object StarTable: CompositeIdTable("stars")
    {
        val user = reference("user", Users.UsersTable).index()
        val post = reference("post", Posts.PostTable).index()
        val time = timestamp("time").index().defaultExpression(CurrentTimestamp)
        override val primaryKey = PrimaryKey(user, post)

        init
        {
            addIdColumn(user)
            addIdColumn(post)
        }
    }

    override fun Transaction.afterTableCreated()
    {
        val posts: Posts by inject()
        setupTriggers(posts.table, StarTable)
    }

    private fun deserialize(row: ResultRow) = Star(
        user = row[StarTable.user].value,
        post = row[StarTable.post].value,
        time = row[StarTable.time].toEpochMilliseconds()
    )

    suspend fun addStar(uid: UserId, pid: PostId): Unit = query()
    {
        insertIgnoreAndGetId {
            it[StarTable.user] = uid
            it[StarTable.post] = pid
        }
    }

    suspend fun removeStar(uid: UserId, pid: PostId): Unit = query()
    {
        deleteWhere {
            (StarTable.user eq uid) and (StarTable.post eq pid)
        }
    }

    suspend fun getStar(uid: UserId, pid: PostId): Boolean = query()
    {
        selectAll().where { (user eq uid) and (post eq pid) }.count() > 0
    }

    suspend fun getStarsCount(pid: PostId): Long = query()
    {
        StarTable.selectAll().where { post eq pid }.count()
    }

    suspend fun getStars(
        user: UserId? = null,
        post: PostId? = null,
        reverseOrder: Boolean = true,
        begin: Long = 0,
        limit: Int = Int.MAX_VALUE,
    ): Slice<Star> = query()
    {
        val query = table.selectAll()
        query.orderBy(table.time, if (reverseOrder) SortOrder.DESC else SortOrder.ASC)
        user?.let { query.andWhere { table.user eq it } }
        post?.let { query.andWhere { table.post eq it } }
        query.asSlice(begin, limit).map { deserialize(it) }
    }

    suspend fun totalStarsCount(duration: Duration?): Long = query()
    {
        val time = duration?.let { Clock.System.now() - it } ?: 0L.toInstant()
        table.selectAll().where { table.time greaterEq time }.count()
    }
}

object StarCountTriggerManager
{
    private val logger = YouthWriteLogger.getLogger<StarCountTriggerManager>()

    private const val INSERT_FUNCTION = "update_starCount_insert"
    private const val DELETE_FUNCTION = "update_starCount_delete"
    private const val UPDATE_FUNCTION = "update_starCount_update"
    private const val INSERT_TRIGGER = "trigger_after_star_insert"
    private const val DELETE_TRIGGER = "trigger_after_star_delete"
    private const val UPDATE_TRIGGER = "trigger_after_star_update"

    fun Transaction.setupTriggers(postsTable: Posts.PostTable, starsTable: Stars.StarTable) {
        try {
            // 将 Table 对象传递给内部函数
            createFunctionsIfNotExists(postsTable)
            createTriggersIfNotExists(starsTable)
            logger.info("Like count triggers initialized successfully")
        } catch (e: Exception) {
            logger.severe("Failed to initialize like count triggers", e)
            throw e
        }
    }

    private fun Transaction.createFunctionsIfNotExists(postsTable: Posts.PostTable)
    {
        deleteFunction(INSERT_FUNCTION)
        exec(
            """
            CREATE FUNCTION $INSERT_FUNCTION() RETURNS TRIGGER AS $$
            BEGIN
                UPDATE ${postsTable.tableName} 
                SET "starCount" = "starCount" + 1 
                WHERE id = NEW.post;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )

        deleteFunction(DELETE_FUNCTION)
        exec(
            """
            CREATE FUNCTION $DELETE_FUNCTION() RETURNS TRIGGER AS $$
            BEGIN
                UPDATE ${postsTable.tableName} 
                SET "starCount" = "starCount" - 1 
                WHERE id = OLD.post;
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )

        deleteFunction(UPDATE_FUNCTION)
        exec(
            """
            CREATE FUNCTION $UPDATE_FUNCTION() RETURNS TRIGGER AS $$
            BEGIN
                IF OLD.post <> NEW.post THEN
                    UPDATE ${postsTable.tableName}  SET "starCount" = "starCount" - 1 WHERE id = OLD.post;
                    UPDATE ${postsTable.tableName}  SET "starCount" = "starCount" + 1 WHERE id = NEW.post;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
    }

    private fun Transaction.createTriggersIfNotExists(starsTable: Stars.StarTable)
    {
        deleteTrigger(INSERT_TRIGGER)
        exec(
            """
            CREATE TRIGGER $INSERT_TRIGGER
            AFTER INSERT ON ${starsTable.tableName} 
            FOR EACH ROW
            EXECUTE FUNCTION $INSERT_FUNCTION();
            """.trimIndent()
        )
        deleteTrigger(DELETE_TRIGGER)
        exec(
            """
            CREATE TRIGGER $DELETE_TRIGGER
            AFTER DELETE ON ${starsTable.tableName} 
            FOR EACH ROW
            EXECUTE FUNCTION $DELETE_FUNCTION();
            """.trimIndent()
        )
        deleteTrigger(UPDATE_TRIGGER)
        exec(
            """
            CREATE TRIGGER $UPDATE_TRIGGER
            AFTER UPDATE ON ${starsTable.tableName} 
            FOR EACH ROW
            EXECUTE FUNCTION $UPDATE_FUNCTION();
            """.trimIndent()
        )
    }

    private fun Transaction.deleteFunction(functionName: String)
    {
        exec("DROP FUNCTION IF EXISTS $functionName() CASCADE;")
    }

    private fun Transaction.deleteTrigger(triggerName: String)
    {
        exec("DROP TRIGGER IF EXISTS $triggerName ON stars;")
    }
}