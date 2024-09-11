@file:Suppress("PackageDirectoryMismatch")

package subit.router.posts

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.JWTAuth.getLoginUser
import subit.dataClasses.*
import subit.dataClasses.BlockId.Companion.toBlockIdOrNull
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.dataClasses.PostVersionId.Companion.toPostVersionIdOrNull
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.*
import subit.plugin.RateLimit
import subit.router.*
import subit.utils.HttpStatus
import subit.utils.Locks
import subit.utils.respond
import subit.utils.statuses

fun Route.posts() = route("/post", {
    tags = listOf("帖子")
})
{

    rateLimit(RateLimit.Post.rateLimitName)
    {
        post("/new", {
            description = "新建帖子"
            request {
                authenticated(true)
                body<NewPost>
                {
                    required = true
                    description = "发帖, 成功返回帖子ID."
                    example("example", NewPost("标题", "内容", false, BlockId(0), false))
                }
            }
            response {
                statuses<PostId>(HttpStatus.OK, example = PostId(0))
                statuses(HttpStatus.BadRequest, HttpStatus.TooManyRequests)
            }
        }) { newPost() }
    }

    get("/top/{block}", {
        description = "获取板块置顶帖子列表"
        request {
            authenticated(false)
            pathParameter<BlockId>("block")
            {
                required = true
                description = "板块ID"
            }
            paged()
        }
        response {
            statuses<Slice<PostFullBasicInfo>>(HttpStatus.OK, example = sliceOf(PostFullBasicInfo.example))
        }
    }) { getBlockTopPosts() }

    id()
    list()
    version()
}

private fun Route.id() = route("/{id}",{
    request {
        pathParameter<PostId>("id")
        {
            required = true
            description = "帖子ID"
        }
    }
    response {
        statuses(HttpStatus.NotFound)
    }
})
{
    get("", {
        description = "获取帖子信息"
        request {
            authenticated(false)
        }
        response {
            statuses<PostFull>(HttpStatus.OK, example = PostFull.example)
        }
    }) { getPost() }

    delete("", {
        description = "删除帖子"
        request {
            authenticated(true)
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { deletePost() }

    rateLimit(RateLimit.Post.rateLimitName)
    {
        put("", {
            description = "编辑帖子(block及以上管理员可修改)"
            request {
                authenticated(true)
                pathParameter<PostId>("id")
                {
                    required = true
                    description = "帖子ID"
                }
                body<EditPost>
                {
                    required = true
                    description = "编辑帖子"
                    example("example", EditPost("新标题", "新内容"))
                }
            }
            response {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.BadRequest, HttpStatus.TooManyRequests)
            }
        }) { editPost() }
    }

    post("/like", {
        description = "点赞/点踩/取消点赞/收藏/取消收藏 帖子"
        request {
            authenticated(true)
            body<LikePost>
            {
                required = true
                description = "点赞/点踩/取消点赞/收藏/取消收藏"
                example("example", LikePost(LikeType.LIKE))
            }
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { likePost() }

    rateLimit(RateLimit.AddView.rateLimitName)
    {
        post("/view", {
            description = "增加帖子浏览量, 应在用户打开帖子时调用. 若未登陆将不会增加浏览量"
            request {
                authenticated(true)
                queryParameter<PostId>("id")
                {
                    required = true
                    description = "帖子ID"
                }
            }
            response {
                statuses(HttpStatus.OK, HttpStatus.TooManyRequests, HttpStatus.Unauthorized)
            }
        }) { addView() }
    }

    post("/setTop/{top}", {
        description = "设置帖子是否置顶"
        request {
            authenticated(true)
            pathParameter<Boolean>("top")
            {
                required = true
                description = "是否置顶"
                example(true)
            }
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { setBlockTopPosts() }
}

private fun Route.list() = route("/list",{
    request {
        authenticated(false)
        paged()
        queryParameter<Posts.PostListSort>("sort")
        {
            required = true
            description = "排序方式"
            example(Posts.PostListSort.NEW)
        }
    }
    response {
        statuses<Slice<PostFullBasicInfo>>(HttpStatus.OK, example = sliceOf(PostFullBasicInfo.example))
    }
})
{
    get("/user/{user}", {
        description = "获取用户发送的帖子列表"
        request {
            pathParameter<UserId>("user")
            {
                required = true
                description = "作者ID"
            }
        }
    }) { getUserPosts() }

    get("/block/{block}", {
        description = "获取板块帖子列表"
        request {
            pathParameter<BlockId>("block")
            {
                required = true
                description = "板块ID"
            }
        }
    }) { getBlockPosts() }
}

private fun Route.version() = route("/version", {
    response {
        statuses(HttpStatus.NotFound)
    }
})
{
    get("/list/{postId}", {
        request {
            authenticated(false)
            paged()
            pathParameter<PostId>("postId")
            {
                required = true
                description = "帖子ID"
            }
        }
        response {
            statuses<Slice<PostVersionBasicInfo>>(HttpStatus.OK, example = sliceOf(PostVersionBasicInfo.example))
        }
    }) { listVersions() }

    get("/{versionId}", {
        request {
            authenticated(false)
            pathParameter<PostVersionId>("versionId")
            {
                required = true
                description = "版本ID"
            }
        }
        response {
            statuses<PostVersionInfo>(HttpStatus.OK, example = PostVersionInfo.example)
        }
    }) { getVersion() }
}

private suspend fun Context.getPost()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val postFull = get<Posts>().getPostFull(id) ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser()
    checkPermission { checkCanRead(postFull.toPostInfo()) }
    if (!postFull.anonymous) call.respond(HttpStatus.OK, postFull) // 若不是匿名帖则直接返回
    else if (loginUser == null || loginUser.permission < PermissionLevel.ADMIN) call.respond(
        HttpStatus.OK,
        postFull.copy(
            author = UserId(
                0
            )
        )
    )
    else call.respond(HttpStatus.OK, postFull) // 若是匿名帖且用户权限足够则返回
}

@Serializable
private data class EditPost(val title: String, val content: String)

@Serializable data class Interval(val start: Int, val end: Int)

@Serializable
data class Operators(
    val insert: Map<Int, String> = mapOf(),
    val del: List<Interval> = listOf(),
    val newTitle: String? = null
)

// 因为编辑帖子计算开销较大, 所以使用锁保证同一时间只有一个编辑操作.
// 这里是保证同一用户只能同时编辑一个帖子
private val editPostLock = Locks<UserId>()
private suspend fun Context.editPost()
{
    val operators = receiveAndCheckBody<Operators>()
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val postInfo = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    if (postInfo.author != loginUser.id) call.respond(HttpStatus.Forbidden.copy(message = "文章仅允许作者编辑"))

    editPostLock.tryWithLock(loginUser.id, { call.respond(HttpStatus.TooManyRequests) })
    {
        val postVersions = get<PostVersions>()
        val wordMarkings = get<WordMarkings>()
        val oldVersion = postVersions.getLatestPostVersion(id) ?: return@tryWithLock call.respond(HttpStatus.NotFound)
        val oldVersionInfo = postVersions.getPostVersion(oldVersion) ?: return@tryWithLock call.respond(HttpStatus.NotFound)

        var markings = wordMarkings.getWordMarkings(oldVersion)

        val del = IntArray(oldVersionInfo.content.length + 1)
        for (i in operators.del) // 在每个删除区间的左端点处+1, 右端点+1处-1
        {
            del[i.start]++
            del[i.end+1]--
        }
        // 进行前缀和操作
        for (i in 1 until del.size) del[i] += del[i - 1]
        // 到此处del[i]表示原字符串中第i个字符被删除的次数, 理论上只能为0或1

        // 计算新的content
        val newContent = StringBuilder()
        for (i in oldVersionInfo.content.indices)
        {
            val insert = operators.insert[i]
            // 如果有插入的话先插入
            if (insert != null) newContent.append(insert)
            // 如果没有删除的话再添加
            if (del[i] == 0) newContent.append(oldVersionInfo.content[i])
        }
        // 如果末尾有插入的话添加
        val insert = operators.insert[oldVersionInfo.content.length]
        if (insert != null) newContent.append(insert)

        val newVersion = postVersions.createPostVersion(
            post = id,
            title = operators.newTitle ?: oldVersionInfo.title,
            content = newContent.toString()
        )

        markings.map { it.copy(postVersion = newVersion) }

        //////////////////////////// 以下为对划词评论的处理 ////////////////////////////

        val pre = IntArray(oldVersionInfo.content.length + 1)

        ///////// 处理划词评论中出现删除的情况 /////////

        // 对del数组进行再前缀和操作
        for (i in 1 until pre.size) pre[i] = pre[i - 1] + del[i - 1]
        // 到此处pre[i]表示原字符串中第i个字符及其之前的字符被删除的次数

        markings = markings.map {
            if (it.state != WordMarkingState.NORMAL) return@map it
            // 如果删除区间的左端点和右端点的删除次数相同, 则说明标记区间内的字符没有被删除, 不做处理
            // 注意: pre[start-1]是在区间前的字符被删除的次数, pre[end]是在末尾及之前的字符被删除的次数,
            // 所以要判断start-1和end是否相等. 如果判断start和end相等的话, 则会导致在start处的字符被删除的情况被忽略
            if (pre[it.start-1] == pre[it.end]) return@map it
            // 如果删除区间的左端点和右端点的删除次数不同, 则说明标记区间内的字符有被删除的情况
            // 需要将标记区间的状态设置为DELETED
            it.copy(state = WordMarkingState.DELETED, start = 0, end = 0)
        }
        // 到此处所有因为删除操作而被删除的标记都被处理完了
        for (i in pre.indices) pre[i] = 0 // 重置pre数组

        ///////// 处理划词评论中出现插入的情况 /////////

        // insert的位置进行前缀和操作
        for (i in 1 until pre.size) pre[i] = pre[i - 1] + (if (operators.insert[i - 1] != null) 1 else 0)
        // 到此处pre[i]表示原字符串中第i个字符及其之前的字符被插入的次数

        markings = markings.map {
            if (it.state != WordMarkingState.NORMAL) return@map it
            // 如果插入区间的左端点和右端点的插入次数相同, 则说明标记区间内的字符没有被插入, 不做处理
            // 注意: pre[start]是在区间前的字符被插入的次数, pre[end]是在末尾及之前的字符被插入的次数, 所以要判断start和end是否相等.
            if (pre[it.start] == pre[it.end]) return@map it
            // 如果插入区间的左端点和右端点的插入次数不同, 则说明标记区间内的字符有被插入的情况
            // 需要将标记区间的状态设置为DELETED
            it.copy(state = WordMarkingState.DELETED, start = 0, end = 0)
        }
        for (i in pre.indices) pre[i] = 0 // 重置pre数组

        ///////// 至此所有因为修改而失效的划词都处理完了, 接下来是修改导致的划词位置偏移 /////////

        for (i in 1 until pre.size) pre[i] = pre[i - 1] - del[i - 1] + (if (operators.insert[i - 1] != null) 1 else 0)
        // 到此处pre[i]表示原字符串中第i个字符及其之前的字符数目变化, 为正表示总的来说字符数目增加, 为负表示总的来说字符数目减少

        markings = markings.map {
            if (it.state != WordMarkingState.NORMAL) return@map it
            it.copy(start = it.start + pre[it.start], end = it.end + pre[it.end])
        }

        ///////// 至此所有的划词评论的处理结束了 /////////

        // 将新的标记写入数据库
        wordMarkings.batchAddWordMarking(markings)
    }

    call.respond(HttpStatus.OK)
}

private suspend fun Context.deletePost()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val post = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    checkPermission { checkCanDelete(post) }
    get<Posts>().setPostState(id, State.DELETED)
    if (post.author != loginUser.id) get<Notices>().createNotice(
        Notice.makeSystemNotice(
            user = post.author,
            content = "您的帖子 ${post.id} 已被删除"
        )
    )
    call.respond(HttpStatus.OK)
}

@Serializable
private enum class LikeType
{
    LIKE,
    UNLIKE,
    STAR,
    UNSTAR
}

@Serializable
private data class LikePost(val type: LikeType)

private suspend fun Context.likePost()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val post = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    val type = receiveAndCheckBody<LikePost>().type
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    checkPermission { checkCanRead(post) }
    when (type)
    {
        LikeType.LIKE    -> get<Likes>().like(loginUser.id, id)
        LikeType.UNLIKE  -> get<Likes>().unlike(loginUser.id, id)
        LikeType.STAR    -> get<Stars>().addStar(loginUser.id, id)
        LikeType.UNSTAR  -> get<Stars>().removeStar(loginUser.id, id)
    }
    if (loginUser.id != post.author && (type == LikeType.LIKE || type == LikeType.STAR))
        get<Notices>().createNotice(
            Notice.makeObjectMessage(
                type = if (type == LikeType.LIKE) Notice.Type.LIKE else Notice.Type.STAR,
                user = post.author,
                obj = id
            )
        )
    call.respond(HttpStatus.OK)
}

@Serializable
private data class NewPost(
    val title: String,
    val content: String,
    val anonymous: Boolean,
    val block: BlockId,
    val top: Boolean
)

private suspend fun Context.newPost()
{
    val newPost = receiveAndCheckBody<NewPost>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)

    val block = get<Blocks>().getBlock(newPost.block) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkCanPost(block) }

    if (newPost.anonymous) checkPermission { checkCanAnonymous(block) }
    if (newPost.top) checkPermission { checkHasAdminIn(block.id) }
    val id = get<Posts>().createPost(
        author = loginUser.id,
        anonymous = newPost.anonymous,
        block = newPost.block,
        top = newPost.top,
        parent = null
    )!! // 父帖子为null, 不可能出现找不到父帖子的情况
    get<PostVersions>().createPostVersion(
        post = id,
        title = newPost.title,
        content = newPost.content,
    )
    call.respond(HttpStatus.OK, id)
}

private suspend fun Context.getUserPosts()
{
    val loginUser = getLoginUser()
    val author = call.parameters["user"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val type = call.parameters["sort"]
                   ?.runCatching { Posts.PostListSort.valueOf(this) }
                   ?.getOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val posts = get<Posts>().getUserPosts(loginUser?.toDatabaseUser(), author, type, begin, count)
    call.respond(HttpStatus.OK, posts)
}

private suspend fun Context.getBlockPosts()
{
    val block = call.parameters["block"]?.toBlockIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val blockFull = get<Blocks>().getBlock(block) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkCanRead(blockFull) }
    val type = call.parameters["sort"]
                   ?.runCatching { Posts.PostListSort.valueOf(this) }
                   ?.getOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val posts = get<Posts>().getBlockPosts(block, type, begin, count)
    call.respond(HttpStatus.OK, posts)
}

private suspend fun Context.getBlockTopPosts()
{
    val block = call.parameters["block"]?.toBlockIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val blockFull = get<Blocks>().getBlock(block) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkCanRead(blockFull) }
    val (begin, count) = call.getPage()
    val posts = get<Posts>().getBlockTopPosts(block, begin, count)
    call.respond(HttpStatus.OK, posts)
}

private suspend fun Context.setBlockTopPosts()
{
    val pid = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val top = call.parameters["top"]?.toBooleanStrictOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val postInfo = get<Posts>().getPostInfo(pid) ?: return call.respond(HttpStatus.NotFound)
    checkPermission {
        checkCanRead(postInfo)
        checkHasAdminIn(postInfo.block)
    }
    if (!get<Posts>().setTop(pid, top = top)) return call.respond(HttpStatus.NotFound)
    call.respond(HttpStatus.OK)
}

private suspend fun Context.addView()
{
    getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val pid = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    get<Posts>().addView(pid)
    call.respond(HttpStatus.OK)
}

private suspend fun Context.listVersions()
{
    val postId = call.parameters["postId"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val versions = get<PostVersions>().getPostVersions(postId, begin, count)
    call.respond(HttpStatus.OK, versions)
}

private suspend fun Context.getVersion()
{
    val versionId = call.parameters["versionId"]?.toPostVersionIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val version = get<PostVersions>().getPostVersion(versionId) ?: return call.respond(HttpStatus.NotFound)
    call.respond(HttpStatus.OK, version)
}