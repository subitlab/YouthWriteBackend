package subit.plugin

import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.data.AuthKeyLocation
import io.github.smiley4.ktorswaggerui.data.AuthScheme
import io.github.smiley4.ktorswaggerui.data.AuthType
import io.github.smiley4.ktorswaggerui.data.KTypeDescriptor
import io.github.smiley4.schemakenerator.reflection.processReflection
import io.github.smiley4.schemakenerator.swagger.compileInlining
import io.github.smiley4.schemakenerator.swagger.data.TitleType
import io.github.smiley4.schemakenerator.swagger.generateSwaggerSchema
import io.github.smiley4.schemakenerator.swagger.withAutoTitle
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 在/api-docs 路径下安装SwaggerUI
 */
@OptIn(ExperimentalSerializationApi::class)
fun Application.installApiDoc() = install(SwaggerUI)
{
    info()
    {
        title = "论坛后端API文档"
        version = subit.version
        description = "SubIT论坛后端API文档"
    }
    this.ignoredRouteSelectors += RateLimitRouteSelector::class

    val serverUrl = this@installApiDoc.environment.config.propertyOrNull("serverUrl")

    val servers =
        if (serverUrl == null) emptyList()
        else runCatching { serverUrl.getList() }.getOrElse { listOf(serverUrl.getString()) }

    servers.forEach { server { url = it } }

    schemas {
        generator = {
            it.processReflection()
                .generateSwaggerSchema()
                .withAutoTitle(TitleType.SIMPLE)
                .compileInlining()
        }
    }

    val json = Json()
    {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = true
        allowSpecialFloatingPointValues = true
        decodeEnumsCaseInsensitive = true
    }

    examples {
        encoder { type, example ->
            when (type)
            {
                is KTypeDescriptor -> json.encodeToString(serializer(type.type), example)
                else -> example
            }
        }
    }

    security {
        securityScheme("JWT")
        {
            name = "Authorization"
            scheme = AuthScheme.BEARER
            location = AuthKeyLocation.HEADER
            description = "JWT Token"
            bearerFormat = "Bearer <token>"
            type = AuthType.HTTP
        }
        defaultSecuritySchemeNames("JWT")
    }
}