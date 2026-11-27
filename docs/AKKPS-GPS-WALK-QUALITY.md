# AKKPS GPS walk capture quality gate

The Runner accepts a point only when Android reports horizontal accuracy of 15 metres or better. It ignores points less than 2 metres from the last accepted point and rejects implausible jumps above 5.5 metres/second. It displays current and best accepted accuracy. These are capture safeguards, not a survey-grade accuracy guarantee; the user must visually review the polygon and may need to repeat a segment when the GPS signal is poor.
