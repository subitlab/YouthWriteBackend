@file:Suppress("PackageDirectoryMismatch")

package subit.router.posts

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.BlockId.Companion.toBlockIdOrNull
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.dataClasses.PostVersionId.Companion.toPostVersionIdOrNull
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.*
import subit.plugin.RateLimit
import subit.router.utils.*
import subit.utils.*

fun Route.posts() = route("/post", {
    tags = listOf("帖子")
})
{

    rateLimit(RateLimit.Post.rateLimitName)
    {
        post("/new", {
            description = "新建帖子"
            request {
                body<NewPost>
                {
                    required = true
                    description = "发帖, 成功返回帖子ID. state为帖子状态, 不允许为DELETE, PRIVATE为预留"
                    example("example", NewPost("标题", "内容", false, BlockId(0), false, State.NORMAL, false))
                }
            }
            response {
                statuses<PostId>(HttpStatus.OK, example = PostId(0))
                statuses(HttpStatus.BadRequest, HttpStatus.TooManyRequests)
            }
        }) { newPost() }
    }

    get("/list",{
        description = "获取帖子列表, 不登录也可以获取, 但是登录/有相应权限的人可能会看到更多内容"
        request {
            paged()
            queryParameter<Posts.PostListSort>("sort")
            {
                required = true
                description = "排序方式"
                example(Posts.PostListSort.NEW)
            }
            queryParameter<UserId>("author")
            {
                required = false
                description = "作者ID, 不填则为所有用户, 允许填0表示登录用户自己"
            }
            queryParameter<BlockId>("block")
            {
                required = false
                description = "板块ID, 不填则为所有板块"
            }
            queryParameter<Boolean>("top")
            {
                required = false
                description = "是否置顶, 不填则为所有"
            }
            queryParameter<State>("state")
            {
                required = false
                description = "帖子状态, 不填则为所有"
            }
            queryParameter<String>("tag")
            {
                required = false
                description = "标签, 不填则为所有"
            }
            queryParameter<Boolean>("comment")
            {
                required = false
                description = "若为true则只返回评论, 若为false则只返回帖子, 不填视为false"
            }
        }
        response {
            statuses<Slice<PostFullBasicInfo>>(HttpStatus.OK, example = sliceOf(PostFullBasicInfo.example))
        }
    }) { getPosts() }

    id()
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
        response {
            statuses<PostFull>(HttpStatus.OK, example = PostFull.example)
        }
    }) { getPost() }

    put("/state", {
        description = "修改帖子状态"
        request {
            queryParameter<State>("state")
            {
                required = true
                description = "帖子状态"
                example(State.DELETED)
            }
        }
        response {
            statuses(HttpStatus.OK)
        }
    }) { changeState() }

    rateLimit(RateLimit.Post.rateLimitName)
    {
        put("", {
            description = """
                编辑帖子
                
                注意: 应当通过GET /post/{id}/version/list 获取最新版本ID, 并在最新版本的基础上进行编辑. 
                GET /post/{id} 返回的最新版本ID是最新的非草稿版本ID, 但最新版本可能是草稿版本.
                """.trimIndent()
            request {
                body<EditPost>
                {
                    required = true
                    description = "编辑帖子"
                    example("example", EditPost(mapOf(0 to "a"), listOf(Interval(1, 2)), "new title", false, PostVersionId(0)))
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

    get("/like", {
        description = "获取帖子的点赞/点踩/收藏状态"
        response {
            statuses<LikeStatus>(HttpStatus.OK, example = LikeStatus(like = true, star = false))
        }
    }) { getLikeStatus() }

    rateLimit(RateLimit.AddView.rateLimitName)
    {
        post("/view", {
            description = "增加帖子浏览量, 应在用户打开帖子时调用. 若未登陆将不会增加浏览量"
            response {
                statuses(HttpStatus.OK, HttpStatus.TooManyRequests, HttpStatus.Unauthorized)
            }
        }) { addView() }
    }

    post("/setTop/{top}", {
        description = "设置帖子是否置顶"
        request {
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

private fun Route.version() = route("/version", {
    response {
        statuses(HttpStatus.NotFound)
    }
})
{
    get("/list/{postId}", {
        description = "获取帖子的版本列表, 如果当前登录用户是作者或者全局管理员的话可以获得包含草稿版本在内的所有版本, 否则只能获得非草稿版本"
        request {
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
        description = "获取帖子版本信息, 需要当前登录的用户可以看到该版本所属的帖子, 并且草稿版本只能由作者或全局管理员查看"
        request {
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
    withPermission { checkRead(postFull.toPostInfo()) }
    call.respond(HttpStatus.OK, checkAnonymous(postFull))
}

@Serializable data class Interval(val start: Int, val end: Int)

@Serializable
data class EditPost(
    val insert: Map<Int, String> = mapOf(),
    val del: List<Interval> = listOf(),
    val newTitle: String? = null,
    val draft: Boolean,
    val oldVersionId: PostVersionId,
)

val editPostLock = Locks<PostId>()
private suspend fun Context.editPost()
{
    val operators = receiveAndCheckBody<EditPost>()
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val postInfo = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    if (postInfo.author != loginUser.id) call.respond(HttpStatus.Forbidden.subStatus(message = "文章仅允许作者编辑"))
    if ((operators.newTitle?.length ?: 0) >= 256) return call.respond(HttpStatus.BadRequest.subStatus(message = "标题过长"))

    editPostLock.tryWithLock(id, { call.respond(HttpStatus.TooManyRequests) })
    {
        val postVersions = get<PostVersions>()
        val wordMarkings = get<WordMarkings>()
        val oldVersion = postVersions.getLatestPostVersion(id, true) ?: return@tryWithLock call.respond(HttpStatus.NotFound)
        if (operators.oldVersionId != oldVersion) return@tryWithLock call.respond(HttpStatus.NotLatestVersion)
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
            content = newContent.toString(),
            draft = operators.draft

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

private suspend fun Context.changeState()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val state = call.parameters["state"]?.toEnumOrNull<State>() ?: return call.respond(HttpStatus.BadRequest)
    val post = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    withPermission { checkChangeState(post, state) }

    if (post.state == state) return call.respond(HttpStatus.OK)

    get<Posts>().setPostState(id, state)
    if (post.author != loginUser.id) get<Notices>().createNotice(
        Notice.makeSystemNotice(
            user = post.author,
            content = "您的帖子 ${post.id} 已被管理员修改状态为 $state"
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
    withPermission { checkRead(post) }
    when (type)
    {
        LikeType.LIKE    -> get<Likes>().addLike(loginUser.id, id)
        LikeType.UNLIKE  -> get<Likes>().removeLike(loginUser.id, id)
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
data class LikeStatus(val like: Boolean?, val star: Boolean?)

private suspend fun Context.getLikeStatus()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val post = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    withPermission { checkRead(post) }
    val like = get<Likes>().getLike(loginUser.id, id)
    val star = get<Stars>().getStar(loginUser.id, id)
    call.respond(HttpStatus.OK, LikeStatus(like, star))
}

@Serializable
private data class NewPost(
    val title: String,
    val content: String,
    val anonymous: Boolean,
    val block: BlockId,
    val top: Boolean,
    val state: State,
    val draft: Boolean,
)

private suspend fun Context.newPost()
{
    val newPost = receiveAndCheckBody<NewPost>()
    if (newPost.state == State.DELETED) return call.respond(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)

    val block = get<Blocks>().getBlock(newPost.block) ?: return call.respond(HttpStatus.NotFound)

    withPermission()
    {
        checkPost(block)
        if (newPost.anonymous) checkAnonymous(block)
        if (newPost.top) checkHasAdminIn(block.id)
    }

    if (newPost.title.length >= 256) return call.respond(HttpStatus.BadRequest.subStatus(message = "标题过长"))

    val id = get<Posts>().createPost(
        author = loginUser.id,
        anonymous = newPost.anonymous,
        block = newPost.block,
        top = newPost.top,
        state = newPost.state,
        parent = null
    )!! // 父帖子为null, 不可能出现找不到父帖子的情况
    get<PostVersions>().createPostVersion(
        post = id,
        title = newPost.title,
        content = newPost.content,
        draft = newPost.draft
    )
    call.respond(HttpStatus.OK, id)
}

private suspend fun Context.getPosts()
{
    val loginUser = getLoginUser()
    val author = call.parameters["author"]?.toUserIdOrNull()
    val block = call.parameters["block"]?.toBlockIdOrNull()
    val top = call.parameters["top"]?.lowercase()?.toBooleanStrictOrNull()
    val state = call.parameters["state"]?.toEnumOrNull<State>()
    val type = call.parameters["sort"].toEnumOrNull<Posts.PostListSort>()
               ?: return call.respond(HttpStatus.BadRequest.subStatus("sort参数错误"))
    val tag = call.parameters["tag"]
    val comment = call.parameters["comment"]?.toBooleanStrictOrNull() ?: false

    val (begin, count) = call.getPage()
    val posts = get<Posts>().getPosts(
        loginUser?.toDatabaseUser(),
        if (author == UserId(0)) (loginUser?.id ?: return call.respond(HttpStatus.Unauthorized)) else author,
        block,
        top,
        state,
        tag,
        comment,
        type,
        begin,
        count
    )
    call.respond(HttpStatus.OK, checkAnonymous(posts))
}

private suspend fun Context.setBlockTopPosts()
{
    val pid = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val top = call.parameters["top"]?.toBooleanStrictOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val postInfo = get<Posts>().getPostInfo(pid) ?: return call.respond(HttpStatus.NotFound)
    withPermission {
        checkRead(postInfo)
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
    val loginUser = getLoginUser()
    val post = get<Posts>().getPostInfo(postId) ?: return call.respond(HttpStatus.NotFound)
    val versions =
        if (loginUser.hasGlobalAdmin() || loginUser?.id == post.author)
            get<PostVersions>().getPostVersions(postId, true, begin, count)
        else
            get<PostVersions>().getPostVersions(postId, false, begin, count)
    call.respond(HttpStatus.OK, versions)
}

private suspend fun Context.getVersion()
{
    val versionId = call.parameters["versionId"]?.toPostVersionIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val version = get<PostVersions>().getPostVersion(versionId) ?: return call.respond(HttpStatus.NotFound)
    val post = get<Posts>().getPostInfo(version.post) ?: return call.respond(HttpStatus.NotFound)
    withPermission {
        checkRead(post)
        if (version.draft && post.author != user?.id && !hasGlobalAdmin) return call.respond(HttpStatus.Forbidden)
    }
    call.respond(HttpStatus.OK, version)
}