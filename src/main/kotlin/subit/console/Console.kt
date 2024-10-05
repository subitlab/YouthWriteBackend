package subit.console

import kotlinx.coroutines.runBlocking
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.widget.AutopairWidgets
import org.jline.widget.AutosuggestionWidgets
import subit.console.command.CommandSet
import subit.dataDir
import subit.logger.YouthWriteLogger.nativeOut
import subit.utils.Power
import sun.misc.Signal
import java.io.File

/**
 * 终端相关
 */
object Console
{
    /**
     * 终端对象
     */
    private val terminal: Terminal?

    /**
     * 颜色显示模式
     */
    var ansiColorMode: ColorDisplayMode = ColorDisplayMode.RGB

    /**
     * 效果显示模式
     */
    var ansiEffectMode: EffectDisplayMode = EffectDisplayMode.ON

    /**
     * 命令行读取器,命令补全为[CommandSet.CommandCompleter],命令历史保存在[historyFile]中
     */
    val lineReader: LineReader?

    init
    {
        Signal.handle(Signal("INT")) { onUserInterrupt(CommandSet.ConsoleCommandSender) }
        var terminal: Terminal? = null
        var lineReader: LineReader? = null
        try
        {
            terminal = TerminalBuilder.builder().jansi(true).build()
            if (terminal.type == "dumb")
            {
                terminal.close()
                terminal = null
                throw IllegalStateException("Unsupported terminal type: dumb")
            }
            lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(CommandSet.CommandCompleter)
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build()

            // 自动配对(小括号/中括号/大括号/引号等)
            val autopairWidgets = AutopairWidgets(lineReader, true)
            autopairWidgets.enable()
            // 根据历史记录建议
            val autosuggestionWidgets = AutosuggestionWidgets(lineReader)
            autosuggestionWidgets.enable()
        }
        catch (e: Throwable)
        {
            terminal?.close()
            println("Failed to initialize terminal, will use system console instead.")
        }
        this.terminal = terminal
        this.lineReader = lineReader
    }

    fun onUserInterrupt(sender: CommandSet.CommandSender): Nothing = runBlocking()
    {
        sender.err("You might have pressed Ctrl+C or performed another operation to stop the server.")
        sender.err(
            "This method is feasible but not recommended, " +
            "it should only be used when a command-line system error prevents the program from closing."
        )
        sender.err("If you want to stop the server, please use the \"stop\" command.")
        Power.shutdown(0, "User interrupt")
    }

    /**
     * 命令历史文件
     */
    private val historyFile: File
        get() = File(dataDir, "command_history.txt")

    /**
     * 在终端上打印一行, 会自动换行并下移命令提升符和已经输入的命令
     */
    fun println(o: Any)
    {
        if (lineReader != null)
        {
            if (lineReader.isReading)
                lineReader.printAbove("\r$o")
            else
                terminal!!.writer().println(o)
        }
        else nativeOut.println(o)
    }

    /**
     * 清空终端
     */
    fun clear()
    {
        nativeOut.print("\u001bc")
        if (lineReader is LineReaderImpl && lineReader.isReading)
            lineReader.redrawLine()
    }
}