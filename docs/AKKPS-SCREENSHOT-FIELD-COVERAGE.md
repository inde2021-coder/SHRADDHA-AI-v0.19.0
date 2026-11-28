# AKKPS screenshot-driven field coverage

The implementation uses the existing screenshots in `main/akkps-screens/`; no second upload from the account owner is required.

## Screens visible in the repository references

- Runner home and recent activity
- Farmers list and New Farmer form
- Farmer identity, address, Aadhaar/document, linked-plot and project-consent sections
- Plots list and New Plot form
- Plot survey/village/remarks fields, AWD baseline practices, rice variety, crop duration, historical yields, boundary KML and linked farmers
- Create Boundary screen with Draw and GPS Walk modes, satellite/terrain layer selection, draggable boundary points, area preview and KML save/upload controls
- AWD Reports list and Edit AWD Report form

## Field labels mapped to canonical command keys

### Farmer
Farmer Name, Father's Name, Mother's Name, Phone Number, Farmer Gender, Identity Type, Aadhaar Number, Address, Signer (farmer), Signed date, Witness, Remarks.

### Plot
Survey Number, Plot Number, Village / Block Number, Remarks, Is the baseline practice continuous flooding?, Previous Irrigation Method, Pre-season Water Condition, Baseline Emission Factor Source, Typical Water Depth (cm), Irrigations / Season, Rice Variety, Crop Duration (days), Year, Season, Yield (qtl/acre).

### AWD report
Year, Season, Crop Establishment Method, Total Land Area (acres), Paddy Area Covered (acres), Stratum Water Regime, Rice Variety, Sowing Date, Planting Date, Harvest Date, Actual Yield (qtl/acre), Crop Loss Notes, First Irrigation Date, Drying Event Dates, Re-flooding Dates, AWD Cycles, Max Dry-down, Field Water Tube Used, Water Level at Each Visit, Soil Type, Soil pH, Soil Organic Carbon, Drainage, Field is Leveled, Organic Amendment Type/Quantity/Date, Straw Management/Quantity/Date, Urea (NPK), Fertilizer Application Dates, Irrigation Source, Pump Type, Who Decides When to Irrigate, Irrigation Decision Maker Gender, Water Source Shared with Other Farmers, Pumping Hours / Season, Electricity (kWh), Farmer Adoption Confirmed, Sensor / IoT Reference, Field Photos, Field Visits / Audit Logs (Visit Date, Visitor, Notes), Verifier Notes, Cultivation Cost (INR), Water Saved (%), Male/Female/Other Gender Laborers, Total Laborers and Notes. AWD Audit also includes the Inlet/Outlet micro-irrigation, change-to-intermittent-flooding and farmer-water-control yes/no questions.

## Automation contract

- Ordinary text/date/number fields require one exact visible match.
- Select/radio choices require an exact visible option match.
- Checkbox/toggle changes require an explicit Hindi/English yes/no value; required unchecked toggles are included in missing-field checks.
- Password, OTP, CAPTCHA, hidden and file inputs are never auto-filled.
- Aadhaar, signatures, agreement/land-deed images and field photos remain user-selected through the website's own file picker.
- Android voice workflows now add recognized AWD repeater rows only within a unique section, verify the new row, and route explicitly supplied values only to that newest row. Voice autocomplete supports exact user-named village, rice-variety, linked-farmer and linked-plot suggestions; missing/ambiguous options are not guessed. Voice document commands open the exact website file field's own picker; upload remains separate. Voice commands also set Plot ID, start/stop GPS Walk, import/export KML, import satellite candidates, save/reopen local boundaries, inspect the live map, prepare a KML handoff and verify a portal boundary response.
- The Windows AKKPS operator implements reviewed nested AWD repeater actions, exact autocomplete selection, document attachment/upload verification, exact-field KML attachment and a separately confirmed non-submit boundary Upload/Save action. Repeater Add controls are scoped to their unique visible section; the newest row is the only row eligible for the follow-up fill action.
- Android KML attachment is not itself a portal upload or save. The user must review the live map and use the website's own Upload/Save control; Android's observer can confirm only a matching same-origin 2xx response with explicit success and a record ID. Portal persistence still requires reopening the Plot map and visually confirming the polygon. A satellite-map screenshot is not proof of an automated satellite boundary-detection model.
- Submit is separate from field fill and requires explicit confirmation. Server success requires a same-origin write response with an explicit success indicator and record ID; uncertain writes are never automatically retried.

The screenshots establish labels and layout, not current login permissions, live API behavior or server-side persistence. Those still require an authorized account session.
