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
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
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

    init
    {
        LikeCountTriggerManager.setupTriggers(database)
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
        LikeTable.selectAll().where { post eq pid }.count()
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

    suspend fun claimUserLikes(oldUserId: UserId, newUserId: UserId): Unit = query()
    {
        table.update({ table.user eq oldUserId }) {
            it[user] = newUserId
        }
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

    fun setupTriggers(db: Database)
    {
        transaction(db)
        {
            try
            {
                createFunctionsIfNotExists()
                createTriggersIfNotExists()
                logger.info("Database triggers initialized successfully")
            }
            catch (e: Exception)
            {
                logger.severe("Failed to initialize database triggers", e)
                throw e
            }
        }
    }

    private fun Transaction.createFunctionsIfNotExists()
    {
        deleteFunction(INSERT_FUNCTION)
        exec(
        """
            CREATE FUNCTION $INSERT_FUNCTION() RETURNS TRIGGER AS $$
            BEGIN
                UPDATE posts 
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
                UPDATE posts 
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
                    UPDATE posts SET "likeCount" = "likeCount" - 1 WHERE id = OLD.post;
                    UPDATE posts SET "likeCount" = "likeCount" + 1 WHERE id = NEW.post;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
    }

    private fun Transaction.createTriggersIfNotExists()
    {
        deleteTrigger(INSERT_TRIGGER)
        exec(
        """
            CREATE TRIGGER $INSERT_TRIGGER
            AFTER INSERT ON likes
            FOR EACH ROW
            EXECUTE FUNCTION $INSERT_FUNCTION();
            """.trimIndent()
        )
        deleteTrigger(DELETE_TRIGGER)
        exec(
        """
            CREATE TRIGGER $DELETE_TRIGGER
            AFTER DELETE ON likes
            FOR EACH ROW
            EXECUTE FUNCTION $DELETE_FUNCTION();
            """.trimIndent()
        )
        deleteTrigger(UPDATE_TRIGGER)
        exec(
        """
            CREATE TRIGGER $UPDATE_TRIGGER
            AFTER UPDATE ON likes
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