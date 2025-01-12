package subit.console.command

import subit.config.systemConfig

/**
 * Maintain the server.
 */
object Maintain: Command
{
    override val description: String = "Maintain the server."
    override val args: String = "[true/false]"

    override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
    {
        if (args.size > 1) return false
        if (args.isEmpty()) sender.out("System Maintaining: ${systemConfig.systemMaintaining}")
        else
        {
            systemConfig = args[0].toBooleanStrictOrNull()?.let { systemConfig.copy(systemMaintaining = it) } ?: return false
        }
        return true
    }
}