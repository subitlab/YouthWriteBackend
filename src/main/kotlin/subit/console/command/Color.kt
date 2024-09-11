package subit.console.command

import subit.config.loggerConfig
import subit.console.*
import subit.console.AnsiStyle.Companion.ansi

object Color: TreeCommand(Test, Mode, Effect)
{
    override val description = "color settings"

    object Test: Command
    {
        override val description = "Test color display. If you appear garbled, you can adjust the color settings."
        override suspend fun execute(args: List<String>): Boolean
        {
            val sb = StringBuilder().append("")
                .append("If certain colors or effects are not supported, ")
                .append("you can use ")
                .append(AnsiEffect.BOLD.ansi()+SimpleAnsiColor.CYAN.bright().ansi())
                .append("color mode [rgb | simple | none] ")
                .append(AnsiStyle.RESET)
                .append("and ")
                .append(AnsiEffect.BOLD.ansi()+SimpleAnsiColor.CYAN.bright().ansi())
                .append("color effect [on | off]")
                .append(AnsiStyle.RESET)
                .append("to set the corresponding color and effect modes, respectively")
                .append("\n")
            for (effect in AnsiEffect.entries) sb.append("$effect${effect.name.lowercase()}${AnsiStyle.RESET} ")
            sb.append("\n")
            for (color in SimpleAnsiColor.entries) sb.append("${color.value}${color.key}${AnsiStyle.RESET} ")
            sb.append("\n")
            sb.append("R:\n")
            for (i in 0.toUByte()..UByte.MAX_VALUE)
                sb.append("${RGBAnsiColor.fromRGB(i.toInt(), 0, 0)}$i${AnsiStyle.RESET} ")
            sb.append("\n")
            sb.append("G:\n")
            for (i in 0.toUByte()..UByte.MAX_VALUE)
                sb.append("${RGBAnsiColor.fromRGB(0, i.toInt(), 0)}$i${AnsiStyle.RESET} ")
            sb.append("\n")
            sb.append("B:\n")
            for (i in 0.toUByte()..UByte.MAX_VALUE)
                sb.append("${RGBAnsiColor.fromRGB(0, 0, i.toInt())}$i${AnsiStyle.RESET} ")
            sb.toString().split("\n").forEach { CommandSet.out.println(it) }
            return true
        }
    }

    object Mode: TreeCommand(RGB, Simple, None)
    {
        override val description = "color display mode"

        object RGB: Command
        {
            override val description = "Use RGB and simple color"
            override suspend fun execute(args: List<String>): Boolean
            {
                Console.ansiColorMode = ColorDisplayMode.RGB
                loggerConfig = loggerConfig.copy(color = ColorDisplayMode.RGB)
                CommandSet.out.println("Color mode: RGB")
                return true
            }
        }

        object Simple: Command
        {
            override val description = "Use simple color"
            override suspend fun execute(args: List<String>): Boolean
            {
                Console.ansiColorMode = ColorDisplayMode.SIMPLE
                loggerConfig = loggerConfig.copy(color = ColorDisplayMode.SIMPLE)
                CommandSet.out.println("Color mode: Simple")
                return true
            }
        }

        object None: Command
        {
            override val description = "Disable color"
            override suspend fun execute(args: List<String>): Boolean
            {
                Console.ansiColorMode = ColorDisplayMode.NONE
                loggerConfig = loggerConfig.copy(color = ColorDisplayMode.NONE)
                CommandSet.out.println("Color mode: None")
                return true
            }
        }
    }

    object Effect: TreeCommand(On, Off)
    {
        override val description = "color effect"

        object On: Command
        {
            override val description = "Enable color effect"
            override suspend fun execute(args: List<String>): Boolean
            {
                Console.ansiEffectMode = EffectDisplayMode.ON
                loggerConfig = loggerConfig.copy(effect = true)
                CommandSet.out.println("Color effect: On")
                return true
            }
        }

        object Off: Command
        {
            override val description = "Disable color effect"
            override suspend fun execute(args: List<String>): Boolean
            {
                Console.ansiEffectMode = EffectDisplayMode.OFF
                loggerConfig = loggerConfig.copy(effect = false)
                CommandSet.out.println("Color effect: Off")
                return true
            }
        }
    }
}