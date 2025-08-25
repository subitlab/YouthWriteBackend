package subit.database

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.KoinComponent
import subit.dataClasses.Like
import subit.dataClasses.PostId
import subit.dataClasses.Slice
import subit.dataClasses.UserId
import subit.database.utils.asSlice
import subit.utils.toInstant
import kotlin.time.Duration
import org.koin.core.component.inject
import subit.database.LikeCountTriggerManager.setupTriggers
import subit.logger.YouthWriteLogger

class Likes: DaoSqlImpl<Likes.LikeTable>(LikeTable), KoinComponent
{
    object LikeTable: CompositeIdTable("likes")
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
        setupTriggers(posts.table, LikeTable)
    }

    private fun deserialize(row: ResultRow) = Like(
        user = row[LikeTable.user].value,
        post = row[LikeTable.post].value,
        time = row[LikeTable.time].toEpochMilliseconds()
    )

    suspend fun addLike(uid: UserId, pid: PostId): Unit = query()
    {
        insertIgnoreAndGetId {
            it[user] = uid
            it[post] = pid
        }
    }

    suspend fun removeLike(uid: UserId, pid: PostId): Unit = query()
    {
        deleteWhere {
            (user eq uid) and (post eq pid)
        }
    }

    suspend fun getLike(uid: UserId, pid: PostId): Boolean = query()
    {
        selectAll().where { (user eq uid) and (post eq pid) }.count() > 0
    }

    suspend fun getLikesCount(pid: PostId): Long = query()
    {
        LikeTable.select(id).where { post eq pid }.count()
    }

    suspend fun getLikes(
        user: UserId? = null,
        post: PostId? = null,
        reverseOrder: Boolean = true,
        begin: Long = 1,
        limit: Int = Int.MAX_VALUE,
    ): Slice<Like> = query()
    {
        val query = table.selectAll()
        query.orderBy(table.time, if (reverseOrder) SortOrder.DESC else SortOrder.ASC)
        user?.let { query.andWhere { table.user eq it } }
        post?.let { query.andWhere { table.post eq it } }
        query.asSlice(begin, limit).map { deserialize(it) }
    }

    suspend fun totalLikesCount(duration: Duration?): Long = query()
    {
        val time = duration?.let { Clock.System.now() - it } ?: 0L.toInstant()
        table.selectAll().where { table.time greaterEq time }.count()
    }

    suspend fun bindNewAccount(oldUserId: UserId, newUserId: UserId): Unit = query()
    {
        insertIgnore(select(intParam(newUserId.value).alias(table.user.name), post).where { table.user eq oldUserId }, listOf(table.user, table.post))
        deleteWhere { table.user eq oldUserId }
    }
}

object LikeCountTriggerManager
{
    private val logger = YouthWriteLogger.getLogger<LikeCountTriggerManager>()

    private const val INSERT_FUNCTION = "update_likeCount_insert"
    private const val DELETE_FUNCTION = "update_likeCount_delete"
    private const val UPDATE_FUNCTION = "update_likeCount_update"
    private const val INSERT_TRIGGER = "trigger_after_like_insert"
    private const val DELETE_TRIGGER = "trigger_after_like_delete"
    private const val UPDATE_TRIGGER = "trigger_after_like_update"

    fun Transaction.setupTriggers(postsTable: Posts.PostTable, likesTable: Likes.LikeTable) {
        try {
            // 将 Table 对象传递给内部函数
            createFunctionsIfNotExists(postsTable)
            createTriggersIfNotExists(likesTable)
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
                SET "likeCount" = "likeCount" + 1 
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
                SET "likeCount" = "likeCount" - 1 
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
                    UPDATE ${postsTable.tableName}  SET "likeCount" = "likeCount" - 1 WHERE id = OLD.post;
                    UPDATE ${postsTable.tableName}  SET "likeCount" = "likeCount" + 1 WHERE id = NEW.post;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
    }

    private fun Transaction.createTriggersIfNotExists(likesTable: Likes.LikeTable)
    {
        deleteTrigger(INSERT_TRIGGER)
        exec(
        """
            CREATE TRIGGER $INSERT_TRIGGER
            AFTER INSERT ON ${likesTable.tableName} 
            FOR EACH ROW
            EXECUTE FUNCTION $INSERT_FUNCTION();
            """.trimIndent()
        )
        deleteTrigger(DELETE_TRIGGER)
        exec(
        """
            CREATE TRIGGER $DELETE_TRIGGER
            AFTER DELETE ON ${likesTable.tableName} 
            FOR EACH ROW
            EXECUTE FUNCTION $DELETE_FUNCTION();
            """.trimIndent()
        )
        deleteTrigger(UPDATE_TRIGGER)
        exec(
        """
            CREATE TRIGGER $UPDATE_TRIGGER
            AFTER UPDATE ON ${likesTable.tableName} 
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
        exec("DROP TRIGGER IF EXISTS $triggerName ON likes;")
    }
}