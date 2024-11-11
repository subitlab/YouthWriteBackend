@file:Suppress("PackageDirectoryMismatch")

package subit.router.files

import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.route
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.utils.io.streams.*
import kotlinx.serialization.Serializable
import subit.dataClasses.*
import subit.dataClasses.Slice.Companion.asSlice
import subit.dataClasses.UserId.Companion.toUserIdOrNull
import subit.database.Notices
import subit.database.Operations
import subit.database.Users
import subit.database.addOperation
import subit.plugin.contentNegotiation.contentNegotiationJson
import subit.router.utils.*
import subit.utils.*
import subit.utils.FileUtils.canDelete
import subit.utils.FileUtils.canGet
import subit.utils.FileUtils.getSpaceInfo
import subit.utils.FileUtils.getUserFiles
import java.io.File
import java.util.*

fun Route.files() = route("files", {
    tags = listOf("文件")
})
{
    route("/{id}", {
        request {
            pathParameter<String>("id")
            {
                required = true
                description = "文件ID"
                example(UUID.randomUUID().toString())
            }
        }
    })
    {
        get("/info", {
            description = "获取文件信息"
            response {
                statuses<FileUtils.FileInfo>(
                    HttpStatus.OK,
                    example = FileUtils.FileInfo("fileName", UserId(0), true, 0, "md5")
                )
                statuses(HttpStatus.NotFound)
            }
        }) { getFileInfo() }

        get("/data", {
            description = "获取文件数据"
            response {
                HttpStatus.OK.message to {
                    body<File>()
                    {
                        mediaTypes(ContentType.Application.OctetStream, ContentType.Image.Any)
                    }
                }
            }
        }) { getFileData() }
    }

    delete("/{id}", {
        description = "删除文件, 除管理员外只能删除自己上传的文件"
        request {
            pathParameter<String>("id")
            {
                required = true
                description = "文件ID"
                example(UUID.randomUUID().toString())
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.NotFound)
            statuses(HttpStatus.Forbidden)
        }
    }) { deleteFile() }

    post("/new", {
        description = "上传文件"
        request {
            multipartBody()
            {
                required = true
                description = "第一部分是文件信息, 第二部分是文件数据"
                mediaTypes(ContentType.MultiPart.FormData)

                part<UploadFile>("info") {}

                part<File>("file")
                {
                    mediaTypes = listOf(ContentType.Application.OctetStream)
                }
            }
            queryParameter<Long>("size")
            {
                required = true
                description = "文件大小"
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.BadRequest)
        }
    }) { uploadFile() }

    get("/list/{id}", {
        description = "获取用户上传的文件的列表, 若不是管理员只能获取目标用户公开的文件"
        request {
            pathParameter<UserId>("id")
            {
                required = true
                description = "用户ID, 为0表示当前登陆的用户"
            }
            paged()
        }
        response {
            statuses<Files>(
                HttpStatus.OK,
                example = Files(FileUtils.SpaceInfo(0L, 0L, 0), sliceOf(UUID.randomUUID().toString()))
            )
        }
    }) { getFileList() }

    post("changePublic", {
        description = "修改文件的公开状态, 只能修改自己上传的文件"
        request {
            body<ChangePublic>
            {
                required = true
                description = "文件信息"
                example("example", ChangePublic(UUID.randomUUID().toString(), true))
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.NotFound)
            statuses(HttpStatus.Forbidden)
        }
    }) { changePublic() }

    post("changePermission", {
        description = "修改其他用户的文件权限"
        request {
            body<ChangePermission>
            {
                required = true
                description = "文件信息"
                example("example", ChangePermission(UserId(0), PermissionLevel.NORMAL))
            }
        }
        response {
            statuses(HttpStatus.OK)
            statuses(HttpStatus.NotFound)
            statuses(HttpStatus.Forbidden)
        }
    }) { changePermission() }
}

private suspend fun Context.getFileInfo0(): Pair<UUID, FileUtils.FileInfo>?
{
    val id = call.parameters["id"].toUUIDOrNull() ?: return call.respond(HttpStatus.BadRequest).let { null }
    val fileInfo = FileUtils.getFileInfo(id) ?: return call.respond(HttpStatus.NotFound).let { null }
    val user = getLoginUser()
    if (!user.canGet(fileInfo)) return call.respond(HttpStatus.Forbidden).let { null }
    return id to fileInfo
}

private suspend fun Context.getFileInfo()
{
    val file = getFileInfo0() ?: return
    call.respond(HttpStatus.OK, file.second)
}

private suspend fun Context.getFileData()
{
    val (id, fileInfo) = getFileInfo0() ?: return
    val file = FileUtils.getFile(id, fileInfo) ?: return call.respond(HttpStatus.NotFound)
    call.response.header("Content-Disposition", "attachment; filename=\"${fileInfo.fileName}\"")
    call.response.header("Content-md5", fileInfo.md5)
    call.respondFile(file)
}

private suspend fun Context.deleteFile()
{
    val id = call.parameters["id"].toUUIDOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val file = FileUtils.getFileInfo(id) ?: return call.respond(HttpStatus.NotFound)
    if (!getLoginUser().canDelete(file)) return call.respond(HttpStatus.Forbidden)
    FileUtils.deleteFile(id)
    call.respond(HttpStatus.OK)
}

@Serializable
private data class UploadFile(
    val fileName: String,
    val public: Boolean,
)

private suspend fun Context.uploadFile()
{
    val user = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val multipart = call.receiveMultipart()
    var fileInfo: UploadFile? = null
    var input: Input? = null
    val size = call.parameters["size"]?.toLongOrNull() ?: return call.respond(HttpStatus.BadRequest)
    multipart.forEachPart { part ->
        val content = when (part)
        {
            is PartData.FileItem -> part.provider().toInputStream().asInput()
            is PartData.FormItem -> part.value.byteInputStream().asInput()
            is PartData.BinaryItem -> part.provider()
            is PartData.BinaryChannelItem -> part.provider().toInputStream().asInput()
        }
        when (part.name)
        {
            "info" -> fileInfo = contentNegotiationJson.decodeFromString(content.readText())
            "file" -> input = content
        }
    }
    if (fileInfo == null || input == null) return call.respond(HttpStatus.BadRequest.subStatus("文件信息未知"))
    if (!user.toDatabaseUser().getSpaceInfo().canUpload(size))
        return call.respond(HttpStatus.NotEnoughSpace)
    if (user.filePermission < PermissionLevel.NORMAL)
        return call.respond(HttpStatus.Forbidden.subStatus("您没有上传文件的权限"))
    FileUtils.saveFile(
        input = input!!.asStream(),
        size = size,
        fileName = fileInfo!!.fileName,
        user = user.id,
        public = fileInfo!!.public
    )
    call.respond(HttpStatus.OK)
}

@Serializable
private data class Files(val info: FileUtils.SpaceInfo, val list: Slice<String>)

private suspend fun Context.getFileList()
{
    val id = call.parameters["id"]?.toUserIdOrNull() ?: return call.respond(HttpStatus.BadRequest)
    val begin = call.parameters["begin"]?.toLongOrNull() ?: 0
    val count = call.parameters["count"]?.toIntOrNull() ?: 10
    val user = getLoginUser()
    if (user != null && (user.id == id || id == UserId(0) || user.permission >= PermissionLevel.ADMIN))
    {
        val files = user.id.getUserFiles().map { it.first.toString() }
        val info = user.toDatabaseUser().getSpaceInfo()
        return call.respond(HttpStatus.OK, Files(info, files.asSlice(begin, count)))
    }
    val file = id.getUserFiles().filter { user.canGet(it.second) }.map { it.first.toString() }
    val info = SSO.getDbUser(id)?.getSpaceInfo() ?: return call.respond(HttpStatus.NotFound)
    call.respond(HttpStatus.OK, Files(info, file.asSlice(begin, count)))
}

@Serializable
private data class ChangePublic(val id: String, val public: Boolean)

private suspend fun Context.changePublic()
{
    val (id, public) = call.receiveAndCheckBody<ChangePublic>().let {
        val id = it.id.toUUIDOrNull() ?: return@let null
        val public = it.public
        id to public
    } ?: return call.respond(HttpStatus.BadRequest)
    val file = FileUtils.getFileInfo(id) ?: return call.respond(HttpStatus.NotFound)

    if (file.user != getLoginUser()?.id) return call.respond(HttpStatus.Forbidden)
    FileUtils.changeInfo(id, file.copy(public = public))
    call.respond(HttpStatus.OK)
}

@Serializable
private data class ChangePermission(val id: UserId, val filePermission: PermissionLevel)

private suspend fun Context.changePermission()
{
    val loginUser = getLoginUser() ?: return call.respond(HttpStatus.Unauthorized)
    val changePermission = call.receiveAndCheckBody<ChangePermission>()
    val user = SSO.getDbUser(changePermission.id) ?: return call.respond(HttpStatus.NotFound)
    checkPermission { checkChangeFilePermission(user, changePermission.filePermission) }
    get<Users>().changeFilePermission(changePermission.id, changePermission.filePermission)
    get<Operations>().addOperation(loginUser.id, changePermission)
    get<Notices>().createNotice(
        Notice.SystemNotice(
            user = changePermission.id,
            content = "您的文件权限被修改为${changePermission.filePermission}"
        )
    )
    call.respond(HttpStatus.OK)
}