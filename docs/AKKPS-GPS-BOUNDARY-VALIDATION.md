# AKKPS GPS boundary geometry validation

The GPS walk workflow produces a polygon from edge points; it does not require the farmer to enter the crop rows. Before local save or AKKPS hand-off, the geometry layer validates coordinate ranges, point count, duplicate consecutive points, minimum area and self-crossing edges. Area is calculated from geographic coordinates and shown in square metres/acres.

## Important limits

- A valid polygon is not proof of cadastral correctness.
- GPS drift and poor satellite reception can move points; the UI must retain accuracy/timestamp metadata and let the farmer review/edit the polygon.
- The area calculation is a spherical approximation and is not a legal land survey.
- The KML/JSON round-trip tests validate coordinate ordering and persistence serialization only; they do not verify a live AKKPS save.
- Satellite-derived candidate boundaries must remain separately labelled as AI suggestions until validated against ground truth.

## Manual field workflow

1. Stand on accessible field edges; do not walk through standing crops.
2. Record points while walking the perimeter; pause when GPS accuracy is poor.
3. Review the closed polygon and area.
4. Save the boundary against the exact Plot ID.
5. Reopen it and compare the points/area before submitting.
