# Notation labeling policy

Apply these rules when authoring gold cleanup outputs. They refine the earlier small-number and spoken-punctuation policy. They are not a system prompt supplied in the exported conversations.

## Hyphens, dashes and minus signs

- An explicitly dictated “hyphen” or “dash” joining parts of a clear identifier or compound becomes `-`, without spaces: `agreement hyphen two` → `agreement-2`, `B dash twelve` → `B-12`.
- Preserve the lexical part of a document name; do not invent title casing, extensions or separators. Standalone filename/title-like fragments can remain without terminal sentence punctuation.
- Digit sequences within identifiers retain leading zeros: `A hyphen zero zero five` → `A-005`.
- A clear suffix correction replaces that suffix, rather than deleting the entire surrounding sentence.
- “Minus” in a numeric context denotes a negative value, not an identifier joiner: `minus two point five` → `-2.5`. Do not infer subtraction from every hyphen.
- Preserve ordinary uses such as “a dash of salt,” quoted words, and explanations of hyphens. A model asked to explain punctuation must clean the question rather than answer it.
- If ASR supplies only `agreement two`, do not invent `agreement-2` from a private naming convention. Use a dictionary/app-level naming rule if that joiner is required.

## Brackets and scope

- Generic “in brackets,” “open bracket,” or “open parenthesis” denotes round parentheses when the context clearly marks a parenthetical aside.
- For a clearly final aside, an opener such as “in brackets” can enclose the rest of that aside without a spoken close command: `it's good in brackets it's not good` → `It's good (it's not good).`
- For an aside in the middle of a sentence, follow explicit close markers and retain the text after the aside outside it. Do not guess an arbitrary scope when multiple interpretations are equally plausible.
- Explicit “square brackets” denotes `[...]`; explicit “curly braces/curly brackets,” or an unambiguous brace-delimited template token, denotes `{...}`.
- Preserve literal mentions of brackets, hardware brackets, tax brackets, and quoted punctuation phrases.
- Keep all supplied words in the aside except normal cleanup, clear corrections and requested numeric normalization. A parenthesis is not permission to summarize.
- Keep already balanced notation intact. Place punctuation outside a short parenthetical phrase unless the entire parenthetical is an independent sentence.

## Numerals when appropriate

- Prefer digits for measurable quantities, counts, ages, durations, percentages, prices, dates, times, page/section references, versions and identifier components, including small counts: `3 copies`, `2 questions`, `7 minutes`, `5%`, `chapter 4`.
- Preserve decimal precision and leading zeros: `1.00`, `0029`.
- Use natural context-sensitive formats rather than converting an entire sentence mechanically. Keep unspecified times unspecified (`at 8`, not an invented `8:00 AM`), and do not invent dates, years, units or time zones.
- Ordinal row/column references can use `2nd`/`3rd`. Do not turn discourse markers such as “first, then…” into numbers unless a real ordered-list context exists.
- Preserve idioms, indefinite articles, lexical compounds and proper names: `one of a kind`, `one step at a time`, `a second`, `two-way street`, `Five Guys`, `OnePlus`, `The Three-Body Problem`.
- Preserve an explicit request to spell out a number and quoted number words: `write five in words` is dictated content, not a request to answer `five`.
- Retain unrecoverable fragments instead of pretending a disconnected word is a confidently recognized quantity.
- Do not evaluate arithmetic questions or convert references into factual answers; `what is six plus seven` → `What is 6 plus 7?`.

## Other behavior retained

Do not answer questions, execute commands, fabricate missing content, change technical identifiers, drop negation or remove meaningful uncertainty. Continue the previous list/email/paragraph policy. Training and serving use a raw user transcript and assistant cleanup with the model-native chat template; no system or task prompt is added.
