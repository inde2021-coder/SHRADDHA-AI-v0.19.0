# Expert Course, Repair & Creator Studio — v0.7.0

## Domains
- Electronics engineering
- Electrical engineering
- Mechanical engineering
- Medical and health education
- Agriculture and farming
- Civil engineering
- Law and legal studies
- Repair and troubleshooting
- Creator studio
- General science and technology

## Modes
- Structured course / learn
- Problem solver
- Conceptual project / design creator
- Repair / fault diagnosis
- Exam preparation
- Content creator (scripts, hooks, captions, titles, storyboards, descriptions and calendars)

## API
`POST /api/expert/assist`

Example body:
```json
{
  "provider": "gemini",
  "domain": "mechanical",
  "mode": "learn",
  "question": "Teach me the basics of a centrifugal pump with a worked example."
}
```

Allowed domain keys: `electronics`, `electrical`, `mechanical`, `medical`, `agriculture`, `civil`, `law`, `repair`, `creator`, `general`.

Allowed mode keys: `learn`, `solve`, `design`, `diagnose`, `exam`, `create`.

## Engineering Workbench

The Android Expert Studio links to a local Engineering Workbench with first-order Ohm's law, LED series-resistor and unloaded resistive voltage-divider calculations. It can export the entered worksheet and calculated result as CSV, generate a reviewable SPICE-compatible netlist for a simple series-LED circuit for use in an external simulator, and export a starter bill of materials (BOM) as CSV. The app does not run SPICE internally and does not provide PCB routing, thermal analysis, structural simulation, or engineering certification. Verify component ratings, tolerances, load effects, wiring, protection and applicable standards before construction.

## Safety boundaries
- Medical: educational content only; no diagnosis or prescription.
- Law: general legal information; jurisdiction-specific lawyer review for actual cases.
- Electrical/electronics: no unsafe live mains instructions or bypassing protective devices.
- Mechanical/repair: isolate power/pressure/fuel; escalate high-risk work to qualified technicians.
- Civil: conceptual assistance only; no structural certification or construction-ready approval.
- Agriculture: pesticide use must follow product labels and local extension guidance; never recommend unsafe mixing.
- Creator: do not fabricate factual claims or copy a living creator's distinctive style exactly.

The assistant does not inspect physical systems, certify designs, execute repairs, or guarantee regulatory compliance. Verify outputs against current standards, labels, laws, and qualified professionals.
