package `in`.shraddha.ai.future

import android.content.Context
import java.util.UUID
import `in`.shraddha.ai.brain.PersonalKnowledgeStore
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Deterministic task DAG planner. It never executes actions; it only returns an ordered plan. */
object AutonomousTaskPlanner {
    data class Task(val id:String, val title:String, val dependsOn:Set<String> = emptySet(), val priority:Int = 3, val estimatedMinutes:Int = 15)
    data class Plan(val ordered:List<Task>, val totalEstimatedMinutes:Int)
    fun plan(tasks:List<Task>):Plan {
        require(tasks.isNotEmpty()) { "At least one task is required" }
        require(tasks.map { it.id }.distinct().size == tasks.size) { "Task IDs must be unique" }
        val byId=tasks.associateBy { it.id }
        tasks.forEach { task ->
            require(task.id.isNotBlank() && task.title.isNotBlank())
            require(task.priority in 1..5 && task.estimatedMinutes in 1..10080)
            require(task.id !in task.dependsOn && task.dependsOn.all { it in byId }) { "Unknown or self dependency in ${task.id}" }
        }
        val remaining=tasks.associate { it.id to it.dependsOn.toMutableSet() }.toMutableMap()
        val ordered=mutableListOf<Task>()
        while(remaining.isNotEmpty()) {
            val ready=remaining.filterValues { it.isEmpty() }.keys.map { byId.getValue(it) }
                .sortedWith(compareBy<Task> { it.priority }.thenBy { it.estimatedMinutes }.thenBy { it.id })
            require(ready.isNotEmpty()) { "Task dependencies contain a cycle" }
            ready.forEach { task -> ordered.add(task); remaining.remove(task.id) }
            remaining.values.forEach { it.removeAll(ready.map { task -> task.id }.toSet()) }
        }
        return Plan(ordered,ordered.sumOf { it.estimatedMinutes })
    }
}

/** Post-task quality gate; a task is complete only when its explicit acceptance checks pass. */
object SelfReviewEngine {
    data class Check(val id:String,val description:String,val passed:Boolean)
    data class Review(val passed:Boolean,val scorePercent:Int,val failedChecks:List<Check>,val summary:String)
    fun review(checks:List<Check>):Review {
        require(checks.isNotEmpty() && checks.map { it.id }.distinct().size==checks.size)
        require(checks.all { it.id.isNotBlank() && it.description.isNotBlank() })
        val failed=checks.filterNot { it.passed }
        val score=((checks.size-failed.size)*100.0/checks.size).toInt()
        return Review(failed.isEmpty(),score,failed,if(failed.isEmpty()) "All acceptance checks passed" else "${failed.size} of ${checks.size} checks need attention")
    }
}

/** Extracts only explicit transcript markers; it does not infer commitments from ordinary speech. */
object MeetingIntelligence {
    data class ActionItem(val owner:String?,val task:String)
    data class Notes(val summary:String,val decisions:List<String>,val actions:List<ActionItem>,val openQuestions:List<String>)
    fun parse(transcript:String):Notes {
        require(transcript.isNotBlank() && transcript.length<=100_000)
        val decisions=mutableListOf<String>(); val actions=mutableListOf<ActionItem>(); val questions=mutableListOf<String>()
        transcript.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            when {
                line.startsWith("DECISION:",true) -> line.substringAfter(':').trim().takeIf { it.isNotBlank() }?.let(decisions::add)
                line.startsWith("ACTION:",true) -> {
                    val body=line.substringAfter(':').trim()
                    if(body.isNotBlank()) {
                        val owner=Regex("^@([^\\s:]{1,40})\\s*:\\s*(.+)$").find(body)
                        actions.add(if(owner!=null) ActionItem(owner.groupValues[1],owner.groupValues[2]) else ActionItem(null,body))
                    }
                }
                line.startsWith("QUESTION:",true) -> line.substringAfter(':').trim().takeIf { it.isNotBlank() }?.let(questions::add)
            }
        }
        val summary=transcript.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("DECISION:",true) && !it.startsWith("ACTION:",true) && !it.startsWith("QUESTION:",true) }.take(5).joinToString(" ").take(1200)
        return Notes(summary,decisions.distinct(),actions.distinct(),questions.distinct())
    }
}

/** Briefing composer uses only data supplied by the user or an authorized calendar connector. */
object DailyBriefingEngine {
    data class Item(val title:String,val timeLabel:String?=null,val priority:Int=3,val kind:String="task")
    data class Briefing(val headline:String,val urgent:List<Item>,val schedule:List<Item>,val reminders:List<Item>)
    fun compose(items:List<Item>):Briefing {
        require(items.size<=500 && items.all { it.title.isNotBlank() && it.priority in 1..5 })
        val urgent=items.filter { it.priority<=2 }.sortedBy { it.timeLabel.orEmpty() }
        val schedule=items.filter { it.kind=="event" }.sortedBy { it.timeLabel.orEmpty() }
        val reminders=items.filter { it.kind=="reminder" }.sortedBy { it.timeLabel.orEmpty() }
        val count=items.size
        return Briefing(if(count==0) "Your day is clear" else "$count items on your agenda",urgent,schedule,reminders)
    }
}

/** Wake phrase lifecycle gate. Audio detection must be supplied by a separately licensed on-device model. */
class WakePhraseGate(private val phrase:String) {
    enum class State { DISABLED, ARMED, ACTIVE }
    var state:State=State.DISABLED; private set
    init { require(phrase.trim().length in 2..40) }
    fun arm(consent:Boolean) { require(consent) { "Explicit microphone consent is required" }; state=State.ARMED }
    fun detected(candidate:String,confidence:Float,threshold:Float=0.82f):Boolean {
        if(state!=State.ARMED || confidence !in threshold..1f) return false
        if(candidate.trim().equals(phrase.trim(),true)) { state=State.ACTIVE; return true }
        return false
    }
    fun deactivate() { state=State.DISABLED }
    fun rearm(consent:Boolean) { arm(consent) }
}

/** Voice presets store configuration only; a real TTS provider/model must render the audio. */
object VoiceProfileRegistry {
    data class Profile(val id:String,val displayName:String,val languageTags:Set<String>,val rate:Float=1f,val pitch:Float=1f,val provider:String)
    fun validate(profile:Profile):Profile {
        require(profile.id.matches(Regex("[a-z0-9_-]{2,40}")) && profile.displayName.isNotBlank())
        require(profile.languageTags.isNotEmpty() && profile.languageTags.all { it.matches(Regex("[a-z]{2,3}(-[A-Z]{2})?")) })
        require(profile.rate in 0.5f..2f && profile.pitch in 0.5f..2f && profile.provider.isNotBlank())
        return profile.copy(displayName=profile.displayName.trim(),languageTags=profile.languageTags.toSet())
    }
}

/** Privacy-safe translation request envelope. Actual translation is performed by a configured model. */
object LiveTranslationEngine {
    data class Request(val text:String,val sourceLanguage:String,val targetLanguage:String,val preserveFormatting:Boolean=true)
    fun prepare(text:String,source:String,target:String):Request {
        require(text.isNotBlank() && text.length<=20_000)
        require(source.matches(Regex("[a-z]{2,3}(-[A-Z]{2})?")) && target.matches(Regex("[a-z]{2,3}(-[A-Z]{2})?")) && source!=target)
        return Request(text,source,target)
    }
}

/** Safe troubleshooting playbooks. They guide observation and never claim a remote diagnosis. */
object VisualTroubleshootingEngine {
    data class Step(val instruction:String,val expectedObservation:String)
    fun playbook(category:String):List<Step> = when(category.lowercase()) {
        "network" -> listOf(Step("Check whether airplane mode is off","Mobile/Wi-Fi radios are enabled"),Step("Check router/modem power and status lights","Power and internet indicators are stable"),Step("Test one known website on another device","Confirms whether the issue is device-specific"),Step("Restart network equipment only when safe","Connection returns without changing security settings"))
        "battery" -> listOf(Step("Check battery and charging-port condition","No swelling, heat, liquid or visible damage"),Step("Try a known-good compatible charger","Charging indicator appears"),Step("Review battery usage by app","Unexpected drain source is identified"),Step("Stop using a swollen or overheating device","Device is powered down and serviced safely"))
        "electrical" -> listOf(Step("Do not touch exposed conductors or open live equipment","Work area remains isolated"),Step("Switch off and isolate the supply if safe","No live work is attempted"),Step("Record model, rating and visible fault indicators","Evidence is available for a qualified electrician"),Step("Escalate mains or burning-smell faults immediately","Qualified professional inspects the equipment"))
        else -> listOf(Step("Describe the symptom and when it occurs","A reproducible symptom is recorded"),Step("Capture model number and visible error code","Device identity and error are available"),Step("Avoid opening powered or sealed equipment","Warranty and personal safety are protected"),Step("Compare with manufacturer guidance","Only model-specific verified steps are followed"))
    }
}

/** 3D map data primitives; coordinates must come from an explicitly enabled sensor/camera pipeline. */
object EnvironmentMapEngine {
    data class Point3(val x:Float,val y:Float,val z:Float)
    data class Landmark(val id:String,val label:String,val position:Point3,val confidence:Float)
    fun validate(points:List<Landmark>):List<Landmark> {
        require(points.size<=10_000 && points.map { it.id }.distinct().size==points.size)
        require(points.all { it.id.isNotBlank() && it.label.isNotBlank() && it.confidence in 0f..1f && listOf(it.position.x,it.position.y,it.position.z).all(Float::isFinite) })
        return points.toList()
    }
}

/** End-to-end encrypted short text clipboard packet; sender and receiver must share a 256-bit key. */
object SecureClipboardPacket {
    data class Packet(val createdAt:Long,val expiresAt:Long,val nonce:String,val ciphertext:String)
    fun seal(text:String,key:ByteArray,now:Long=System.currentTimeMillis(),ttlMillis:Long=120_000):Packet {
        require(text.length<=20_000 && key.size==32 && ttlMillis in 1_000..600_000)
        val nonce=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce))
        val encrypted=cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Packet(now,now+ttlMillis,Base64.getEncoder().encodeToString(nonce),Base64.getEncoder().encodeToString(encrypted))
    }
    fun open(packet:Packet,key:ByteArray,now:Long=System.currentTimeMillis()):String {
        require(key.size==32 && now in packet.createdAt..packet.expiresAt) { "Clipboard packet expired or key invalid" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,Base64.getDecoder().decode(packet.nonce)))
        return cipher.doFinal(Base64.getDecoder().decode(packet.ciphertext)).toString(Charsets.UTF_8)
    }
}

/** Chunk manifest supports resumable, integrity-checked transfers; transport and peer consent are separate. */
object SecureFileTransfer {
    data class Manifest(val fileName:String,val sizeBytes:Long,val sha256:String,val chunkSize:Int,val chunkCount:Int)
    fun manifest(fileName:String,sizeBytes:Long,sha256:String,chunkSize:Int=256*1024):Manifest {
        require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\') && fileName!="." && fileName!="..")
        require(sizeBytes in 0..(2L*1024*1024*1024) && sha256.matches(Regex("[a-fA-F0-9]{64}")) && chunkSize in 16*1024..1024*1024)
        val count=if(sizeBytes==0L) 0 else ((sizeBytes+chunkSize-1)/chunkSize).toInt()
        return Manifest(fileName,sizeBytes,sha256.lowercase(),chunkSize,count)
    }
    fun verifyChunk(index:Int,bytes:ByteArray,m:Manifest):Boolean {
        if(index !in 0 until m.chunkCount) return false
        val expected=if(index==m.chunkCount-1) (m.sizeBytes-index.toLong()*m.chunkSize).toInt() else m.chunkSize
        return bytes.size==expected
    }
    fun verifyWholeFile(bytes:ByteArray,m:Manifest):Boolean =
        bytes.size.toLong()==m.sizeBytes && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }==m.sha256
}

/** Notification filter. It never reads notification contents; it classifies metadata only. */
object SmartNotificationPolicy {
    data class Rule(val packageName:String,val enabled:Boolean,val quietStartMinute:Int?=null,val quietEndMinute:Int?=null)
    fun shouldNotify(rule:Rule?,minuteOfDay:Int,highPriority:Boolean=false):Boolean {
        if(rule==null || !rule.enabled || minuteOfDay !in 0..1439) return false
        if(highPriority) return true
        val start=rule.quietStartMinute ?: return true
        val end=rule.quietEndMinute ?: return true
        if(start !in 0..1439 || end !in 0..1439 || start==end) return true
        val quiet=if(start<end) minuteOfDay in start until end else minuteOfDay>=start || minuteOfDay<end
        return !quiet
    }
}

/** User-controlled life timeline. Events are added only through explicit calls. */
class LifeTimeline(context: Context? = null) {
    data class Event(val id:String,val title:String,val category:String,val timestamp:Long,val note:String="")
    private val events=linkedMapOf<String,Event>()
    private val persistent = context?.let(::PersonalKnowledgeStore)

    @Synchronized fun add(event:Event) {
        require(event.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && event.title.isNotBlank() && event.category.isNotBlank())
        val safe=event.copy(title=event.title.trim().take(120),category=event.category.trim().take(40),note=event.note.take(1000))
        persistent?.addTimelineEvent(PersonalKnowledgeStore.TimelineEvent(safe.id,safe.title,safe.category,safe.timestamp,safe.note))?.getOrThrow()
        events[event.id]=safe
    }
    @Synchronized fun search(query:String):List<Event> {
        val saved=persistent?.searchTimeline(query)?.getOrThrow()?.map { Event(it.id,it.title,it.category,it.timestamp,it.note) }
        return (saved ?: events.values.filter { it.title.contains(query,true)||it.note.contains(query,true)||it.category.contains(query,true) }.sortedByDescending { it.timestamp })
    }
    @Synchronized fun between(from:Long,to:Long):List<Event> {
        require(from<=to)
        val saved=persistent?.searchTimeline("",from,to)?.getOrThrow()?.map { Event(it.id,it.title,it.category,it.timestamp,it.note) }
        return saved ?: events.values.filter { it.timestamp in from..to }.sortedBy { it.timestamp }
    }
    @Synchronized fun delete(id:String) {
        persistent?.deleteTimelineEvent(id)?.getOrThrow()
        events.remove(id)
    }
}

/** Personal skill learning is opt-in and records explicit user-approved examples only. */
class PersonalSkillLearning(context: Context? = null) {
    data class Example(val skill:String,val input:String,val approvedOutput:String,val approvedAt:Long)
    private val examples=mutableListOf<Example>()
    private val persistent = context?.let(::PersonalKnowledgeStore)

    @Synchronized fun add(consent:Boolean,skill:String,input:String,approvedOutput:String,now:Long=System.currentTimeMillis()) {
        require(consent) { "Explicit learning consent required" }
        require(skill.isNotBlank() && input.isNotBlank() && approvedOutput.isNotBlank())
        val safe=Example(skill.trim().take(60),input.take(2000),approvedOutput.take(4000),now)
        if(persistent!=null) {
            persistent.addApprovedSkillExample(true,PersonalKnowledgeStore.SkillExample(
                UUID.randomUUID().toString().replace("-",""),safe.skill,safe.input,safe.approvedOutput,safe.approvedAt
            )).getOrThrow()
        } else {
            require(examples.size<5000)
            examples.add(safe)
        }
    }
    @Synchronized fun examplesFor(skill:String):List<Example> {
        val saved=persistent?.skillExamples(skill)?.getOrThrow()?.map { Example(it.skill,it.input,it.approvedOutput,it.approvedAt) }
        return saved ?: examples.filter { it.skill.equals(skill,true) }.sortedByDescending { it.approvedAt }
    }
    @Synchronized fun forget(skill:String) {
        if(persistent!=null) persistent.forgetSkill(skill).getOrThrow()
        else examples.removeAll { it.skill.equals(skill,true) }
    }
}

/** Routine rules are proposals; sensitive actions always require a fresh user approval. */
object SmartRoutineEngine {
    data class Routine(val id:String,val title:String,val trigger:String,val actionSummary:String,val enabled:Boolean,val requiresApproval:Boolean=true)
    data class Proposal(val routineId:String,val summary:String,val requiresApproval:Boolean)
    fun propose(routine:Routine,triggerObserved:String):Proposal? {
        if(!routine.enabled || routine.trigger.isBlank() || !routine.trigger.equals(triggerObserved,true)) return null
        return Proposal(routine.id,routine.actionSummary,routine.requiresApproval)
    }
}
