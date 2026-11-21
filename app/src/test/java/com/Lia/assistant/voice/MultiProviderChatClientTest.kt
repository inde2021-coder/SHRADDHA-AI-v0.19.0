package com.Lia.assistant.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class MultiProviderChatClientTest {

    @Test
    fun parsesAgentMessageAndCitationAnnotations() {
        val response = JSONObject(
            """{
              "status":"completed",
              "output":[{
                "type":"message",
                "content":[{
                  "type":"output_text",
                  "text":"भारत की राजधानी नई दिल्ली है।",
                  "annotations":[{"type":"url_citation","title":"Government source","url":"https://example.gov.in"}]
                }]
              }]
            }"""
        )

        val result = MultiProviderChatClient.parsePerplexityAgentResponse(response)
        assertTrue(result is ChatReplyResult.Success)
        val text = (result as ChatReplyResult.Success).text
        assertTrue(text.contains("नई दिल्ली"))
        assertTrue(text.contains("Government source"))
        assertTrue(text.contains("https://example.gov.in"))
    }

    @Test
    fun parsesAgentSearchResultItems() {
        val response = JSONObject(
            """{
              "status":"completed",
              "output":[
                {"type":"search_results","results":[
                  {"title":"Official result","url":"https://example.org","snippet":"summary"}
                ]},
                {"type":"message","content":[{"type":"output_text","text":"Found one result."}]}
              ]
            }"""
        )

        val result = MultiProviderChatClient.parsePerplexityAgentResponse(response)
        assertTrue(result is ChatReplyResult.Success)
        val text = (result as ChatReplyResult.Success).text
        assertTrue(text.contains("Found one result."))
        assertTrue(text.contains("https://example.org"))
    }

    @Test
    fun reportsAgentFailureAndEmptyOutput() {
        val failed = MultiProviderChatClient.parsePerplexityAgentResponse(
            JSONObject("""{"status":"failed","error":{"message":"Quota exceeded"}}""")
        )
        assertTrue(failed is ChatReplyResult.Error)
        assertTrue((failed as ChatReplyResult.Error).message.contains("Quota exceeded"))

        val empty = MultiProviderChatClient.parsePerplexityAgentResponse(
            JSONObject("""{"status":"completed","output":[]}""")
        )
        assertTrue(empty is ChatReplyResult.Error)
    }
}
