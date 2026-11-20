package `in`.shraddha.ai.future

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Compact, versioned serializer for the encrypted personal knowledge graph. */
internal object KnowledgeGraphCodec {
    private const val VERSION = "KG1"
    private const val MAX_NODES = 100
    private const val MAX_EDGES = 300

    fun encode(graph: KnowledgeGraph): String {
        val nodes = graph.allNodes()
        val edges = graph.allEdges()
        require(nodes.size <= MAX_NODES) { "Knowledge graph is limited to $MAX_NODES notes" }
        require(edges.size <= MAX_EDGES) { "Knowledge graph is limited to $MAX_EDGES relationships" }
        val lines = mutableListOf(VERSION)
        nodes.forEach { node ->
            lines += listOf("N", enc(node.id), enc(node.label), enc(node.kind), enc(node.note), node.updatedAt.toString()).joinToString("|")
        }
        edges.forEach { edge ->
            lines += listOf("E", enc(edge.fromId), enc(edge.relation), enc(edge.toId)).joinToString("|")
        }
        return lines.joinToString("\n").also { require(it.length <= 15000) { "Knowledge graph is too large to store" } }
    }

    fun decode(payload: String): KnowledgeGraph {
        require(payload.isNotBlank() && payload.length <= 15000) { "Knowledge graph data is empty or too large" }
        val lines = payload.lineSequence().toList()
        require(lines.firstOrNull() == VERSION) { "Unsupported knowledge graph format" }
        val graph = KnowledgeGraph()
        val nodes = linkedMapOf<String, KnowledgeNode>()
        val edges = mutableListOf<KnowledgeEdge>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            when (line.substringBefore("|")) {
                "N" -> {
                    val p = line.split("|")
                    require(p.size == 6) { "Invalid knowledge node record" }
                    val node = KnowledgeNode(dec(p[1]), dec(p[2]), dec(p[3]), dec(p[4]), p[5].toLong())
                    require(node.id.isNotBlank() && node.label.isNotBlank()) { "Invalid knowledge node" }
                    require(nodes.put(node.id, node) == null) { "Duplicate knowledge node ID" }
                    require(nodes.size <= MAX_NODES) { "Too many knowledge nodes" }
                }
                "E" -> {
                    val p = line.split("|")
                    require(p.size == 4) { "Invalid knowledge relationship record" }
                    edges += KnowledgeEdge(dec(p[1]), dec(p[2]), dec(p[3]))
                    require(edges.size <= MAX_EDGES) { "Too many knowledge relationships" }
                }
                else -> error("Unknown knowledge graph record")
            }
        }
        nodes.values.forEach(graph::upsert)
        edges.forEach { edge ->
            require(nodes.containsKey(edge.fromId) && nodes.containsKey(edge.toId)) { "Relationship refers to a missing note" }
            graph.connect(edge.fromId, edge.relation, edge.toId)
        }
        return graph
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
