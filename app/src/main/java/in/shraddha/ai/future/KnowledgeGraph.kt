package `in`.shraddha.ai.future

/** User-controlled local knowledge graph primitives. No automatic sensitive inference. */
data class KnowledgeNode(val id:String, val label:String, val kind:String, val note:String = "", val updatedAt:Long = System.currentTimeMillis())
data class KnowledgeEdge(val fromId:String, val relation:String, val toId:String)
class KnowledgeGraph {
    private val nodes=linkedMapOf<String,KnowledgeNode>()
    private val edges=linkedSetOf<KnowledgeEdge>()
    fun upsert(node:KnowledgeNode) { require(node.label.isNotBlank()); nodes[node.id]=node.copy(label=node.label.trim(),kind=node.kind.take(40),note=node.note.take(1000)) }
    fun connect(fromId:String, relation:String, toId:String) { require(fromId!=toId && nodes.containsKey(fromId) && nodes.containsKey(toId)); require(relation.isNotBlank()); edges.add(KnowledgeEdge(fromId,relation.trim().take(60),toId)) }
    fun search(query:String):List<KnowledgeNode> = nodes.values.filter { it.label.contains(query,true) || it.note.contains(query,true) }.sortedByDescending { it.updatedAt }
    fun linksFor(id:String):List<KnowledgeEdge> = edges.filter { it.fromId==id || it.toId==id }
    fun remove(id:String) { nodes.remove(id); edges.removeAll { it.fromId==id || it.toId==id } }
    fun allNodes():List<KnowledgeNode> = nodes.values.toList()
    fun allEdges():List<KnowledgeEdge> = edges.toList()
}
