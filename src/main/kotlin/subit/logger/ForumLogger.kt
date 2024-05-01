package subit.logger

import subit.Loader
import subit.config.loggerConfig
import subit.console.AnsiStyle.Companion.RESET
import subit.console.Console
import subit.console.SimpleAnsiColor
import subit.console.SimpleAnsiColor.Companion.PURPLE
import subit.logger.ForumLogger.nativeOut
import subit.logger.ForumLogger.safe
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * logger系统
 */
object ForumLogger: LoggerUtils(Logger.getLogger(""))
{
    internal val nativeOut: PrintStream = System.out
    internal val nativeErr: PrintStream = System.err

    /**
     * logger中的日期格式
     */
    val loggerDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    /**
     * 日志输出流
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val out: PrintStream = PrintStream(LoggerOutputStream(Level.INFO))

    /**
     * 日志错误流
     */
    val err: PrintStream = PrintStream(LoggerOutputStream(Level.SEVERE))
    fun addFilter(pattern: String)
    {
        loggerConfig = loggerConfig.copy(matchers = loggerConfig.matchers+pattern)
    }

    fun removeFilter(pattern: String)
    {
        loggerConfig = loggerConfig.copy(matchers = loggerConfig.matchers.filter { it != pattern })
    }

    fun setWhiteList(whiteList: Boolean)
    {
        loggerConfig = loggerConfig.copy(whiteList = whiteList)
    }

    fun setLevel(level: Level)
    {
        loggerConfig = loggerConfig.copy(level = level)
    }

    /**
     * 获取过滤器
     */
    fun filters(): MutableList<String> = Collections.unmodifiableList(loggerConfig.matchers)

    /**
     * 若由于终端相关组件错误导致的异常, 异常可能最终被捕获并打印在终端上, 可能导致再次抛出错误, 最终引起[StackOverflowError].
     * 未避免此类问题, 在涉及需要打印内容的地方, 应使用此方法.
     * 此当[block]出现错误时, 将绕过终端相关组件, 直接打印在标准输出流上. 以避免[StackOverflowError]的发生.
     */
    internal inline fun safe(block: ()->Unit)
    {
        runCatching(block).onFailure()
        {
            it.printStackTrace(nativeErr)
        }
    }

    /**
     * 初始化logger，将设置终端支持显示颜色码，捕获stdout，应在启动springboot前调用
     */
    init
    {
        System.setOut(out)
        System.setErr(err)
        ForumLogger.logger.setUseParentHandlers(false)
        ForumLogger.logger.handlers.forEach { ForumLogger.logger.removeHandler(it) }
        ForumLogger.logger.addHandler(ToConsoleHandler)
        ForumLogger.logger.addHandler(ToFileHandler)
        Loader.getResource("/logo/SubIT-logo.txt")?.copyTo(out) ?: warning("logo not found")
    }

    private class LoggerOutputStream(private val level: Level): OutputStream()
    {
        val arrayOutputStream = ByteArrayOutputStream()
        override fun write(b: Int) = safe()
        {
            if (b == '\n'.code)
            {
                val str: String
                synchronized(arrayOutputStream)
                {
                    str = arrayOutputStream.toString()
                    arrayOutputStream.reset()
                }
                ForumLogger.logger.log(level, str)
            }
            else synchronized(arrayOutputStream) { arrayOutputStream.write(b) }
        }
    }
}

/**
 * 向终端中打印log
 */
object ToConsoleHandler: Handler()
{
    override fun publish(record: LogRecord) = safe()
    {
        if (!loggerConfig.check(record)) return

        val messages = mutableListOf(record.message)

        if (record.thrown != null)
        {
            val str = record.thrown.stackTraceToString()
            str.split("\n").forEach { messages.add(it) }
        }

        if (record.sourceClassName.startsWith("org.jline"))
        {
            val head = String.format(
                "[%s][%s]",
                ForumLogger.loggerDateFormat.format(record.millis),
                record.level.name
            )
            messages.forEach { message -> nativeOut.println("$head $message") }
            return
        }

        val level = record.level
        val ansiStyle = if (level.intValue() >= Level.SEVERE.intValue()) SimpleAnsiColor.RED.bright()
        else if (level.intValue() >= Level.WARNING.intValue()) SimpleAnsiColor.YELLOW.bright()
        else if (level.intValue() >= Level.CONFIG.intValue()) SimpleAnsiColor.BLUE.bright()
        else SimpleAnsiColor.GREEN.bright()

        val head = String.format(
            "%s[%s]%s[%s]%s",
            PURPLE.bright(),
            ForumLogger.loggerDateFormat.format(record.millis),
            ansiStyle,
            level.name,
            RESET,
        )

        messages.forEach { message ->
            Console.println("$head $message $RESET")
        }
    }

    override fun flush() = Unit
    override fun close() = Unit
}

/**
 * 将log写入文件
 */
object ToFileHandler: Handler()
{
    /**
     * log文件的日期格式
     */
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")

    /**
     * log文件的目录
     */
    private val logDir = File("logs")

    /**
     * log文件
     */
    private val logFile = File(logDir, "latest.log")

    /**
     * 当前log文件的行数
     */
    private var cnt = 0

    init
    {
        new()
    }

    /**
     * 创建新的log文件
     */
    private fun new()
    {
        if (!logDir.exists()) logDir.mkdirs()
        if (logFile.exists()) // 如果文件已存在，则压缩到zip
        { // 将已有的log压缩到zip
            val zipFile = File(logDir, "${fileDateFormat.format(System.currentTimeMillis())}.zip")
            zipFile(zipFile)
            logFile.delete()
        }
        logFile.createNewFile() // 创建新的log文件
        cnt = 0 // 重置行数
    }

    /**
     * 将log文件压缩到zip
     */
    private fun zipFile(zipFile: File)
    {
        if (!logFile.exists()) return
        if (!zipFile.exists()) zipFile.createNewFile()
        val fos = FileOutputStream(zipFile)
        val zipOut = ZipOutputStream(fos)
        val fis = FileInputStream(logFile)
        val zipEntry = ZipEntry(logFile.getName())
        zipOut.putNextEntry(zipEntry)
        val bytes = ByteArray(1024)
        var length: Int
        while (fis.read(bytes).also { length = it } >= 0) zipOut.write(bytes, 0, length)
        zipOut.close()
        fis.close()
        fos.close()
    }

    private fun check()
    {
        if ((cnt ushr 10) > 0) new()
    }

    private fun append(lines: List<String>) = synchronized(this)
    {
        if (!logFile.exists()) new()
        val writer = FileWriter(logFile, true)
        lines.forEach { writer.appendLine(it) }
        writer.close()
        check()
    }

    private val colorMatcher = Regex("\u001B\\[[;\\d]*m")
    override fun publish(record: LogRecord) = safe()
    {
        if (!loggerConfig.check(record)) return

        val messages = mutableListOf(record.message)

        if (record.thrown != null)
        {
            val str = record.thrown.stackTraceToString()
            str.split("\n").forEach { messages.add(it) }
        }

        val messagesWithOutColor = messages.map { colorMatcher.replace(it, "") }

        val head = String.format(
            "[%s][%s]",
            ForumLogger.loggerDateFormat.format(record.millis),
            record.level.name
        )

        append(messagesWithOutColor.map { "$head $it" })
    }

    override fun flush() = Unit
    override fun close() = Unit
}