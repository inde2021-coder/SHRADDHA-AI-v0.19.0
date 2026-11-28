# AKKPS screenshot-driven field coverage

The implementation uses the existing screenshots in `main/akkps-screens/`; no second upload from the account owner is required.

## Screens visible in the repository references

- Runner home and recent activity
- Farmers list and New Farmer form
- Farmer identity, address, Aadhaar/document, linked-plot and project-consent sections
- Plots list and New Plot form
- Plot survey/village/remarks fields, AWD baseline practices, rice variety, crop duration, historical yields, boundary KML and linked farmers
- Create Boundary screen with Draw and GPS Walk modes
- AWD Reports list and Edit AWD Report form

## Field labels mapped to canonical command keys

### Farmer
Farmer Name, Father's Name, Mother's Name, Phone Number, Farmer Gender, Identity Type, Aadhaar Number, Address, Signer (farmer), Signed date, Witness, Remarks.

### Plot
Survey Number, Plot Number, Village / Block Number, Remarks, Is the baseline practice continuous flooding?, Previous Irrigation Method, Pre-season Water Condition, Baseline Emission Factor Source, Typical Water Depth (cm), Irrigations / Season, Rice Variety, Crop Duration (days), Year, Season, Yield (qtl/acre).

### AWD report
Year, Season, Crop Establishment Method, Total Land Area (acres), Paddy Area Covered (acres), Stratum Water Regime, Rice Variety, Sowing Date, Planting Date, Harvest Date, Actual Yield (qtl/acre), Crop Loss Notes, First Irrigation Date, Drying Event Dates, Re-flooding Dates, AWD Cycles, Max Dry-down, Field Water Tube Used, Water Level at Each Visit, Soil Type, Soil pH, Soil Organic Carbon, Drainage, Field is Leveled, Organic Amendment Type/Quantity/Date, Straw Management/Quantity/Date, Urea (NPK), Fertilizer Application Dates, Irrigation Source, Pump Type, Irrigation Decision Maker and Gender, Water Source Shared, Pumping Hours, Electricity (kWh), Farmer Adoption Confirmed, Sensor/IoT Reference, Field Photos, Field Visits/Audit Logs, Verifier Notes, Cultivation Cost, Water Saved, Male/Female/Other Gender Laborers, Total Laborers and Notes.

## Automation contract

- Ordinary text/date/number fields require one exact visible match.
- Select/radio choices require an exact visible option match.
- Checkbox/toggle changes require an explicit Hindi/English yes/no value; required unchecked toggles are included in missing-field checks.
- Password, OTP, CAPTCHA, hidden and file inputs are never auto-filled.
- Aadhaar, signatures, agreement/land-deed images and field photos remain user-selected through the website's own file picker.
- Autocomplete selections, nested repeaters (drying/re-flooding dates, fertilizer rows, historical yield rows, field visits and water readings), and map boundary actions remain manual until their page-specific behavior is observed and implemented. The AI mapper must report these as missing/manual steps rather than claiming success.
- Submit is separate from field fill and requires explicit confirmation. Server success requires a same-origin write response with an explicit success indicator and record ID; uncertain writes are never automatically retried.

The screenshots establish labels and layout, not current login permissions, live API behavior or server-side persistence. Those still require an authorized account session.
