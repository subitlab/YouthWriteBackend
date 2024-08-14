package subit.utils

import java.util.*

/**
 * 检查密码是否合法
 * 要求密码长度在 6-20 之间，且仅包含数字、字母和特殊字符 !@#$%^&*()_+-=
 */
fun checkPassword(password: String): Boolean =
    password.length in 8..20 &&
    password.all { it.isLetterOrDigit() || it in "!@#$%^&*()_+-=" }

/**
 * 检查用户名是否合法
 * 要求用户名长度在 2-15 之间，且仅包含中文、数字、字母和特殊字符 _-.
 */
fun checkUsername(username: String): Boolean =
    username.length in 2..20 &&
    username.all { it in '\u4e00'..'\u9fa5' || it.isLetterOrDigit() || it in "_-." }

fun String?.toUUIDOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()