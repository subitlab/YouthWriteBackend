package subit.config

import kotlinx.serialization.Serializable
import net.mamoe.yamlkt.Comment

@Serializable
data class SystemConfig(
    @Comment("是否在系统维护中")
    val systemMaintaining: Boolean,
    @Comment("SSO服务后端url, 请不要以/结尾, 例如: https://sso.subit.org.cn/api 而不是 https://sso.subit.org.cn/api/")
    val ssoServer: String,
    @Comment("SSO服务ID")
    val ssoServerId: Int,
    @Comment("SSO服务秘钥")
    val ssoSecret: String,
    @Comment("是否开启实名认证")
    val requireRealName: Boolean,
)

var systemConfig: SystemConfig by config(
    "system.yml", 
    SystemConfig(false, "https://sso.subit.org.cn/api", 1, "secret", false),
)