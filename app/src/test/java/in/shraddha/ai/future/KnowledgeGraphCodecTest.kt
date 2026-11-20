package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGraphCodecTest {
    @Test fun roundTripsHindiAndEnglishNodesAndRelationships() {
        val graph = KnowledgeGraph()
        graph.upsert(KnowledgeNode("person-1", "राहुल", "person", "Prefers Hindi replies", 100L))
        graph.upsert(KnowledgeNode("topic-1", "Tent House", "project", "Booking workflow", 200L))
        graph.connect("person-1", "works_on", "topic-1")

        val restored = KnowledgeGraphCodec.decode(KnowledgeGraphCodec.encode(graph))
        assertEquals(graph.allNodes(), restored.allNodes())
        assertEquals(graph.allEdges(), restored.allEdges())
        assertEquals(1, restored.linksFor("person-1").size)
    }

    @Test fun emptyGraphRoundTrips() {
        val restored = KnowledgeGraphCodec.decode(KnowledgeGraphCodec.encode(KnowledgeGraph()))
        assertTrue(restored.allNodes().isEmpty())
        assertTrue(restored.allEdges().isEmpty())
    }

    @Test fun rejectsUnsupportedVersionAndDanglingEdges() {
        try {
            KnowledgeGraphCodec.decode("KG9")
            throw AssertionError("Unsupported version should fail")
        } catch (_: IllegalArgumentException) { }

        val dangling = "KG1\nE|YQ|cmVsYXRpb24|Yg"
        try {
            KnowledgeGraphCodec.decode(dangling)
            throw AssertionError("Dangling edge should fail")
        } catch (_: IllegalArgumentException) { }
    }
}
