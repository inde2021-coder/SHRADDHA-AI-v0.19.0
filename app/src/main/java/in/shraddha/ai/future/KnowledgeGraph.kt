package `in`.shraddha.ai.future

/** User-controlled local knowledge graph primitives. No automatic sensitive inference. */
data class KnowledgeNode(val id:String, val label:String, val kind:String, val note:String = "", val updatedAt:Long = System.currentTimeMillis())
data class KnowledgeEdge(val fromId:String, val relation:String, val toId:String)
class KnowledgeGraph {
    private val nodes=linkedMapOf<String,KnowledgeNode>()
    private val edges=linkedSetOf<KnowledgeEdge>()
    fun upsert(node: KnowledgeNode) {
        require(node.id.isNotBlank() && node.id.length <= 120) { "Memory ID is invalid" }
        require(node.label.isNotBlank() && node.label.length <= 160) { "Memory title must contain 1–160 characters" }
        require(nodes.containsKey(node.id) || nodes.size < MAX_NODES) { "Knowledge graph is limited to $MAX_NODES notes" }
        nodes[node.id] = node.copy(id = node.id.trim(), label = node.label.trim(), kind = node.kind.trim().take(40).ifBlank { "Note" }, note = node.note.take(1000))
    }
    fun connect(fromId: String, relation: String, toId: String) {
        require(fromId != toId && nodes.containsKey(fromId) && nodes.containsKey(toId)) { "Relationship endpoints must be two existing, different memories" }
        require(relation.isNotBlank()) { "Relationship label is required" }
        val edge = KnowledgeEdge(fromId, relation.trim().take(60), toId)
        require(edge in edges || edges.size < MAX_EDGES) { "Knowledge graph is limited to $MAX_EDGES relationships" }
        edges.add(edge)
    }
    fun search(query:String):List<KnowledgeNode> = nodes.values.filter { it.label.contains(query,true) || it.note.contains(query,true) }.sortedByDescending { it.updatedAt }
    fun linksFor(id:String):List<KnowledgeEdge> = edges.filter { it.fromId==id || it.toId==id }
    fun remove(id:String) { nodes.remove(id); edges.removeAll { it.fromId==id || it.toId==id } }
    fun allNodes():List<KnowledgeNode> = nodes.values.toList()
    fun allEdges(): List<KnowledgeEdge> = edges.toList()

    companion object {
        const val MAX_NODES = 100
        const val MAX_EDGES = 300
    }
}
