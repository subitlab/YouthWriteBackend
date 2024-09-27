package subit

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.intellij.lang.annotations.Language
import org.junit.Test
import subit.dataClasses.*
import subit.utils.*

class ForumBackendTest
{
    fun test() = testApplication() {
        createClient() {
            install(ContentNegotiation) {
                json(Json() {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }
    }


    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun testContent()
    {
        @Language("JSON") val json = """
        [
          {
            "id":"ff6br",
            "children":[
              {
                "id":"ff6br",
                "children":[
                  {
                    "text":"12"
                  }
                ],
                "type":"p"
              },
              {
                "text": "34"
              }
            ],
            "type":"p"
          },
          {
            "type": "p",
            "children": [
              {
                "text": ""
              }
            ]
          }
        ]
        """.trimIndent()

        @Language("JSON") val newJson = """
        [
          {
            "type": "p",
            "children": [
              {
                "type": "p",
                "children": [
                  {
                    "subid": 0,
                    "text": "1",
                    "wmid": [
                      1,
                      3
                    ]
                  },
                  {
                    "text": "-"
                  },
                  {
                    "subid": 1,
                    "text": "2",
                    "wmid": [
                      2,
                      3
                    ]
                  }
                ]
              },
              {
                "subid": 2,
                "text": "3",
                "wmid": [
                  3
                ]
              },
              {
                "subid": 3,
                "text": "4",
                "wmid": [
                  3
                ]
              }
            ]
          },
          {
            "type": "p",
            "children": [
              {
                "text": ""
              }
            ]
          }
        ]
        """.trimIndent()
        val j = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
        val wordMarkings_ = listOf(
            WordMarkingInfo(WordMarkingId(1), PostVersionId(1), PostId(1), 0, 0, WordMarkingState.NORMAL),
            WordMarkingInfo(WordMarkingId(2), PostVersionId(1), PostId(1), 1, 1, WordMarkingState.NORMAL),
            WordMarkingInfo(WordMarkingId(3), PostVersionId(1), PostId(1), 0, 3, WordMarkingState.NORMAL),
        )
        val jsonElement = j.parseToJsonElement(json)
        val newJsonElement = j.parseToJsonElement(newJson)
        val wordMarkings = withWordMarkings(jsonElement, wordMarkings_)
        val splitContentNode = splitContentNode(jsonElement)
        val splitContentNodeAndWordMarkings = withWordMarkings(splitContentNode, wordMarkings_)
        val clearAndMerge = clearAndMerge(splitContentNodeAndWordMarkings)
        val mapWordMarkings = mapWordMarkings(jsonElement, newJsonElement, wordMarkings_)
        println("wordMarkings: ${j.encodeToString(wordMarkings)}")
        println("splitContentNode: ${j.encodeToString(splitContentNode)}")
        println("wordMarkings2: ${j.encodeToString(splitContentNodeAndWordMarkings)}")
        println("clearIdAndMerge: ${j.encodeToString(clearAndMerge)}")
        println("getContentText: ${getContentText(clearAndMerge)}")
        println("mapWordMarkings: ${j.encodeToString(mapWordMarkings)}")
    }
}