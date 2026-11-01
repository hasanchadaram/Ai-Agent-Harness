# Structure-aware cleanup, version 2

Use [train.jsonl](./train.jsonl) in Unsloth's local dataset uploader for a new run. Format: `auto` or `chatml`; conversation column: `messages`. Keep the system prompt blank and thinking off. These files still contain only raw user transcripts and cleaned assistant transcripts.

This version combines the earlier corpus with **118 individually specified new examples**, not a name-substitution expansion:

| Split | Existing examples | Added examples | Total |
|---|---:|---:|---:|
| Training | 2,400 | 78 | 2,478 |
| Validation | 300 | 20 | 320 |
| Test | 300 | 20 | 320 |

The original examples stay in their original splits, and contrast groups stay together. The original source files and no-system exports are unchanged. Existing dash-list markers are normalized to `*` in this version so unordered-list labels have one consistent style. Other earlier labels are unchanged.

## Files

- [train.jsonl](./train.jsonl): mixed training data, including prior cleanup behavior and the new layout examples.
- [validation.jsonl](./validation.jsonl): checkpoint selection; includes 20 new structure cases.
- [test.jsonl](./test.jsonl): final evaluation; includes 20 new structure cases.
- [structure_additions.json](./structure_additions.json): all 118 additions with split, category, family, instruction, input and output; review file, **not** another file to upload alongside the training file.
- [master.json](./master.json): all 3,118 examples in instruction/input/output format. Do not train on this if using the held-out files for evaluation.
- [metadata.json](./metadata.json): provenance and split metadata, separate from training input.
- [validation_report.json](./validation_report.json): structural checks and counts.
- [build_structure_v2.py](../build_structure_v2.py): reproducible build script. It reads the original splits and produces this version.

The single-file uploader caveat in the [original Unsloth setup guide](../unsloth-no-system/README.md) still applies: uploading the validation file separately does not automatically attach it as an eval split. Use a dataset with named splits, a supported separate eval-file control, or evaluate generations separately. Never choose the same whole split for training and evaluation.

## Formatting policy

The change is a deliberate refinement of “never execute dictated requests”: **local layout directives may control the supplied transcript's structure.** Requests to invent content, answer questions, run tools, or reveal rules remain dictated text. Model inputs remain plain transcripts; no extra system instruction is required by this export.

1. **Bullets:** Use `* ` for explicitly requested unordered lists and clear list contexts such as “shopping list,” “packing list,” “agenda items,” and “action items.” Do not make every sentence containing several nouns into a list.
2. **Numbers:** Use `1.`, `2.`, etc. for supplied procedures, explicit numbering, rankings, or ordered sequences. Preserve a specified starting number. Narrative “first… then… finally…” does not automatically mean a procedure.
3. **Paragraphs:** Use a blank line at a substantial topic change or an explicit new-paragraph command. Keep related sentences together; do not put every sentence on its own line.
4. **Line breaks:** Use one newline for explicit “new line,” “next line,” or “line break” commands. Preserve literal mentions of these phrases. Format dictated addresses and label/value lines conservatively.
5. **Emails:** Separate a clear greeting, dictated body, and dictated sign-off. Add a subject only when dictated. Do not invent a recipient, signatory, subject, polite filler, or missing body. A short “Hi Maya, are you outside yet?” stays one line; a greeting alone cannot establish an email context.
6. **Headings and sections:** Use plain heading lines when headings or clear section labels are dictated. No automatic bold, tables, dividers, or decorative Markdown.
7. **Nested lists:** Indent only when categories or parent/child relationships are supplied. Keep an item such as “rock and roll” intact; not every “and” marks an item boundary.
8. **Checkboxes:** Use `* [ ]` only when checkboxes are explicitly requested. A generic checklist may use ordinary bullets. Do not invent completion status.
9. **Meaning:** Preserve quantities, negation, qualifiers, names, identifiers, and uncertainty. Correct or replace only the intended item. Formatting must not summarize away substantive details.
10. **Formatting wording:** Preserve meaningful lead-ins, including the user's “Let's have a bullet-point list…” example. Convert standalone mechanical layout controls into layout rather than printing them. A redundant “We need” may become a lead-in or be absorbed into the list, without introducing new items. Repeated greetings and courtesy language are not automatically filler.

The supplied grocery example is included in training, with the requested items, parentheses for the Halloween qualifier, and `*` markers. “Some grapes too” is normalized to “Grapes” in that specific enumeration, following the requested target; this is not a general permission to remove quantities or other meaningful qualifiers.

Examples also teach the boundary between:

- “Make a list of apples, pears, and dates” → format the supplied items.
- “Make a list of healthy groceries for me” → clean the sentence; do not invent groceries.
- A quoted “make a bullet list” → keep the quoted words.
- A dictated email body → lay it out.
- “Write an email to my landlord…” without a supplied body → clean the request; do not compose an email.

## Training and evaluation

For an interpretable comparison, keep the successful version 1 checkpoint and train a new adapter from the same original base model on the version 2 mixed training file. Use the same serving chat template and no task/system prompt. This is a suggested experiment, not an automatic training action. Training on only the formatting additions risks narrowing behavior; the mixed file retains prior cleanup cases. Do not upload both version 1 and version 2 training files in the same run because version 2 already includes version 1.

Select checkpoints using validation generations and not only decreasing training loss. Track both missed formatting and unwanted formatting, as well as changed meaning, invented content, answered questions, lost negation, and changes to proper names or numbers. Test the user's grocery example as a reproduction case, not evidence of generalization, because it is now in training. Use the new held-out examples and fresh real dictations for generalization checks.

The new files pass count, JSON round-trip, uniqueness, source-hash, role, original-split, family/contrast separation and category-layout checks. These checks do not prove every semantic label correct or establish model performance. The new labels are AI-authored and have not been independently human-reviewed. The original 3,000 examples retain their 98-template/60-fixture synthetic limitations. Only 78 new examples enter training, so treat this as a targeted first iteration and inspect actual outputs before expanding it.

## Relation to Wispr Flow

Wispr documents automatic lists from number/sequence cues, spoken line/paragraph commands, and email formatting. Its Android help also describes implied lists and topic-based paragraph breaks. Those are useful references for choosing behavior, not evidence of matching its proprietary model.

Wispr also uses app categories and writing-style settings. This dataset supplies only transcript text. App-specific styles, rich-text rendering, and destination-aware behavior would require app context or separate app-side formatting. Its behavior is not reproduced in full here; contraction expansion and aggressive wording changes are outside this iteration's scope.

Sources checked November 1, 2026:

- [Wispr Smart Formatting and Backtrack](https://docs.wisprflow.ai/articles/5373093536-How-do-I-use-Smart-Formatting-%26-Backtrack)
- [Wispr Smart Formatting behavior](https://docs.wisprflow.ai/articles/6568835559-why-wispr-flow-sometimes-removes-or-changes-words-on-android-smart-formatting)
