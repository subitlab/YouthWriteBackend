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
        sender.out.println("SubIT Forum Backend")
        sender.out.println("Version: $version")
        sender.out.println("Author: SubIT Team")
        sender.out.println("Github: https://github.com/subitlab")
        sender.out.println("Website: https://subit.org.cn")
        sender.out.println("Email: subit@i.pkuschool.edu.cn")
        return true
    }
}