package subit.console.command

import subit.version

/**
 * About command.
 * print some info about this server.
 */
object About: Command
{
    override val description = "Show about."
    override val aliases = listOf("version", "ver")

    override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
    {
        sender.out("SubIT Forum Backend")
        sender.out("Version: $version")
        sender.out("Author: SubIT Team")
        sender.out("Github: https://github.com/subitlab")
        sender.out("Website: https://subit.org.cn")
        sender.out("Email: subit@i.pkuschool.edu.cn")
        return true
    }
}