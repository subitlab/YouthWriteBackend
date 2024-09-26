package subit.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.intellij.lang.annotations.Language
import subit.dataClasses.*

const val SUB_CONTENT_LENGTH = 100

private const val ID_KEY = "subid"
private const val WORD_MARKING_KEY = "wmid"

private data class SplitContentNode(val maxId: Int, val nodes: List<JsonElement>)

/**
 * 把获得的帖子内容的node中, 有多个字符的node拆开为若干node, 并编号
 * @param nodes 帖子内容的node
 */
fun splitContentNode(nodes: JsonElement): JsonElement
{
    var id = 0
    fun splitContentNodeImpl(nodes: JsonElement): List<JsonElement> = when (nodes)
    {
        is JsonArray ->
        {
            val list = mutableListOf<JsonElement>()
            for (node in nodes)
            {
                val res = splitContentNodeImpl(node)
                list.addAll(res)
            }
            listOf(JsonArray(list))
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
                    obj.remove("id")
                    obj.remove("text")
                    obj.remove("children")
                    obj.remove(ID_KEY)
                    obj["text"] = JsonPrimitive(x.toString())
                    obj[ID_KEY] = JsonPrimitive(id++)
                    list.add(JsonObject(obj))
                }
                list
            }
            else if (node != null)
            {
                val obj = nodes.toMutableMap()
                obj.remove("id")
                obj.remove("text")
                obj.remove("children")
                obj.remove(ID_KEY)
                val splitNode = splitContentNodeImpl(node)
                obj["children"] = splitNode[0]
                listOf(JsonObject(obj))
            }
            else listOf(nodes)
        }
        else -> listOf(nodes)
    }
    val res = splitContentNodeImpl(nodes)
    return if (res.size == 1) res[0] else JsonArray(res)
}

data class ContentWithIdAndIndex(val content: Char, val subId: Int?, val index: Int)

fun getContentWithIdAndIndex(nodes: JsonElement): List<ContentWithIdAndIndex>
{
    val map = mutableListOf<ContentWithIdAndIndex>()
    var index = 0
    fun dfs(node: JsonElement)
    {
        if (node is JsonArray)
        {
            for (n in node)
                dfs(n)
        }
        else if (node is JsonObject)
        {
            val id = node[ID_KEY]?.jsonPrimitive?.int
            val text = node["text"]?.jsonPrimitive?.content

            if (text != null)
            {
                for ((i, c) in text.withIndex())
                {
                    map +=
                        if (i == 0 && id != null) ContentWithIdAndIndex(c, id, index)
                        else ContentWithIdAndIndex(c, null, index)
                    index++
                }
            }

            val children = node["children"]
            if (children != null)
                dfs(children)
        }
    }
    dfs(nodes)
    return map
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

/**
 * 清除帖子内容中的编号及wordMarking, 并合并相邻的相同内容
 */
fun clearAndMerge(nodes: JsonElement): JsonElement
{
    fun equals(map1: Map<String, JsonElement>, map2: Map<String, JsonElement>): Boolean
    {
        if (map1.size != map2.size) return false
        for ((key, value) in map1)
        {
            if (key !in map2) return false
            val v2 = map2[key] ?: return false
            if (value is JsonObject && v2 is JsonObject)
            {
                if (!equals(value, v2)) return false
            }
            else if (value != v2) return false
        }
        return true
    }

    fun canMerge(node1: JsonElement, node2: JsonElement): Boolean
    {
        if (node1 is JsonObject && node2 is JsonObject)
        {
            val map1 = node1.toMutableMap()
            val map2 = node2.toMutableMap()
            map1.remove(ID_KEY)
            map2.remove(ID_KEY)
            map1.remove("id")
            map2.remove("id")
            map1.remove("text")
            map2.remove("text")
            map1.remove("children")
            map2.remove("children")
            map1.remove(WORD_MARKING_KEY)
            map2.remove(WORD_MARKING_KEY)
            return equals(map1, map2)
        }
        return false
    }

    fun merge(node1: JsonObject, node2: JsonObject): JsonElement
    {
        val map = node1.toMutableMap()
        val c1 = node1["text"] ?: node1["children"]
        val c2 = node2["text"] ?: node2["children"]
        map.remove(ID_KEY)
        map.remove("id")
        map.remove("text")
        map.remove("children")
        if (c1 is JsonPrimitive && c2 is JsonPrimitive)
        {
            map["text"] = JsonPrimitive(c1.content + c2.content)
        }
        else if (c1 is JsonArray && c2 is JsonArray)
        {
            val list = mutableListOf<JsonElement>()
            list.addAll(c1)
            list.addAll(c2)
            map["children"] = JsonArray(list)
        }
        return JsonObject(map)
    }

    return when (nodes)
    {
        is JsonArray  ->
        {
            val list = mutableListOf<JsonElement>()
            for (node in nodes)
            {
                val last = list.lastOrNull()
                if (last == null)
                {
                    list.add(node)
                    continue
                }
                if (canMerge(last, node))
                {
                    list[list.size - 1] = merge(last as JsonObject, node as JsonObject)
                }
                else list.add(node)
            }
            for (i in list.indices)
                list[i] = clearAndMerge(list[i])
            JsonArray(list)
        }

        is JsonObject ->
        {
            val obj = nodes.toMutableMap()
            val node = nodes["text"] ?: nodes["children"]
            obj.remove(ID_KEY)
            obj.remove("id")
            obj.remove("text")
            obj.remove("children")
            obj.remove(WORD_MARKING_KEY)
            if (node is JsonPrimitive)
                obj["text"] = JsonPrimitive(node.content)
            else if (node != null)
                obj["children"] = clearAndMerge(node)
            JsonObject(obj)
        }

        else          -> nodes
    }
}

/**
 * 给内容打上wordMarking
 */
fun withWordMarkings(jsonElement: JsonElement, wordMarkings: List<WordMarkingInfo>): JsonElement
{
    val map = mutableMapOf<Int, MutableSet<WordMarkingId>>()
    for (wordMarking in wordMarkings)
    {
        for (i in wordMarking.start..wordMarking.end)
            map.getOrPut(i) { hashSetOf() }.add(wordMarking.id)
    }

    fun withWordMarkings(
        nodes: JsonElement,
        wordMarkings: Map<Int, Set<WordMarkingId>>,
        index: Int
    ): SplitContentNode
    {
        var id = index
        return when (nodes)
        {
            is JsonArray ->
            {
                val list = mutableListOf<JsonElement>()
                for (node in nodes)
                {
                    val res = withWordMarkings(node, wordMarkings, id)
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
                    val list = mutableListOf<MutableMap<String, JsonElement>>()
                    var last: Set<WordMarkingId>? = null
                    for (x in text)
                    {
                        if (wordMarkings[id] == last && list.isNotEmpty())
                        {
                            val lastElement = list.last()
                            lastElement["text"] = JsonPrimitive(lastElement["text"]!!.jsonPrimitive.content + x)
                        }
                        else
                        {
                            val obj = nodes.toMutableMap()
                            obj.remove("id")
                            obj.remove("text")
                            obj.remove("children")
                            obj["text"] = JsonPrimitive(x.toString())
                            val wms = wordMarkings[id]
                            if (wms != null)
                            {
                                obj[WORD_MARKING_KEY] = JsonArray(wms.map { JsonPrimitive(it.value) })
                            }
                            last = wms
                            list.add(obj)
                        }
                        id++
                    }
                    SplitContentNode(id - 1, list.map { JsonObject(it) })
                }
                else if (node != null)
                {
                    val obj = nodes.toMutableMap()
                    obj.remove("id")
                    obj.remove("text")
                    obj.remove("children")
                    val splitNode = withWordMarkings(node, wordMarkings, id)
                    obj["children"] = if (splitNode.nodes.size == 1) splitNode.nodes[0] else JsonArray(splitNode.nodes)
                    id = splitNode.maxId + 1
                    SplitContentNode(id - 1, listOf(JsonObject(obj)))
                }
                else SplitContentNode(id, listOf(nodes))
            }
            else -> SplitContentNode(id, listOf(nodes))
        }
    }

    val res = withWordMarkings(jsonElement, map, 0)
    return if (res.nodes.size == 1) res.nodes[0] else JsonArray(res.nodes)
}

fun mapWordMarkings(oldContent: JsonElement, newContent: JsonElement, wordMarkings: List<WordMarkingInfo>): List<WordMarkingInfo>
{
    val old = getContentWithIdAndIndex(splitContentNode(oldContent)).associateBy { it.index }
    val new = getContentWithIdAndIndex(newContent).associateBy { it.subId }

    return wordMarkings.map()
    {
        var newStart: Int = -1
        var newEnd: Int = -1
        var lastIndex: Int? = null
        for (i in it.start..it.end)
        {
            val oldInfo = old[i] ?: return@map it.copy(start = -1, end = -1)
            val newInfo = new[oldInfo.subId] ?: return@map it.copy(start = -1, end = -1)
            if (oldInfo.content != newInfo.content) return@map it.copy(start = -1, end = -1)
            if (lastIndex != null && lastIndex + 1 != newInfo.index)
                return@map it.copy(start = -1, end = -1)
            if (i == it.start) newStart = newInfo.index
            if (i == it.end) newEnd = newInfo.index
            lastIndex = newInfo.index
        }
        it.copy(start = newStart, end = newEnd)
    }
}