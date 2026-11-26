# Bhagavad Gita — 700-verse scholarly verification audit

**Status: STRUCTURAL CHECKS PASSED; SCHOLARLY VERIFICATION NOT YET COMPLETED.**
**Do not label the corpus scholar-verified, certified or error-free.** The 700 records are present and internally sequential, but a qualified Sanskrit scholar must still check every verse against named print editions and document each decision.

## 1. Corpus structure checked

The 18 chapter JSON assets were read directly from the Shraddha branch. The following machine checks passed:

- Exactly 18 chapter files parse as JSON.
- The chapter verse counts match the 700-verse scheme: 47, 72, 43, 42, 29, 47, 30, 28, 34, 42, 55, 20, 34, 27, 20, 24, 28, 78.
- Total: **700 verse records**.
- Each chapter's internal app references run sequentially from chapter.1 to the chapter's declared final verse.
- No duplicate app verse references were found.
- Sanskrit, transliteration, word-gloss and speaker fields are populated for every record.
- Declared per-chapter speaker totals match the speaker values in that chapter.
- Chapter 13 preserves the Arjuna opening question as a separately labelled edition variant rather than silently dropping it.
- Chapter 18 verse 78's single-danda closing marker is preserved as a source-format variant.

These checks establish data shape and consistency only. They do not establish that every Sanskrit akṣara, sandhi, pada, accent, transliteration, speaker attribution or gloss is correct.

## 2. Mandatory scholarly issues to resolve

### A. Speaker attribution discrepancy — Chapter 1, verse 1.28

The current corpus aggregates to Krishna 574, Arjuna 85, Sanjaya 40 and Dhritarashtra 1. Bal Gangadhar Tilak's published speaker-count table reports Krishna 574, Arjuna 84, Sanjaya 41 and Dhritarashtra 1. The one-verse difference is an explicit review item, not something to auto-correct. Review Chapter 1 verse 1.28 against the selected printed edition and document whether the mixed narration/quoted speech is attributed to Sanjaya or Arjuna in that edition.

### B. Chapter 13 numbering and textual variant

The selected app edition has 34 numbered verses. It retains Arjuna's question (commonly shown as 13.1 in 35-verse/701-numbering witnesses) in `editionVariants`; the app's Krishna verse 13.1 has source number 13.2. Keep both numbering systems visible in scholarly notes. Do not delete the opening question or silently shift references.

### C. Chapter 18 verse 78 closing marker

The source transcription ends the final marker as `।।18.78।` (single final danda), unlike the common double-danda close elsewhere. A scholar should compare the exact witness and decide whether this is an accepted typographic convention or a transcription error. Do not normalize it without a source note.

### D. Text, transliteration and word gloss

All current glosses/transliterations are source-provided and still need review. Check each verse independently for:
- Devanagari letters, anusvāra/visarga, conjuncts, punctuation and pāṭha variants;
- sandhi and padaccheda, including compound segmentation;
- transliteration scheme consistency (the present source field is not a guaranteed scholarly IAST edition);
- word-by-word Sanskrit morphology and meaning;
- speaker attribution and verse boundaries;
- interpretive statements kept separate from literal gloss, with sampradāya/edition attribution where relevant.

## 3. Source witnesses and rights

1. **Current data source:** `gita/gita`, `data/verse.json`, declared Unlicense in the repository metadata. This is the current machine-readable source, not an independent scholar sign-off.
2. **Independent comparison candidate:** Hansi Gita Bhawan Trust, *The Bhagavad Gita: 700 verses*. Its research page says the Sanskrit transcription was compared verse by verse against published editions, with a third consulted for disagreements. Use it as an independent witness and cite the Trust; do not copy its separately copyrighted English/Hindi translations into Shraddha without permission.
3. **Additional reference:** IIT Kanpur's Gita Supersite is an established multi-commentary reference portal. Select and record the exact Sanskrit edition/commentary used for each comparison; the portal itself is not a single critical edition.
4. **Speaker-count comparison:** Bal Gangadhar Tilak, *Śrīmad Bhagavadgītā Rahasya*, published table of verse counts attributed to the characters. Record edition/page in the scholar's review notes.

Online links:
- https://github.com/gita/gita/blob/main/data/verse.json
- https://gitabhawan.org.in/gita/research
- https://heritage.iitk.ac.in/gitasupersite
- https://www.bhagavadgitam.com/comm/Bhagavadgita-by-Tilak.pdf

## 4. Per-verse review workflow

The companion file `GITA-700-VERSE-REVIEW-QUEUE.csv` contains exactly 700 rows, one for each app verse reference. A qualified Sanskrit reviewer must complete the fields for text, speaker, transliteration, padaccheda/grammar, word gloss and edition variant, then add reviewer ID, date and notes.

For each reviewer:
1. Record name/approved reviewer ID, qualifications, institution (if attribution permitted), review date and chosen printed edition(s).
2. Compare each verse with the primary chosen print witness; use an independent witness for every disputed reading.
3. Mark each field PASS, CORRECTION_REQUIRED or VARIANT_ACCEPTED; never leave a disputed item as PASS without a note.
4. Record exact source page/edition and correction evidence.
5. Re-run the corpus structural test after corrections.
6. A second reviewer checks all corrections and all unresolved variants before release.
7. Sign off the exact content version/commit. Any later text change invalidates that sign-off for the changed verse.

## 5. Release gate

- [ ] Sanskrit scholar appointed and qualifications verified.
- [ ] Named base edition and independent comparison edition recorded.
- [ ] All 700 rows reviewed.
- [ ] Chapter 1 verse 1.28 speaker discrepancy resolved with cited edition.
- [ ] Chapter 13 700/701 numbering policy approved and displayed.
- [ ] Chapter 18 verse 18.78 closing marker resolved.
- [ ] Transliteration and word-gloss review complete.
- [ ] Corrections independently checked.
- [ ] Exact commit signed off by the scholar.

Until every applicable item is complete, production status remains **PENDING SCHOLAR REVIEW**.
