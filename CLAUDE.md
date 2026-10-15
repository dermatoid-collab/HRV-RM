# HRV-RM — notes for future sessions

## Pending: translate ASI Italian strings to English (approved 2026-10-15, not yet implemented)

The user approved translating every Italian string in the ASI module to English. This is a
memo for a *future* build — no code has been changed yet. When implementing, update the string
tables below plus the tests that assert on these exact strings (`AsiEngineTest.kt`,
`AsiInterpretationTest.kt`).

### `AsiStatus` — label (`asi/AsiModels.kt`)
| Italian | English |
|---|---|
| Regolare | Normal |
| Lievi scostamenti | Mild deviation |
| Attenzione | Caution |
| Scostamenti marcati | Marked deviation |
| Calibrazione | Calibrating |
| Dati insufficienti | Insufficient data |
| Dati non recenti | Stale data |

### `AsiStatus` — baseComment (currently unused in UI, but part of the model)
| Italian | English |
|---|---|
| Profilo autonomico complessivamente regolare. | Overall normal autonomic profile. |
| Lievi scostamenti dal profilo abituale. | Mild deviations from the usual profile. |
| Scostamenti autonomici che richiedono attenzione. | Autonomic deviations that call for attention. |
| Scostamenti autonomici marcati. | Marked autonomic deviations. |
| Calibrazione in corso: storico insufficiente. | Calibrating: insufficient history. |
| Dati insufficienti per calcolare ASI. | Insufficient data to compute ASI. |
| Ultime sette letture non sufficientemente recenti. | The last seven readings aren't recent enough. |

### `AsiCombination` — comment (10 fixed combinations, `asi/AsiModels.kt`)
| Italian | English |
|---|---|
| HRV7 ridotta e RHR7 elevata: segnali concordanti. | Reduced HRV7 and elevated RHR7: concordant signals. |
| Riduzione isolata dell'HRV odierna. | Isolated reduction in today's HRV. |
| HRV7 ridotta e CV7 basso: possibile soppressione stabile. | Reduced HRV7 and low CV7: possible stable suppression. |
| RHR odierna elevata senza corrispondente riduzione HRV. | Elevated today's RHR without a corresponding HRV reduction. |
| HRV odierna nella fascia abituale, ma HRV7 ancora ridotta. | Today's HRV within the usual range, but HRV7 still reduced. |
| Indicatori complessivamente coerenti con lo storico. | Indicators overall consistent with history. |
| Risposta autonomica insolita: non equivale automaticamente a recupero superiore. | Unusual autonomic response: not automatically superior recovery. |
| CV7 molto elevato senza riduzione consolidata di HRV7. | Very high CV7 without a consolidated HRV7 reduction. |
| RHR7 persistentemente elevata con HRV7 non ridotta. | Persistently elevated RHR7 with HRV7 not reduced. |
| HRV odierna ridotta e RHR odierna elevata. | Today's HRV reduced and today's RHR elevated. |

### `AsiParameterKey` — label (`asi/AsiModels.kt`)
| Italian | English |
|---|---|
| HRV oggi | HRV today |
| HRV7 | HRV7 (unchanged) |
| CV7 | CV7 (unchanged) |
| RHR oggi | RHR today |
| RHR7 | RHR7 (unchanged) |

### `asiParameterStatusLabel()` (`ui/asi/AsiIcons.kt`)
| Italian | English |
|---|---|
| Bassa | Low |
| Normale | Normal |
| Alta | High |
| Molto alta | Very high |
| Insolitamente alta | Unusually high |
| Insolitamente bassa | Unusually low |
| Assente | Missing |
| Storico insufficiente | Insufficient history |
| Non recente | Not recent |
| Non disponibile | Not available |

### `asiExceptionLabel()` (`ui/asi/AsiIcons.kt`)
Reuses COMBO_07's text verbatim — translate it the same way as COMBO_07 above.

### `asiConfidenceLabel()` (`ui/asi/AsiIcons.kt`)
| Italian | English |
|---|---|
| Alta | High |
| Moderata | Moderate |
| Bassa | Low |
| Non disponibile | Not available |

### Already English (no change needed)
`"Confidence: "` prefix in `ui/asi/AsiComponents.kt`.

### Implementation notes
- KDoc comments referencing spec section numbers (in Italian) are not part of this approval —
  leave them as-is unless asked separately.
- `AsiConfig.VERSION = "hrvrm-asi-1.0"` is not user-visible text, no change.
- Never commit/push without an explicit instruction in the session, per this project's
  established workflow.
