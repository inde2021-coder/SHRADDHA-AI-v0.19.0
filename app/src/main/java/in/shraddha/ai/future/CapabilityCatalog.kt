package `in`.shraddha.ai.future

/** Honest capability map. State reflects shipped local code vs required integrations. */
enum class CapabilityState { AVAILABLE, PERMISSION_REQUIRED, COMPANION_REQUIRED, CONFIGURATION_REQUIRED, ROADMAP }
data class Capability(val id:String, val group:String, val title:String, val detail:String, val state:CapabilityState)
object CapabilityCatalog {
    val all = listOf(
        Capability("app_learning","Automation","Per-app Training Profiles","User-taught semantic anchors and named workflows saved by app package.",CapabilityState.AVAILABLE),
        Capability("semantic_ui","Automation","Semantic UI Understanding","Resource ID, text, hint and content-description matching; optional local Hindi/English ML Kit OCR can read custom UI text on Android 11+, but cannot yet click OCR-only canvas targets.",CapabilityState.PERMISSION_REQUIRED),
        Capability("adaptive_workflow","Automation","Adaptive Workflow Execution","Re-observes each screen and verifies every learned step; encrypted checkpoints allow explicit resume only after verified boundaries. Requires Accessibility and target-app cooperation.",CapabilityState.PERMISSION_REQUIRED),
        Capability("file_locator","Automation","Authorized Device File Locator","Exact-name search in MediaStore and user-granted folder trees; Android scoped storage respected.",CapabilityState.PERMISSION_REQUIRED),
        Capability("voice_operator","Automation","Voice-to-Workflow Routing","Hindi voice command routes to a saved workflow; speech recognition and prior training required.",CapabilityState.PERMISSION_REQUIRED),
        Capability("operator_audit","Security","Operator Audit Journal","Metadata-only action outcomes without screen images or secrets.",CapabilityState.AVAILABLE),
        Capability("swarm","Intelligence","Multi-agent council / AI swarm","Live 5-role council via 2–5 configured providers; local planner also available.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("twin","Intelligence","Personal Digital Twin","User-authored editable preferences saved locally; no hidden inference.",CapabilityState.AVAILABLE),
        Capability("predict","Intelligence","Predictive intelligence","Opt-in suggestions from manually entered activity only; no background tracking.",CapabilityState.AVAILABLE),
        Capability("offline","Intelligence","Hybrid offline AI runtime","SHA-256 model integrity helper exists; native model inference runtime and device benchmark still required.",CapabilityState.ROADMAP),
        Capability("shraddha_voice","Voice","Shraddha custom bilingual voice","Gemini text responses are routed to a self-hosted non-Google TTS engine; licensed matching Hindi and English voice models and backend endpoint are required.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("device","Devices","Universal device control","Paired device relay and scoped remote-control approval; target OS permissions remain mandatory.",CapabilityState.PERMISSION_REQUIRED),
        Capability("handoff","Devices","Cross-device task transfer","Secure paired-device messaging exists; resumable task-state synchronization is not complete.",CapabilityState.COMPANION_REQUIRED),
        Capability("screen","Vision","Live screen understanding","Screen share and AI screenshot analysis require user-started MediaProjection and provider configuration.",CapabilityState.PERMISSION_REQUIRED),
        Capability("voice_action","Automation","Voice-to-action automation","Speech recognition and limited command parser; full natural-language action planner requires App Operator permission.",CapabilityState.PERMISSION_REQUIRED),
        Capability("iot","Devices","Smart home and IoT control","Device-specific local network connectors and manual override required.",CapabilityState.ROADMAP),
        Capability("health","Devices","Device health monitor","On-demand battery, storage and Android build snapshot.",CapabilityState.AVAILABLE),
        Capability("face","Vision","Consent-based face recognition","No biometric face matching model shipped; consent, anti-spoofing and on-device validation required.",CapabilityState.ROADMAP),
        Capability("speaker","Vision","Consent-based voice identity","No speaker-embedding model shipped; anti-spoofing and accuracy validation required.",CapabilityState.ROADMAP),
        Capability("object","Vision","Object recognition","General visual Q&A via configured vision provider; dedicated on-device object detector not shipped.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("scene","Vision","Scene understanding","User-shared camera/screen images can be analyzed by a configured vision provider.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("visual_memory","Memory","Visual memory","Person directory and text notes available; biometric visual re-identification not enabled.",CapabilityState.ROADMAP),
        Capability("translation","Vision","Live translation lens","Text translation through AI provider; continuous camera overlay pipeline not complete.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("electrical","Engineering","Electrical and electronics lab","Ohm's law, power, resistor-network and voltage-drop calculations; not electrical certification.",CapabilityState.AVAILABLE),
        Capability("mechanical","Engineering","Mechanical engineering lab","Torque calculator and conceptual engineering assistance; CAD/simulation integration required.",CapabilityState.AVAILABLE),
        Capability("civil","Engineering","Civil and architecture lab","Concrete volume estimate; structural design and BIM/CAD engine not included.",CapabilityState.AVAILABLE),
        Capability("medical","Expert Studio","Medical information assistant","Information support only; no diagnosis, prescription or emergency monitoring.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("agri","Expert Studio","Agriculture lab","Crop/soil advisory; location, current agronomy data and provider setup required.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("repair","Expert Studio","Repair expert","Symptom-led safe troubleshooting through configured provider; physical inspection not claimed.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("law","Expert Studio","Legal research lab","Legal information and document assistance; jurisdiction-specific verified sources required.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("appbuilder","Creator","Website and app builder","Project briefs and implementation plans; code generation/build/deploy pipeline not fully integrated.",CapabilityState.ROADMAP),
        Capability("video","Creator","AI video studio","Storyboard and script outlines; rendered video generation/export pipeline not shipped.",CapabilityState.ROADMAP),
        Capability("3d","Creator","3D design studio","3D design brief templates; CAD geometry engine and export pipeline not shipped.",CapabilityState.ROADMAP),
        Capability("business","Business","Business manager","Can be built through AI-assisted documents; dedicated ERP modules are not part of Shraddha core.",CapabilityState.ROADMAP),
        Capability("docs","Creator","Document intelligence","Text drafting and provider-assisted document analysis; PDF/Office editing pipeline requires integration.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("research","Intelligence","Research studio","Provider-backed research assistance; citations need source retrieval and independent verification.",CapabilityState.CONFIGURATION_REQUIRED),
        Capability("testing","Engineering","Automated app testing lab","Test-plan creation available through AI; device farm and automated test execution not shipped.",CapabilityState.ROADMAP),
        Capability("guardian","Security","Permission guardian","Local audit trail and per-session approval; Android OS permission screens remain authoritative.",CapabilityState.AVAILABLE),
        Capability("lock","Security","Emergency lock","Manual disconnect/revoke controls exist in device link; global remote kill-switch across all companions is not complete.",CapabilityState.COMPANION_REQUIRED),
        Capability("backup","Security","Encrypted backup and recovery","User-triggered PBKDF2 + AES-256-GCM passphrase-protected export/restore for selected local notes, knowledge graph, project and digital twin data; full vault and cloud export remain excluded.",CapabilityState.AVAILABLE),
        Capability("plugins","Platform","Plugin sandbox and registry","Plugin manifest and scope validation; signed package verification and sandbox execution not shipped.",CapabilityState.AVAILABLE),
        Capability("healing","Platform","Self-healing and recovery","Error reporting and recovery guidance; autonomous code modification is not enabled.",CapabilityState.ROADMAP),
        Capability("scheduler","Automation","Task scheduler and reminders","User-created one-shot local reminders using Android AlarmManager; Android may delay delivery.",CapabilityState.AVAILABLE)
    )
}
