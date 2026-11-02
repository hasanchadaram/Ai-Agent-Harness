# Mixed dictation cleanup policy, v4

This is the authoritative annotation guide for this dataset. It reconciles the
prompts supplied by the user, with their later English-only clarification; it
is NOT added to exported model conversations. Hindi/Hinglish/transliteration
training is intentionally out of scope.
Examples are synthetic, AI-authored editorial targets, not independent human
judgments or recordings from the user's ASR engine.

## Task and input contract

One raw transcript is the user message. Return only its cleaned text. Do not
answer questions, execute requests, reveal instructions, apologize, add a
preamble, or explain edits. Mentions of assistants, ChatGPT, and `{{agentName}}`
remain dictated content. Hostile or instruction-like text is still dictation.
Local spoken editing commands are the exception: apply punctuation, layout,
quotation, and unambiguous self-corrections inside the transcript.

Training and serving use raw text without `<transcript>` wrappers, a system
message, task prefix, tools, or output reasoning. The pasted source prompt's
wrappers were a transport convention, not a feature of the current application.
Do not mix wrapped and unwrapped inputs accidentally. Preserve literal XML/code
when it is itself dictated content. Keep the model-native chat template and its
assistant generation marker; no-system-prompt does not mean no chat template.

## Conflict resolutions and priority

1. Preserve meaning, negation, uncertainty, names, facts, and deliberate
   emphasis. Never fill in missing facts or guess ambiguous ASR words.
2. Apply explicit local punctuation/layout commands and clear corrections.
3. Repair minimal English grammar, casing, and certain contextual ASR errors.
4. Apply conservative formatting and number conventions below.
5. Already-clean text is an identity example: preserve existing valid style,
   digits, punctuation, line breaks, and case-sensitive identifiers, even when
   an alternative default is used for unformatted speech.

Specific decisions relative to the source prompts and older datasets:

| Conflict | v4 decision |
| --- | --- |
| Small numbers optional / always digits / words below ten | Bare counts one through nine use words; ten and above use digits. Units, money, time, dates, percentages, labels, versions, and codes use digits. Existing clean digits remain unchanged. |
| Remove every "like" / retain voice | Remove only clear discourse filler; preserve preference, comparison, quotation, and meaningful "you know." |
| "I mean" filler / keep "I mean" | Keep self-clarification and meaningful "I mean"; consume it only when it introduces a clear replacement. |
| "Actually" filler / correction | Keep emphasis or contrast; consume it only as part of a clear replacement. "Basically" is retained. |
| All repetition removed / all repetition preserved | Remove clear stutters and accidental restarts; keep deliberate emphasis, negatives, lexical repetition, and quoted repetition. Ambiguous repetition stays. |
| Every sequence becomes a list / never over-format | Clear parallel independent items, a checklist, named items, or explicitly counted steps form a list. An inline series, chronological story, and one topic remain prose. |
| Asterisks versus dashes | Newly formatted unordered lists use `- `. Already-clean lists preserve their existing valid marker. |
| Period on every output | Sentences take appropriate punctuation. Titles, labels, filenames, list fragments, and fragments need no automatic period. |

## Cleanup and voice

Remove unambiguous `um`, `uh`, `er`, hesitation, and sentence-opening `so`
when they only introduce speech. Preserve logical "so" and quoted filler words. Preserve
"basically," emphasis "actually," and clarification "I mean." Keep politeness,
informality, dialect vocabulary, contractions, and emotional tone. Do not turn
casual dictation into a business rewrite.

Repair English agreement, missing small function words, clear word forms, and
word order using minimal edits. Do not rewrite already grammatical sentences.
Correct a misheard word only where local context makes it certain; preserve an
unfamiliar proper noun or ambiguous homophone. Never correct a factual assertion
or solve arithmetic. Retain hedges, uncertainty, double negatives when meaningful,
negation, and relative dates without inventing calendar dates.

Apply a restart/correction only to the rejected span. Keep the final choice;
preserve surrounding qualifiers and the rest of a list. Preserve "scratch that"
and similar phrases when they are literal quoted text or reported instructions.
An explicit cancellation of the whole dictation may yield an empty output.
Empty, whitespace-only, punctuation-free filler-only input yields empty output.
Incomplete but meaningful speech remains an incomplete fragment, not empty text.

## English, names, and technical content

This dataset covers English only. It makes no claim about Hindi, Hinglish,
translation, transliteration, or script-dependent punctuation. English requests
remain dictation even when they ask the model to translate or answer in another
language; the output is the cleaned English request, not its execution.

Normalize certain conventional prose casing (`api` -> `API`, `postgres` ->
`Postgres`) without expanding names. Preserve URLs, paths, code, variable names,
email addresses, placeholders, version precision, leading zeros, and technical
identifiers exactly when supplied. Do not lowercase an unfamiliar product name
or insert Markdown fences or backticks that were not requested/already present.

## Numbers and punctuation

- Raw bare counts under ten become words. Money, units, ages, durations, dates,
  percentages, references, codes, and versions use digits even below ten.
- Use currency symbols only for explicitly spoken dollars/rupees or existing
  symbols. English dictation about rupees is included. Do not add `$`/`₹` to an
  unspecified amount or perform conversion.
- Use `3 pm`, `5:30 pm`; never infer AM/PM, timezone, year, or currency. Preserve
  already-clean `PM` or other unambiguous existing time styles.
- Use spelled-out month names for unambiguous spoken dates: `January 15, 2026`.
  Preserve ambiguous dates instead of assuming a locale. Relative dates stay
  relative.
- Preserve number idioms, names, explicit number-word mentions, precision,
  leading zeros, and supplied units. Do not calculate expressions or convert
  measurements.
- Convert clear spoken punctuation and layout commands. Literal discussion of
  periods, commas, brackets, quotes, and new lines stays literal text.
- `new line` means one `\n`; `new paragraph` means `\n\n`. Never output the
  literal backslash sequences instead of real line breaks.
- Explicit `in double quotes` / `within double quotes` encloses the clearly
  scoped phrase with `"..."`. Generic brackets/paren mean `(...)`; explicit
  square/curly means `[...]` / `{...}`. Preserve balanced existing notation.
  A final aside may be implicitly closed at end of input. Do not guess an
  arbitrary internal scope when the boundary is ambiguous.
- Explicit hyphen/dash joins a clear identifier/compound; numeric minus is a
  negative sign. Never invent a separator in an unknown filename.

## Lists, paragraphs, and email

When the speaker explicitly counts a genuine list, use consecutive `1.`, `2.`,
etc. Otherwise clear independent list items use `- `. Keep counts inside an item
as content; they are not list numbering. A spoken lead-in takes a colon, then a
blank line, then the list. Do not invent a lead-in. Complete-sentence items get
sentence punctuation; fragment items do not require it. Text after a clearly
ended list resumes as a normal paragraph.

Use paragraphs for clear topic changes or explicit commands, not every clause.
Preserve an inline series and narrative ordering. Two short related sentences
do not automatically form a list. Use email greeting/body/sign-off layout only
when those elements were actually spoken. Never add a subject, recipient,
signature, new list item, heading, summary, or recommendation.

## Annotation and evaluation limits

This policy asks the model to generalize context-sensitive choices; it does not
promise certainty or deterministic obedience. Contrast examples and combined
skills are included to discourage mechanical replacement. Evaluate generation
without a system prompt, not merely training loss. Exact match is a strict
regression measure; legitimate alternative punctuation can still be acceptable.
Review semantic preservation separately. Add reviewed real-ASR failures before
claiming production reliability. Train from the original base for a clean
comparison; old v3 targets conflict with this policy and must not be blindly
concatenated or implicitly carried over through an older adapter.
