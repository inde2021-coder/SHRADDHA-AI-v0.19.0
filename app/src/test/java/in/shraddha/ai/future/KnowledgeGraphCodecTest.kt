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
    @Test fun graphRejectsBlankIdsInvalidTitlesAndSelfRelationships() {
        val graph = KnowledgeGraph()
        try { graph.upsert(KnowledgeNode("", "Title", "note")); throw AssertionError("Blank ID should fail") } catch (_: IllegalArgumentException) { }
        try { graph.upsert(KnowledgeNode("id", " ", "note")); throw AssertionError("Blank title should fail") } catch (_: IllegalArgumentException) { }
        graph.upsert(KnowledgeNode("a", "Alpha", "note"))
        try { graph.connect("a", "related_to", "a"); throw AssertionError("Self-link should fail") } catch (_: IllegalArgumentException) { }
    }

    @Test fun removingNodeAlsoRemovesItsRelationships() {
        val graph = KnowledgeGraph()
        graph.upsert(KnowledgeNode("a", "Alpha", "note")); graph.upsert(KnowledgeNode("b", "Beta", "note"))
        graph.connect("a", "related_to", "b"); graph.remove("a")
        assertTrue(graph.allNodes().none { it.id == "a" }); assertTrue(graph.allEdges().isEmpty())
    }

    @Test fun graphEnforcesNodeAndRelationshipLimitsBeforeEncoding() {
        val graph = KnowledgeGraph()
        repeat(KnowledgeGraph.MAX_NODES) { graph.upsert(KnowledgeNode("n$it", "Node $it", "note")) }
        try { graph.upsert(KnowledgeNode("overflow", "Overflow", "note")); throw AssertionError("Node limit should fail") } catch (_: IllegalArgumentException) { }
        val links = KnowledgeGraph()
        links.upsert(KnowledgeNode("a", "A", "note")); links.upsert(KnowledgeNode("b", "B", "note"))
        repeat(KnowledgeGraph.MAX_EDGES) { links.connect("a", "relation_$it", "b") }
        try { links.connect("a", "overflow", "b"); throw AssertionError("Edge limit should fail") } catch (_: IllegalArgumentException) { }
    }
}
