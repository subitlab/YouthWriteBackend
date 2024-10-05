package subit.config

import kotlinx.serialization.Serializable
import net.mamoe.yamlkt.Comment

@Serializable
data class SystemConfig(
    @Comment("是否在系统维护中")
    val systemMaintaining: Boolean,
    @Comment("SSO服务后端url, 请不要以/结尾, 例如: https://ssubito.subit.org.cn/api 而不是 https://ssubito.subit.org.cn/api/")
    val ssoServer: String,
    @Comment("SSO服务秘钥")
    val ssoSecret: String,
)

var systemConfig: SystemConfig by config(
    "system.yml", 
    SystemConfig(false, "https://ssubito.subit.org.cn/api", "secret"),
)

/*
eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJBdXRoZW50aWNhdGlvbiIsInR5cGUiOiJTRVJWSUNFIiwiaWQiOjI3LCJleHAiOjE3NDM2ODk2MTgsImlzcyI6InN1Yml0IiwiaWF0IjoxNzI4MTM3NjE4fQ.rDS7Q8tjdHq0mhJFJ4mjoWjjDIWLMHDRGJNrOF4dYzsIEt7FdE9WbSvi8eLcNhJcIc4-4LgbVi4JnQ8TjINZoQ
 */