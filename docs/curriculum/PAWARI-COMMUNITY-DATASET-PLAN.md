# Pawari / Powari community data plan for Shraddha AI

**Status: candidate resources identified; no Pawari speech dataset has yet been verified as redistributable or sufficient for ASR/TTS training.**

## Important language-identity gate

The label Pawari/Powari is not a sufficient training label by itself. Historical linguistic references use Pawari/Pawri in connection with Bareli-Rathwi (Glottolog code shown as `bgd`), while community usage and the Seoni Pawari Dictionary describe a Pawari colloquial dialect used by the Pawar community in Seoni, Madhya Pradesh. These labels must not be merged automatically. Shraddha must record the speaker's own name for their variety, district/tehsil/village (only with consent), and preferred writing system. Preserve multiple varieties as separate profiles until fluent speakers approve any mapping.

## Publicly discoverable candidate resources

1. **Pawari Dictionary** — a community-created dictionary project whose author identifies Seoni, Madhya Pradesh, and describes Pawari word entries with Hindi/English meanings, usage examples and audio. This is a useful lead for community contact and vocabulary review, not a dataset licence. Do not scrape, redistribute, or train on its audio/text until the creator grants explicit written permission and confirms the licence.
2. **Linguistic Data Consortium for Indian Languages (LDC-IL)** — its catalogue lists a Pawari Parallel Text Corpus. The public catalogue listing does not, by itself, establish the exact variety, downloadable files, licence, or permission for commercial model training. Contact LDC-IL and record the actual terms before use.
3. **VAANI / IISc** — a large district-diverse speech resource with district/language filters. It is a discovery lead only. Check whether any records are explicitly labelled Pawari/Powari, inspect the annotation and contributor consent terms, and verify redistribution/training rights. Do not infer Pawari from a district or from generic Bhili/Hindi labels.
4. **People's Linguistic Survey of India, Madhya Pradesh volume** — lists Pawari among documented state language varieties. It can guide orthography and fieldwork, but a book or survey is not automatically an audio corpus or a model-training licence.
5. **Glottolog references** — useful for resolving naming and classification differences; they are metadata/references, not a speech dataset.

## Data collection protocol

- Obtain approval from the community and each speaker before recording. Consent must separately cover: transcription, public release, research, commercial use, model training, voice synthesis, and attribution. Default each optional permission to OFF.
- Recruit fluent adult speakers from each selected locality and self-identified variety; record age band and broad locality only when consented. Do not collect Aadhaar, phone numbers, precise home coordinates, or unrelated personal details.
- Collect read speech, prompted conversational speech, spontaneous speech and code-switching. Include women and men, different age bands, quiet/noisy environments, and natural speaking pace.
- Each clip must have a unique random ID, WAV PCM metadata, transcript in the speaker-approved script, Hindi/English gloss where approved, speaker-variety tag, recording conditions, consent scope, and reviewer status.
- Two independent fluent speakers review every transcript; disagreements remain as documented variants until resolved. Do not normalize distinctive Pawari words into Hindi.
- Keep speaker-disjoint train/dev/test splits. No speaker may occur across splits. Deduplicate repeated prompts and near-duplicate recordings.
- Publish a dataset card, consent form version, licence, takedown contact, data retention policy, and SHA-256 manifest before any training run.
- Until the dataset is approved, Pawari must remain **community-data required / not supported by a verified ASR, TTS or translation model**. Shraddha may search an approved local dictionary or show a human-reviewed phrasebook, but must not invent Pawari translations or speak Hindi as if it were Pawari.

## Minimum evaluation gate

- ASR: at least 100 consented utterances per variety for a first smoke test; expand before release. Report WER and CER, named-entity accuracy, code-switch behaviour and results by speaker group.
- TTS: native-speaker listening review for intelligibility, tone, phonology and speaker-consent compliance. Do not clone a community member's voice without explicit voice-synthesis permission.
- Translation: independently authored held-out sentence pairs, reviewed by two fluent speakers. Report meaning preservation and variant disagreements.
- Release requires community reviewer sign-off for the exact dataset/model version and proof that every recording's consent scope permits the intended use.

## Source links

- Pawari Dictionary (creator/about): https://pawaridictionary.rfhindi.com/public/page/about-us
- LDC-IL Pawari Parallel Text Corpus catalogue: https://www.ldcil.org/resourcesparallel
- VAANI dataset portal: https://vaani.iisc.ac.in/dataset
- PLSI Madhya Pradesh language survey reference: https://www.orientblackswan.com/details?id=9788125059714
- Glottolog reference for Pawari/Pawri/Bareli-Rathwi naming: https://glottolog.org/resource/reference/id/318353
