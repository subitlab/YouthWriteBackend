package subit.utils

import kotlinx.serialization.json.*

const val SUB_CONTENT_LENGTH = 100

private const val ID_KEY = "subid"

data class SplitContentNode(val maxId: Int, val nodes: List<JsonElement>)

/**
 * 把获得的帖子内容的node中, 有多个字符的node拆开为若干node, 并编号
 * @param nodes 帖子内容的node
 */
fun splitContentNode(nodes: JsonElement): JsonElement
{
    val res = splitContentNode(nodes, 0).nodes
    return if (res.size == 1) res[0] else JsonArray(res)
}

/**
 * 把获得的帖子内容的node中, 有多个字符的node拆开为若干node, 并编号
 * @param nodes 帖子内容的node
 * @param initialId 初始编号
 * @return 拆分后的node及其最大编号
 */
fun splitContentNode(nodes: JsonElement, initialId: Int): SplitContentNode
{
    var id = initialId
    return when (nodes)
    {
        is JsonArray ->
        {
            val list = mutableListOf<JsonElement>()
            for (node in nodes)
            {
                val res = splitContentNode(node, id)
                list.addAll(res.nodes)
                id = res.maxId + 1
            }
            SplitContentNode(id - 1, listOf(JsonArray(list)))
        }
        is JsonObject ->
        {
            val node = nodes["text"] ?: nodes["children"]
            if (node is JsonPrimitive)
            {
                val text = node.content
                val list = mutableListOf<JsonElement>()
                for (x in text)
                {
                    val obj = nodes.toMutableMap()
                    obj.remove("text")
                    obj.remove("children")
                    obj.remove(ID_KEY)
                    obj["text"] = JsonPrimitive(x.toString())
                    obj[ID_KEY] = JsonPrimitive(id++)
                    list.add(JsonObject(obj))
                }
                SplitContentNode(id - 1, list)
            }
            else if (node != null)
            {
                val obj = nodes.toMutableMap()
                obj.remove("text")
                obj.remove("children")
                obj.remove(ID_KEY)
                val splitNode = splitContentNode(node, id)
                obj["children"] = splitNode.nodes[0]
                id = splitNode.maxId + 1
                SplitContentNode(id - 1, listOf(JsonObject(obj)))
            }
            else SplitContentNode(id, listOf(nodes))
        }
        else -> SplitContentNode(id, listOf(nodes))
    }
}

/**
 * 从已编号过的帖子内容中获得编号及其对应的text
 * @param nodes 帖子内容的node
 */
fun getContentTextWithId(nodes: JsonElement): Map<Int, String>
{
    val res = mutableMapOf<Int, String>()
    getContentTextWithId(nodes, res)
    return res
}

private fun getContentTextWithId(nodes: JsonElement, res: MutableMap<Int, String>)
{
    when (nodes)
    {
        is JsonArray  -> for (node in nodes) getContentTextWithId(node, res)
        is JsonObject ->
        {
            val id = nodes[ID_KEY]?.jsonPrimitive?.int ?: return
            val node = nodes["text"] ?: nodes["children"]
            if (node is JsonPrimitive) res[id] = node.content
            else if (node != null) getContentTextWithId(node, res)
        }
        is JsonPrimitive -> return
    }
}

/**
 * 清除帖子内容中的编号
 */
fun clearContentId(nodes: JsonElement): JsonElement
{
    return when (nodes)
    {
        is JsonArray ->
        {
            val list = mutableListOf<JsonElement>()
            for (node in nodes)
                list.add(clearContentId(node))
            JsonArray(list)
        }
        is JsonObject ->
        {
            val obj = nodes.toMutableMap()
            obj.remove(ID_KEY)
            val node = nodes["text"] ?: nodes["children"]
            obj.remove("text")
            obj.remove("children")
            if (node is JsonPrimitive) obj["text"] = JsonPrimitive(node.content)
            else if (node != null)
            {
                obj["children"] = clearContentId(node)
            }
            JsonObject(obj)
        }
        else -> nodes
    }
}

/**
 * 从node中获得帖子的纯文本内容
 * @param nodes 帖子内容的node
 * @param maxLength 最大长度(如果超过此长度则截断取前maxLength个字符). 默认100
 * @param overLengthTail 超过最大长度时的后缀. 默认"..."
 */
fun getContentText(nodes: JsonElement, maxLength: Int = Int.MAX_VALUE, overLengthTail: String = "..."): String
{
    if (maxLength < 0) return overLengthTail
    return when (nodes)
    {
        is JsonArray ->
        {
            val sb = StringBuilder()
            for (node in nodes)
                sb.append(getContentText(node, maxLength - sb.length, overLengthTail))
            sb.toString()
        }
        is JsonObject -> when (val node = nodes["text"] ?: nodes["children"])
        {
            is JsonPrimitive ->
            {
                if (node.content.length > maxLength)
                    node.content.substring(0, maxLength) + overLengthTail
                else node.content
            }
            null -> ""
            else -> getContentText(node, maxLength, overLengthTail)
        }
        else -> ""
    }
}

/*
fun main()
{
    @Language("JSON")
    val json = """
        [
          {
            "id":"ff6br",
            "children":[
              {
                "id":"ff6br",
                "children":[
                  {
                    "text":"1"
                  },
                  {
                    "text":"2"
                  }
                ],
                "type":"p"
              },
              {
                "text": "3"
              }
            ],
            "type":"p"
          }
        ]
    """.trimIndent()
    val j = Json { prettyPrint = true }
    val jsonElement = j.parseToJsonElement(json)
    splitContentNode(jsonElement).nodes.forEach { println(j.encodeToString<JsonElement>(it)) }
}*/
