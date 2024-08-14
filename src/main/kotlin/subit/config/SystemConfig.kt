package subit.config

import kotlinx.serialization.Serializable
import net.mamoe.yamlkt.Comment

@Serializable
data class SystemConfig(
    @Comment("是否在系统维护中")
    val systemMaintaining: Boolean,
    @Comment("SSO服务器地址, 请不要以/结尾, 例如: https://ssubito.subit.org.cn/api " +
             "而不是 https://ssubito.subit.org.cn/api/")
    val ssoServer: String,
)

var systemConfig: SystemConfig by config(
    "system.yml", 
    SystemConfig(false, "https://ssubito.subit.org.cn/api")
)