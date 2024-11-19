@file:Suppress("PackageDirectoryMismatch")

package subit.router.posts

import io.github.smiley4.ktorswaggerui.dsl.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import subit.dataClasses.*
import subit.dataClasses.BlockId.Companion.toBlockIdOrNull
import subit.dataClasses.PostId.Companion.toPostIdOrNull
import subit.dataClasses.PostVersionId.Companion.toPostVersionIdOrNull
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.*
import subit.plugin.rateLimit.RateLimit
import subit.router.utils.*
import subit.utils.*

fun Route.posts() = route("/post", {
    tags = listOf("帖子")
})
{

    rateLimit(RateLimit.Post.rateLimitName)
    {
        post("/new", {
            description = "新建帖子, 新建评论见/comment/{postId}, 设置置顶需要板块管理员"
            request {
                body<NewPost>
                {
                    required = true
                    description = "发帖, 成功返回帖子ID. state为帖子状态, 不允许为DELETE, PRIVATE为预留"
                    example(
                        "example",
                        NewPost("标题", PostVersionInfo.example.content, false, BlockId(0), false, State.NORMAL, false)
                    )
                }
            }
            response {
                statuses<PostId>(HttpStatus.OK, example = PostId(0))
                statuses(HttpStatus.BadRequest, HttpStatus.TooManyRequests)
            }
        }) { newPost() }
    }

    get("/list", {
        description = "获取帖子列表, 不登录也可以获取, 但是登录/有相应权限的人可能会看到更多内容"
        request {
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
                description = """
                    - true -> 只返回评论
                    - false -> 只返回帖子
                    - 不填 -> 返回所有
                """.trimIndent()
            }
            queryParameter<Boolean>("draft")
            {
                required = false
                description = """
                    - true -> 返回所有只有草稿版本的帖子
                    - false -> 返回所有有发布版本的帖子
                    - 不填 -> 返回所有帖子
                """.trimIndent()
            }
            queryParameter<Long>("createBefore")
            {
                required = false
                description = "创建时间在此时间之前, 若此项不为空, 则draft项无效且被视为false"
            }
            queryParameter<Long>("createAfter")
            {
                required = false
                description = "创建时间在此时间之后, 若此项不为空, 则draft项无效且被视为false"
            }
            queryParameter<Long>("lastModifiedBefore")
            {
                required = false
                description = "最后修改时间在此时间之前, 若此项不为空, 则draft项无效且被视为false"
            }
            queryParameter<Long>("lastModifiedAfter")
            {
                required = false
                description = "最后修改时间在此时间之后, 若此项不为空, 则draft项无效且被视为false"
            }
            queryParameter<String>("containsKeyWord")
            {
                required = false
                description = "包含关键词"
            }
            queryParameter<Posts.PostListSort>("sort")
            {
                required = true
                description = "排序方式"
            }
            paged()
        }
        response {
            statuses<Slice<PostFullBasicInfo>>(HttpStatus.OK, example = sliceOf(PostFullBasicInfo.example))
        }
    }) { getPosts() }

    id()
    version()
}

private fun Route.id() = route("/{id}", {
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
                    example("example", EditPost("标题", PostVersionInfo.example.content, false, PostVersionId(0)))
                }
            }
            response {
                statuses(HttpStatus.OK)
                statuses(HttpStatus.BadRequest, HttpStatus.TooManyRequests)
            }
        }) { editPost() }
    }

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
        description = "设置帖子是否在板块中置顶, 该接口不适用于评论, 需要板块管理员"
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

    get("/latestVersion", {
        description = "获取帖子的最新版本"
        request {
            queryParameter<Boolean>("containsDraft")
            {
                required = false
                description = "是否包含草稿版本, 若当前用户无权限查看草稿版本则该参数无效且被视为false. 默认为false"
            }
            queryParameter<Boolean>("forEdit")
            {
                required = false
                description =
                    "是否为编辑编辑帖子获取, 若为true则containsDraft无效且被视为true, 将返回标号后的内容. 默认为false"
            }
        }
        response {
            statuses<PostVersionInfo>(HttpStatus.OK, example = PostVersionInfo.example)
            statuses(HttpStatus.NotFound.subStatus("未找到帖子版本"))
            statuses(HttpStatus.BadRequest.subStatus("未找到帖子"))
        }
    }) { getLatestVersion() }

    route("/like")
    {
        post("", {
            description = "点赞/取消点赞/收藏/取消收藏 帖子"
            request {
                body<LikePost>
                {
                    required = true
                    description = "点赞/取消点赞/收藏/取消收藏"
                    example("example", LikePost(LikeType.LIKE))
                }
            }
            response {
                statuses(HttpStatus.OK)
            }
        }) { likePost() }

        get("", {
            description = "获取帖子的点赞/收藏状态"
            response {
                statuses<LikeStatus>(HttpStatus.OK, example = LikeStatus(like = true, star = false))
            }
        }) { getLikeStatus() }

        get("/list", {
            description =
                "获取帖子的点赞/收藏列表, 若用户设置了不显示自己的点赞/收藏且当前用户不是全局管理员, 则user为null"
            request {
                paged()
                queryParameter<Boolean>("star")
                {
                    required = true
                    description = "若为true则返回收藏列表, 若为false返回点赞列表"
                }
            }
            response {
                statuses<Slice<LikeListResponse>>(HttpStatus.OK, example = LikeListResponse.examples)
            }
        }) { getLikeList() }
    }
}

private fun Route.version() = route("/version", {
    response {
        statuses(HttpStatus.NotFound)
    }
})
{
    get("/list/{postId}", {
        description =
            "获取帖子的版本列表, 如果当前登录用户是作者或者全局管理员的话可以获得包含草稿版本在内的所有版本, 否则只能获得非草稿版本"
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
        description =
            "获取帖子版本信息, 需要当前登录的用户可以看到该版本所属的帖子, 并且草稿版本只能由作者或全局管理员查看"
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
    checkPermission { checkRead(postFull.toPostInfo()) }
    val wordMarkings = postFull.lastVersionId?.let { get<WordMarkings>().getWordMarkings(it) }
    val resContent = postFull.content?.let { withWordMarkings(it, wordMarkings!!) }
    call.respond(HttpStatus.OK, checkAnonymous(postFull.copy(content = resContent)))
}

@Serializable
data class EditPost(val title: String, val content: JsonElement, val draft: Boolean, val oldVersionId: PostVersionId)

val editPostLock = Locks<PostId>()
private suspend fun Context.editPost() = editPostLock.tryWithLock(
    call.parameters["id"]?.toPostIdOrNull() ?: finishCall(HttpStatus.BadRequest),
    { finishCall(HttpStatus.TooManyRequests) }
)
{ id ->
    val operators = call.receiveAndCheckBody<EditPost>()
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)

    val postVersions = get<PostVersions>()
    val postInfo = get<Posts>().getPostFullBasicInfo(id) ?: return call.respond(HttpStatus.NotFound)
    val oldVersionId = postVersions.getLatestPostVersion(id, true)
    if (postInfo.author != loginUser.id) call.respond(HttpStatus.Forbidden.subStatus(message = "文章仅允许作者编辑"))
    if (operators.oldVersionId != oldVersionId) return call.respond(HttpStatus.NotLatestVersion)
    if (operators.title.length >= 256) return call.respond(HttpStatus.BadRequest.subStatus(message = "标题过长"))

    val newVersionId = postVersions.createPostVersion(
        post = id,
        title = operators.title,
        content = if (operators.draft) operators.content else clearAndMerge(operators.content),
        draft = operators.draft
    )

    if (operators.draft) finishCall(HttpStatus.OK)

    val wordMarkings = get<WordMarkings>()
    val oldVersionInfo =
        postVersions.getPostVersion(oldVersionId) ?: return@tryWithLock call.respond(HttpStatus.NotFound)
    val markings = wordMarkings.getWordMarkings(oldVersionId)
    val newMarkings =
        mapWordMarkings(oldVersionInfo.content, operators.content, markings).map { it.copy(postVersion = newVersionId) }

    wordMarkings.batchAddWordMarking(newMarkings)
    finishCall(HttpStatus.OK)
}

private suspend fun Context.changeState()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val state = call.parameters["state"].decodeOrElse<State> { return call.respond(HttpStatus.BadRequest) }
    val post = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    checkPermission { checkChangeState(post, state) }

    if (post.state == state) return call.respond(HttpStatus.OK)

    get<Posts>().setPostState(id, state)
    if (post.author != loginUser.id) get<Notices>().createNotice(
        Notice.SystemNotice(
            user = post.author,
            content = "您的帖子 ${post.id} 已被管理员修改状态为 $state",
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
    val post = get<Posts>().getPostFullBasicInfo(id) ?: return call.respond(HttpStatus.NotFound)
    val type = call.receiveAndCheckBody<LikePost>().type
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    checkPermission { checkRead(post.toPostInfo()) }
    when (type)
    {
        LikeType.LIKE -> get<Likes>().addLike(loginUser.id, id)
        LikeType.UNLIKE -> get<Likes>().removeLike(loginUser.id, id)
        LikeType.STAR -> get<Stars>().addStar(loginUser.id, id)
        LikeType.UNSTAR -> get<Stars>().removeStar(loginUser.id, id)
    }
    if (loginUser.id != post.author && (type == LikeType.LIKE || type == LikeType.STAR))
    {
        val notice =
            Notice.PostNotice.build(
                type = if (type == LikeType.LIKE) Notice.Type.LIKE else Notice.Type.STAR,
                user = post.author,
                post = post.id,
                operator = loginUser,
            )
        get<Notices>().createNotice(notice)
    }
    call.respond(HttpStatus.OK)
}

@Serializable
private data class LikeListResponse(val user: BasicUserInfo?, val time: Long)
{
    companion object
    {
        val examples = sliceOf(
            LikeListResponse(BasicUserInfo.example, System.currentTimeMillis()),
            LikeListResponse(null, System.currentTimeMillis())
        )
    }
}

private suspend fun Context.getLikeList()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val star = call.parameters["star"]?.toBooleanStrictOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val (begin, count) = call.getPage()
    val post = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkRead(post) }
    val list =
        if (star) get<Stars>().getStars(post = id, begin = begin, limit = count).map { it.user to it.time }
        else get<Likes>().getLikes(post = id, begin = begin, limit = count).map { it.user to it.time }

    val res = checkPermission {
        list.map {
            val (sso, dbUser) = SSO.getUserAndDbUser(it.first) ?: return@map LikeListResponse(null, it.second)
            if (!hasGlobalAdmin && !dbUser.showStars) return@map LikeListResponse(null, it.second)
            LikeListResponse(BasicUserInfo.from(sso, dbUser), it.second)
        }
    }
    call.respond(HttpStatus.OK, res)
}

@Serializable
data class LikeStatus(val like: Boolean?, val star: Boolean?)

private suspend fun Context.getLikeStatus()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val post = get<Posts>().getPostInfo(id) ?: return call.respond(HttpStatus.NotFound)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    checkPermission { checkRead(post) }
    val like = get<Likes>().getLike(loginUser.id, id)
    val star = get<Stars>().getStar(loginUser.id, id)
    call.respond(HttpStatus.OK, LikeStatus(like, star))
}

@Serializable
private data class NewPost(
    val title: String,
    val content: JsonElement,
    val anonymous: Boolean,
    val block: BlockId,
    val top: Boolean,
    val state: State,
    val draft: Boolean,
)

private suspend fun Context.newPost()
{
    val newPost = call.receiveAndCheckBody<NewPost>()
    if (newPost.state == State.DELETED) return call.respond(HttpStatus.BadRequest)
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)

    val block = get<Blocks>().getBlock(newPost.block) ?: return call.respond(HttpStatus.NotFound)

    checkPermission()
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
        content = if (newPost.draft) newPost.content else clearAndMerge(newPost.content),
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
    val state = call.parameters["state"].decodeOrNull<State>()
    val tag = call.parameters["tag"]
    val comment = call.parameters["comment"]?.toBooleanStrictOrNull()
    val draft = call.parameters["draft"]?.toBooleanStrictOrNull()
    val createBefore = call.parameters["createBefore"]?.toLongOrNull()
    val createAfter = call.parameters["createAfter"]?.toLongOrNull()
    val lastModifiedBefore = call.parameters["lastModifiedBefore"]?.toLongOrNull()
    val lastModifiedAfter = call.parameters["lastModifiedAfter"]?.toLongOrNull()
    val containsKeyWord = call.parameters["containsKeyWord"]
    val sort = call.parameters["sort"].decodeOrElse<Posts.PostListSort> { return call.respond(HttpStatus.BadRequest.subStatus("sort参数错误")) }
    val (begin, count) = call.getPage()

    val posts = get<Posts>().getPosts(
        loginUser = loginUser,
        author = if (author == UserId(0)) loginUser?.id else author,
        block = block,
        top = top,
        state = state,
        tag = tag,
        comment = comment,
        draft = draft,
        createBefore = createBefore?.toInstant(),
        createAfter = createAfter?.toInstant(),
        lastModifiedBefore = lastModifiedBefore?.toInstant(),
        lastModifiedAfter = lastModifiedAfter?.toInstant(),
        containsKeyWord = containsKeyWord,
        sortBy = sort,
        begin = begin,
        limit = count,
    )
    call.respond(HttpStatus.OK, checkAnonymous(posts))
}

private suspend fun Context.setBlockTopPosts()
{
    val pid = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val top = call.parameters["top"]?.toBooleanStrictOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val postInfo = get<Posts>().getPostInfo(pid) ?: return call.respond(HttpStatus.NotFound)
    if (postInfo.parent != null) return call.respond(HttpStatus.BadRequest.subStatus("评论不允许置顶"))
    checkPermission {
        checkRead(postInfo)
        checkHasAdminIn(postInfo.block)
    }
    if (!get<Posts>().setTop(pid, top = top)) return call.respond(HttpStatus.NotFound)
    call.respond(HttpStatus.OK)
}

private suspend fun Context.getLatestVersion()
{
    val id = call.parameters["id"]?.toPostIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val containsDraft = call.parameters["containsDraft"]?.toBooleanStrictOrNull() ?: false
    val forEdit = call.parameters["forEdit"]?.toBooleanStrictOrNull() ?: false
    val versions = get<PostVersions>()
    val version = versions.getLatestPostVersion(id, forEdit || containsDraft)?.let { versions.getPostVersion(it) }
                  ?: return call.respond(HttpStatus.NotFound.subStatus("未找到帖子版本"))
    checkPermission { checkEdit(version.toPostVersionBasicInfo()) }
    if (forEdit) finishCall(HttpStatus.OK, version.copy(content = splitContentNode(version.content)))
    call.respond(HttpStatus.OK, version)
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
    checkPermission {
        checkRead(post)
        if (version.draft && post.author != dbUser?.id && !hasGlobalAdmin) return call.respond(HttpStatus.Forbidden)
    }
    call.respond(HttpStatus.OK, version)
}