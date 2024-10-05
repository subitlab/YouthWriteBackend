package subit

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import net.mamoe.yamlkt.Yaml
import subit.console.command.CommandSet.startCommandThread
import subit.database.loadDatabaseImpl
import subit.logger.YouthWriteLogger
import subit.plugin.apiDoc.installApiDoc
import subit.plugin.authentication.installAuthentication
import subit.plugin.autoHead.installAutoHead
import subit.plugin.contentNegotiation.installContentNegotiation
import subit.plugin.cors.installCORS
import subit.plugin.doubleReceive.installDoubleReceive
import subit.plugin.koin.installKoin
import subit.plugin.rateLimit.installRateLimit
import subit.plugin.statusPages.installStatusPages
import subit.plugin.webSockets.installWebSockets
import subit.router.router
import subit.utils.FileUtils
import subit.utils.Power
import java.io.File
import kotlin.properties.Delegates

lateinit var version: String
    private set
lateinit var workDir: File
    private set
lateinit var dataDir: File
    private set
var debug by Delegates.notNull<Boolean>()
    private set

/**
 * 解析命令行, 返回的是处理后的命令行, 和从命令行中读取的配置文件(默认是config.yaml, 可通过-config=xxxx.yaml更改)
 */
private fun parseCommandLineArgs(args: Array<String>): Pair<Array<String>, File>
{
    val argsMap = args.mapNotNull {
        when (val idx = it.indexOf("="))
        {
            -1 -> null
            else -> Pair(it.take(idx), it.drop(idx + 1))
        }
    }.toMap()

    // 从命令行中加载信息

    // 工作目录
    workDir = File(argsMap["-workDir"] ?: ".")
    workDir.mkdirs()

    // 配置文件目录
    dataDir = argsMap["-dataDir"]?.let { File(it) } ?: File(workDir, "data")
    dataDir.mkdirs()

    // 是否开启debug模式
    debug = argsMap["-debug"]?.toBoolean() ?: false
    System.setProperty("io.ktor.development", "$debug")

    // 去除命令行中的-config参数, 因为ktor会解析此参数进而不加载打包的application.yaml
    // 其余参数还原为字符串数组
    val resArgs = argsMap.entries
        .filterNot { it.key == "-config" || it.key == "-workDir" || it.key == "-debug" || it.key == "-dataDir" }
        .map { (k, v) -> "$k=$v" }
        .toTypedArray()
    // 命令行中输入的自定义配置文件
    val configFile = argsMap["-config"]?.let { File(it) } ?: File(workDir, "config.yaml")

    return resArgs to configFile
}

fun main(args: Array<String>)
{
    // 处理命令行应在最前面, 因为需要来解析workDir, 否则后面的程序无法正常运行
    val (args1, configFile) = runCatching { parseCommandLineArgs(args) }.getOrElse { return }

    // 初始化配置文件加载器, 会加载所有配置文件
    subit.config.ConfigLoader.init()

    // 检查主配置文件是否存在, 不存在则创建默认配置文件, 并结束程序
    if (!configFile.exists())
    {
        configFile.createNewFile()
        Loader
            .getResource("default_config.yaml")
            ?.readAllBytes()
            ?.let(configFile::writeBytes)
            ?: error("default_config.yaml not found")
        YouthWriteLogger.getLogger().severe(
            "config.yaml not found, the default config has been created, " +
            "please modify it and restart the program"
        )
        return
    }

    val defaultConfig = Loader.getResource("application.yaml") ?: error("application.yaml not found")
    val customConfig = configFile.inputStream()

    val resConfig = Loader.mergeConfigs(defaultConfig, customConfig)
    // 创建一个临时文件, 用于存储合并后的配置文件
    val tempFile = File.createTempFile("resConfig", ".yaml")
    tempFile.writeText(Yaml.encodeToString(resConfig))

    val resArgs = args1 + "-config=${tempFile.absolutePath}"

    // 生成环境
    val environment = commandLineEnvironment(args = resArgs)
    // 启动服务器
    embeddedServer(Netty, environment).start(wait = true)
    // 若服务器关闭则终止整个程序
    Power.shutdown(0)
}

/**
 * 应用程序入口
 */
@Suppress("unused")
fun Application.init()
{
    version = environment.config.property("version").getString()

    Loader.getResource("logo/SubIT-logo.txt")
        ?.bufferedReader()
        ?.use { it.readText().split("\n").forEach(YouthWriteLogger.globalLogger::info) }
    ?: YouthWriteLogger.globalLogger.warning("SubIT-logo.txt not found")

    startCommandThread()

    FileUtils.init() // 初始化文件系统

    installApiDoc()
    installAuthentication()
    installAutoHead()
    installContentNegotiation()
    installCORS()
    installDoubleReceive()
    installKoin()
    installRateLimit()
    installStatusPages()
    installWebSockets()

    loadDatabaseImpl()

    router()
}