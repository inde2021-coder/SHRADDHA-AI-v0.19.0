# AKKPS form fill and submit safety

This change tightens the existing Runner form mapper:
- only one visible form is accepted for a fill operation;
- fields must be visible, enabled and matched by exact normalized label/name/id/placeholder/ARIA text;
- fuzzy partial matches are not used;
- ambiguous fields, duplicate targets, missing select options and invalid checkbox values stop the operation;
- required visible fields are checked before submit;
- the app presents a final review/confirmation before sending a record.

Password, hidden and file inputs are excluded from automated filling. The app does not claim that a submit request means a record was saved; the existing post-submit message check remains a limited confirmation and the actual record should be checked in AKKPS.

This is an implementation slice, not a claim of verified submission against a live AKKPS account.
