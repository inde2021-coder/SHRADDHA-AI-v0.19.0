# Phone call service lifecycle

Shraddha's optional default-dialer InCallService owns callbacks for live Telecom Call objects. When Android destroys the service, callbacks are unregistered and in-memory call state, mute state and available endpoints are cleared. The app broadcasts a final package-scoped state update so the Phone Agent UI does not keep showing a stale active call. Call history stores only a masked last-four identifier, not the full number.
