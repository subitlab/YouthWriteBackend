package subit.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import subit.config.filesConfig
import subit.dataClasses.DatabaseUser
import subit.dataClasses.PermissionLevel
import subit.dataClasses.UserFull
import subit.dataClasses.UserId
import subit.dataDir
import subit.plugin.contentNegotiation.dataJson
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.*
import kotlin.math.min

/**
 * 文件工具类
 * 文件存储结构
 * file/
 *   home/
 *     home.png         #首页图片
 *     home.md          #首页配文
 *     announcement.md  #公告
 *   index/
 *     ${id}.index #文件的info
 *   raw/
 *     ${user}/
 *       user.index
 *       ${id}.file
 *
 * 当通过id查找文件时, 先在通过 "index/id.index" 查找到文件信息, 然后通过 "raw/${user}/${id}.file" 获取文件内容
 */
object FileUtils
{
    val fileFolder = File(dataDir, "files")
    private val indexFolder = File(fileFolder, "index")
    private val rawFolder = File(fileFolder, "raw")
    fun init()
    {
        dataDir.mkdirs()
        fileFolder.mkdirs()
        indexFolder.mkdirs()
        rawFolder.mkdirs()
        HomeFilesUtils.init()
    }

    @Serializable
    data class FileInfo(
        val fileName: String,
        val user: UserId,
        val public: Boolean,
        val size: Long,
        val md5: String
    )
    {
        val private: Boolean get() = !public
    }

    fun UserFull?.canGet(file: FileInfo) =
        file.public || this?.id == file.user || (this != null && this.filePermission >= PermissionLevel.ADMIN)

    fun UserFull?.canDelete(file: FileInfo) =
        this != null && (this.id == file.user || this.filePermission >= PermissionLevel.ADMIN)

    private fun getFileInfo(file: File): FileInfo? = runCatching {
        dataJson.decodeFromString<FileInfo>(file.readText())
    }.getOrNull()

    private fun getRandomId(): UUID
    {
        var id = UUID.randomUUID()
        while (File(indexFolder, "${id}.index").exists()) id = UUID.randomUUID()
        return id
    }

    private suspend fun getFileMd5(file: File): String
    {
        val md = MessageDigest.getInstance("MD5") // 获取MD5实例
        val buffer = ByteArray(8192) // 缓冲区
        withContext(Dispatchers.IO) // 使用IO线程
        {
            FileInputStream(file).use()
            { inputStream ->
                var read: Int
                while (inputStream.read(buffer).also { read = it } > 0) md.update(buffer, 0, read)
            }
        }
        val byteArray = md.digest() // 获取MD5值
        return byteArray.joinToString("") { "%02x".format(it) } // 转为16进制字符串
    }

    /**
     * 保存一个文件
     * @param input 文件输入流
     * @param fileName 文件名
     * @param user 所属用户
     * @param public 是否公开
     * @return 文件id
     */
    suspend fun saveFile(input: InputStream, size: Long, fileName: String, user: UserId, public: Boolean): UUID
    {
        val id = getRandomId()
        val userFile = File(rawFolder, user.value.toString(16))
        userFile.mkdirs()
        val rawFile = File(userFile, "${id}.file")
        val indexFile = File(indexFolder, "${id}.index")
        withContext(Dispatchers.IO) { rawFile.createNewFile() }

        // 复制前size字节到rawFile
        withContext(Dispatchers.IO)
        {
            val out = rawFile.outputStream()
            var bytesCopied: Long = 0
            val buffer = ByteArray(8192)
            var bytes = input.read(buffer, 0, min(buffer.size.toLong(), size).toInt())
            while (bytes >= 0)
            {
                out.write(buffer, 0, bytes)
                bytesCopied += bytes
                if (bytesCopied >= size) break
                bytes = input.read(buffer, 0, min(buffer.size.toLong(), size - bytesCopied).toInt())
            }
        }

        val md5 = getFileMd5(rawFile)
        val fileInfo = FileInfo(fileName, user, public, rawFile.length(), md5)
        dataJson.encodeToString(FileInfo.serializer(), fileInfo).let(indexFile::writeText)
        return id
    }

    /**
     * 获取一个文件的信息
     * @param id 文件id
     */
    fun getFileInfo(id: UUID): FileInfo?
    {
        val indexFile = File(indexFolder, "${id}.index")
        return if (indexFile.exists()) getFileInfo(indexFile)
        else null
    }

    suspend fun UserId.getUserFiles(): Sequence<Pair<UUID, FileInfo>> = withContext(Dispatchers.IO)
    {
        val userFolder = File(rawFolder, this@getUserFiles.value.toString(16))
        if (!userFolder.exists()) return@withContext emptySequence()
        userFolder.walk()
            .filter { it.isFile }
            .mapNotNull { it.nameWithoutExtension.toUUIDOrNull() }
            .mapNotNull { id -> getFileInfo(id)?.let(id::to) }
    }

    fun getFile(id: UUID, info: FileInfo): File?
    {
        val userFolder = File(rawFolder, info.user.value.toString(16))
        val rawFile = File(userFolder, "${id}.file")
        return if (rawFile.exists()) rawFile else null
    }

    @Serializable
    data class SpaceInfo(val max: Long, val used: Long, val fileCount: Int)
    {
        fun canUpload(size: Long) = max-used >= size
    }

    /**
     * 获取使用空间与剩余空间
     */
    suspend fun DatabaseUser.getSpaceInfo(): SpaceInfo = withContext(Dispatchers.IO)
    {
        val userFolder = File(rawFolder, this@getSpaceInfo.id.value.toString(16))
        val max = if (this@getSpaceInfo.filePermission >= PermissionLevel.ADMIN) filesConfig.adminMaxFileSize
        else filesConfig.userMaxFileSize
        val (used, count) = userFolder.walk().filter(File::isFile).fold(0L to 0)
        { (size, count), file ->
            size+file.length() to count+1
        }
        SpaceInfo(max, used, count)
    }

    /**
     * 删除文件, 仅删除raw文件索引不删除
     */
    fun deleteFile(id: UUID)
    {
        val info = getFileInfo(id) ?: return
        deleteFile(id, info)
    }

    private fun deleteFile(id: UUID, info: FileInfo) = deleteFile(id, info.user)
    private fun deleteFile(id: UUID, user: UserId)
    {
        File(rawFolder, "$user.$id.file").delete()
    }

    fun changeInfo(id: UUID, info: FileInfo)
    {
        val indexFile = File(indexFolder, "${id}.index")
        indexFile.writeText(dataJson.encodeToString(FileInfo.serializer(), info))
    }
}

object HomeFilesUtils
{
    private val homeFolder = File(FileUtils.fileFolder, "home")
    private val homeMdFile = File(homeFolder, "home.md")
    private val announcementMdFile = File(homeFolder, "announcement.md")
    private val homePngFile = File(homeFolder, "home.png")
    fun init()
    {
        homeFolder.mkdirs()
    }

    var homeMd: String
        get() = if (homeMdFile.exists()) homeMdFile.readText() else ""
        set(value) = homeMdFile.writeText(value)

    var announcementMd: String
        get() = if (announcementMdFile.exists()) announcementMdFile.readText() else ""
        set(value) = announcementMdFile.writeText(value)

    var homeImage: InputStream?
        get() =
            if (homePngFile.exists()) homePngFile.inputStream()
            else null
        set(value)
        {
            if (value != null) homePngFile.outputStream().use { value.copyTo(it) }
            else homePngFile.delete()
        }
}