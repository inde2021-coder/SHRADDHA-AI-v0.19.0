# AKKPS server-side submit verification

The Runner observes same-origin Fetch/XHR write requests made after the user's explicit Submit confirmation. A response is eligible as a server-side record confirmation only when:

- the HTTP method is POST, PUT or PATCH;
- the request path identifies a Farmer, Plot, AWD Report, record or boundary write endpoint;
- the response is 2xx and carries an explicit success indicator; and
- the response includes a record identifier (recordId, plotId or reportId).

Unrelated telemetry, page navigation, a generic success toast, or an HTTP 200 without a record ID is not presented as fully verified. If AKKPS changes its API route or response shape, the app must report that verification is inconclusive and the user must check the Plot/AWD listing. The client does not fabricate a record ID or retry a potentially successful submission automatically.
