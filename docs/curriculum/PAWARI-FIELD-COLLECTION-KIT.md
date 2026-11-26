# Shraddha AI — Pawari/Powari field collection kit

**Scope confirmed by the user:** Balaghat and Seoni (Madhya Pradesh), Gondia and Bhandara (Maharashtra).

## District profiles

| Profile ID | State | District | Current status |
|---|---|---|---|
| `pawari-balaghat-mp` | Madhya Pradesh | Balaghat | Awaiting community speakers and validation |
| `pawari-seoni-mp` | Madhya Pradesh | Seoni | Awaiting community speakers and validation |
| `powari-gondia-mh` | Maharashtra | Gondia | Awaiting community speakers and validation |
| `powari-bhandara-mh` | Maharashtra | Bhandara | Awaiting community speakers and validation |

These are operational collection labels, not a claim that each district has one uniform or formally classified dialect. Let speakers name their own variety. Preserve intra-district differences.

## Collection rules

1. Recruit consenting adult fluent speakers from each district. Include multiple localities and age bands where feasible; do not let one speaker represent a whole district.
2. Record the speaker's own name for their speech variety and preferred script. Do not force Devanagari or standard Hindi spelling if the speaker prefers another representation.
3. Collect read prompts, natural conversation, question/answer, numbers, names/places, everyday instructions and code-switching. Keep the utterance type labelled.
4. Never normalize local vocabulary into Hindi. Store original transcript and Hindi/English gloss in separate fields.
5. Each recording must have a random record ID. Do not put a person's name, phone number, Aadhaar, or precise home address in filenames or record IDs.
6. Obtain separate explicit consent for transcription, research, public release, commercial model training and voice synthesis. Each permission defaults to **NO**. Voice synthesis is not implied by permission to record speech.
7. Keep raw recordings access-controlled. Public release and model training are blocked until rights are documented and a responsible community reviewer approves the release.
8. Two independent fluent local reviewers must review every transcript. Record disagreement; do not silently choose a spelling or pronunciation.
9. Keep speaker-disjoint train/dev/test splits. A speaker must never appear in more than one split.
10. Provide a withdrawal route. Honour takedown requests for data where legally and technically possible, and document model/data limitations.

## Audio capture guidance

- Preferred master: WAV, PCM, mono, 16-bit or better, 16 kHz or higher.
- Record in a quiet room; keep microphone distance consistent; avoid aggressive noise suppression.
- Keep original unprocessed master. If a cleaned derivative is made, retain both and record the processing steps.
- Record clipping, background noise, device and sample-rate metadata. Do not discard dialectal pauses or pronunciation as "errors".
- Obtain a separate consent decision before recording any child; this initial collection kit is adult-speaker only.

## Suggested starter prompt categories

Create locally reviewed prompts rather than translating a Hindi list mechanically:
- Greetings and introductions
- Family and relationships
- Farming, seasons, crops and animals
- Markets, prices, weights and counting
- Travel, directions and place names
- Household objects, food and cooking
- Health and emergency phrases (reviewed carefully; not medical advice)
- School, work and daily routines
- Questions, requests, negation, tense and plural forms
- Natural code-switching with Marathi/Hindi and neighbouring varieties

Do not publish these categories as Pawari translations. They are elicitation topics only.

## CSV schema

Use `PAWARI-FIELD-COLLECTION-TEMPLATE.csv`. One row represents one utterance. The `profile_id` must be one of the four IDs above. Consent columns must contain `YES`, `NO` or `WITHDRAWN`; blank consent is treated as NO. Do not ingest records into training unless the exact intended use has affirmative consent and both reviewer statuses are APPROVED.

## Release gates

A district profile remains **COLLECTION / NOT MODEL-VERIFIED** until:
- local speakers confirm the variety label and the profile boundaries;
- data provenance and licence/consent cover the exact use;
- two reviewers approve transcripts and documented variants;
- speaker-disjoint splits and deduplication are complete;
- ASR/TTS/translation evaluation is run on held-out local data;
- community reviewers approve the specific dataset and model version.

No Pawari ASR, TTS or translation capability should be advertised as verified before these gates pass.
